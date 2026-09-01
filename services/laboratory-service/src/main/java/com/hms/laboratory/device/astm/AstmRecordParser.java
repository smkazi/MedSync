package com.hms.laboratory.device.astm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses individual ASTM records.
 *
 * <p>Ported from {@code parsers/astm_parser.py} (smkazi/HaematologyIS). The field positions below
 * are not the ASTM standard's — they are what Sysmex analyzers were observed to actually send, with
 * the standard positions kept as fallbacks. That double lookup is the whole reason this code exists
 * rather than a generic ASTM library, so it is preserved deliberately.
 */
final class AstmRecordParser {

    /** Eight digits: a date of birth as {@code YYYYMMDD}. */
    private static final Pattern DOB_YYYYMMDD = Pattern.compile("^\\d{8}$");

    /** An age written with a year suffix, e.g. {@code 058Y}. */
    private static final Pattern AGE_WITH_YEAR_SUFFIX = Pattern.compile("(\\d{1,3})[Yy]");

    /** Age and sex packed together, e.g. {@code 045M}. */
    private static final Pattern AGE_WITH_SEX_SUFFIX = Pattern.compile("^0*(\\d+)[MmFf]$");

    private static final Pattern BARE_AGE = Pattern.compile("^\\d{1,3}$");

    private static final Pattern CONTAINS_LETTER = Pattern.compile("[A-Za-z]");

    private static final Pattern CONTAINS_DIGIT = Pattern.compile("\\d");

    private static final Pattern CARETS = Pattern.compile("\\^+");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

    /** A reference range as transmitted, e.g. {@code 4.0-11.0} or {@code 4.0 – 11.0}. */
    private static final Pattern NORMAL_RANGE = Pattern.compile("([\\d.]+)\\s*[-–]\\s*([\\d.]+)");

    /**
     * Sysmex XP-100 / XP-300 three-part differential codes.
     *
     * <p>Per the XP host-interface specification an analyzer transmits either these {@code W-}
     * codes or the display names, depending on its "Item name" setting. SCR/MCR/LCR are the
     * small/middle/large cell <em>ratios</em> (lymphocyte / mixed / neutrophil percentages) and
     * SCC/MCC/LCC the corresponding absolute <em>counts</em>.
     */
    private static final Map<String, String> XP_PARAMETER_ALIASES = Map.of(
            "W-SCR", "LYM%", "W-MCR", "MXD%", "W-LCR", "NEUT%",
            "W-SCC", "LYM#", "W-MCC", "MXD#", "W-LCC", "NEUT#");

    private AstmRecordParser() {
    }

    /** Field at {@code index}, trimmed, or {@code fallback} when absent or empty. */
    private static String field(List<String> parts, int index, String fallback) {
        if (index >= parts.size()) {
            return fallback;
        }
        String value = parts.get(index).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String field(List<String> parts, int index) {
        return field(parts, index, "");
    }

    static AstmRecord.Header parseHeader(List<String> parts) {
        return new AstmRecord.Header(field(parts, 4), field(parts, 11), field(parts, 12));
    }

    /**
     * Parses a P record.
     *
     * <p>Sysmex Poch-100i positions: [2] patient/sample id, [4] name as {@code SURNAME^FIRSTNAME},
     * [6] date of birth {@code YYYYMMDD}, [7] sex, [16] referring doctor. Standard ASTM uses
     * [5] name, [7] date of birth, [8] sex, [14] referring doctor. Sysmex positions are tried
     * first, then the standard ones.
     */
    static AstmRecord.Patient parsePatient(List<String> parts) {
        // An absent id stays blank: fabricating one (the original's "UNKNOWN") would make two
        // unidentified samples collide into a single chart.
        String patientId = field(parts, 2, field(parts, 3));

        String rawName = "";
        for (int index : new int[] {4, 5, 3}) {
            String candidate = field(parts, index);
            if (!candidate.isEmpty() && CONTAINS_LETTER.matcher(candidate).find()) {
                rawName = candidate;
                break;
            }
        }
        String name = WHITESPACE_RUN.matcher(CARETS.matcher(rawName).replaceAll(" ").trim())
                .replaceAll(" ").trim();

        String dateOfBirthRaw = field(parts, 6, field(parts, 7));
        Integer age = parseAge(dateOfBirthRaw);

        String rawSex = field(parts, 7, field(parts, 8));
        // Guard: when the "sex" position actually holds a date of birth, ignore it.
        if (rawSex.length() > 2) {
            rawSex = "";
        }
        if (rawSex.isEmpty() && !dateOfBirthRaw.isEmpty()) {
            String last = dateOfBirthRaw.substring(dateOfBirthRaw.length() - 1).toUpperCase();
            if (last.equals("M") || last.equals("F")) {
                rawSex = last;
            }
        }
        String sex = normaliseSex(rawSex);

        String referringDoctor = field(parts, 16, field(parts, 14, field(parts, 15, field(parts, 13))));
        referringDoctor = CARETS.matcher(referringDoctor).replaceAll(" ").trim();

        return new AstmRecord.Patient(patientId, name, age, sex, dateOfBirthRaw, referringDoctor);
    }

    /**
     * Derives the patient's age from whatever the analyzer put in the date-of-birth field:
     * a full date, an age with a {@code Y} suffix, an age with a packed sex letter, or a bare
     * number. Returns null when none of those apply.
     */
    static Integer parseAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (DOB_YYYYMMDD.matcher(raw).matches()) {
            try {
                LocalDate birthDate = LocalDate.of(Integer.parseInt(raw.substring(0, 4)),
                        Integer.parseInt(raw.substring(4, 6)), Integer.parseInt(raw.substring(6, 8)));
                return java.time.Period.between(birthDate, LocalDate.now()).getYears();
            } catch (RuntimeException ex) {
                // Not a real date (e.g. month 00) - fall through to the age patterns.
            }
        }
        Matcher yearSuffix = AGE_WITH_YEAR_SUFFIX.matcher(raw);
        if (yearSuffix.find()) {
            return Integer.parseInt(yearSuffix.group(1));
        }
        Matcher sexSuffix = AGE_WITH_SEX_SUFFIX.matcher(raw);
        if (sexSuffix.matches()) {
            return Integer.parseInt(sexSuffix.group(1));
        }
        if (BARE_AGE.matcher(raw).matches()) {
            return Integer.parseInt(raw);
        }
        return null;
    }

    private static String normaliseSex(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "M";
        }
        String sex = raw.substring(0, 1).toUpperCase();
        return (sex.equals("M") || sex.equals("F")) ? sex : "M";
    }

    /**
     * Parses an O record.
     *
     * <p>A Poch-100i sends {@code O|1||^^          KAMAL^M|...}: the name and sex are packed into
     * field [3], and the sample-id field [2] is empty. An XN-330 packs the sample sequence number
     * into [3] instead. Both are handled.
     */
    static AstmRecord.Order parseOrder(List<String> parts) {
        String sampleId = field(parts, 2);
        String packed = field(parts, 3);

        String name = "";
        String sex = "M";
        if (!packed.isEmpty()) {
            for (String component : packed.split("\\^")) {
                String trimmed = component.trim();
                if (trimmed.length() == 1 && (trimmed.equalsIgnoreCase("M") || trimmed.equalsIgnoreCase("F"))) {
                    sex = trimmed.toUpperCase();
                } else if (trimmed.length() > 1) {
                    name = trimmed;
                }
            }
        }
        if (sampleId.isEmpty() && !packed.isEmpty()) {
            Matcher number = FIRST_NUMBER.matcher(packed);
            if (number.find()) {
                sampleId = number.group();
            }
        }
        return new AstmRecord.Order(sampleId, name, sex, field(parts, 5));
    }

    /**
     * Parses an R record — the measured result.
     *
     * <p>The test identifier arrives as an ASTM component string such as {@code ^^^WBC^1} or
     * {@code ^^^^WBC^26}; the trailing number is the XP dilution ratio, not part of the name, so
     * the last non-numeric component wins.
     *
     * <p>Values the analyzer could not measure are transmitted as symbol strings
     * ({@code ***.*} masked, {@code +++.+} over-range, {@code ---.-} under-range). They carry no
     * number, so they are stored as no value rather than as those symbols.
     */
    static AstmRecord.Result parseResult(List<String> parts) {
        String testField = parts.size() > 2 ? parts.get(2) : "";
        String testName = "";
        String[] components = testField.split("\\^");
        for (int i = components.length - 1; i >= 0; i--) {
            String component = components[i].trim();
            if (!component.isEmpty() && !isAllDigits(component)) {
                testName = component;
                break;
            }
        }
        if (testName.isEmpty()) {
            testName = testField.replace("^", "").trim();
        }
        testName = XP_PARAMETER_ALIASES.getOrDefault(testName.toUpperCase(), testName);

        String value = parts.size() > 3 ? parts.get(3).trim() : "";
        if (!value.isEmpty() && !CONTAINS_DIGIT.matcher(value).find()) {
            value = "";
        }
        String unit = parts.size() > 4 ? parts.get(4).trim() : "";
        String normalRange = parts.size() > 5 ? parts.get(5).trim() : "";
        String flag = parts.size() > 6 ? parts.get(6).trim() : "";

        Double normalLow = null;
        Double normalHigh = null;
        Matcher range = NORMAL_RANGE.matcher(normalRange);
        if (range.lookingAt()) {
            try {
                normalLow = Double.valueOf(range.group(1));
                normalHigh = Double.valueOf(range.group(2));
            } catch (NumberFormatException ex) {
                normalLow = null;
                normalHigh = null;
            }
        }
        return new AstmRecord.Result(testName, value, unit, normalRange, normalLow, normalHigh, flag);
    }

    static AstmRecord.Comment parseComment(List<String> parts) {
        return new AstmRecord.Comment(parts.size() > 1 ? parts.get(1) : "",
                parts.size() > 2 ? parts.get(2) : "");
    }

    /** Splits a record line on the ASTM field delimiter, keeping trailing empty fields. */
    static List<String> splitFields(String line) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts;
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }
}
