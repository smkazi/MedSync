package com.hms.laboratory.device.astm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and interprets cell-distribution histograms from an ASTM transmission.
 *
 * <p>Ported from {@code HistogramExtractor} in {@code parsers/astm_parser.py}
 * (smkazi/HaematologyIS). Sysmex analyzers deliver a histogram either as a tagged R record whose
 * value is a comma-separated frequency array ({@code WBC-H}, {@code RBC-H}, {@code PLT-H}) or
 * embedded in a C comment record; both routes are supported.
 */
public final class HistogramExtractor {

    /** The cell groups an analyzer produces a distribution for. */
    public static final List<String> HISTOGRAM_GROUPS = List.of("WBC", "RBC", "PLT");

    /**
     * Fewest channels a numeric array must have before it counts as a histogram.
     *
     * <p>A real distribution curve is 100–256 channels. This guard is what stops a couple of stray
     * numbers in a comment from being plotted as a patient's platelet distribution.
     */
    public static final int MIN_HISTOGRAM_CHANNELS = 12;

    /** Frequencies are non-negative and bounded; anything larger is not a channel value. */
    private static final double MAX_CHANNEL_FREQUENCY = 100_000;

    /**
     * Result-record identifiers that carry a numeric histogram, matched case-insensitively.
     */
    private static final Map<String, Pattern> HISTOGRAM_TAGS = Map.of(
            "WBC", Pattern.compile("^W(?:BC)?[-_ ]?(?:H|HIST(?:O|OGRAM)?|DISTRIB\\w*)$", Pattern.CASE_INSENSITIVE),
            "RBC", Pattern.compile("^R(?:BC)?[-_ ]?(?:H|HIST(?:O|OGRAM)?|DISTRIB\\w*)$", Pattern.CASE_INSENSITIVE),
            "PLT", Pattern.compile("^P(?:LT)?[-_ ]?(?:H|HIST(?:O|OGRAM)?|DISTRIB\\w*)$", Pattern.CASE_INSENSITIVE));

    /**
     * The fixed cell-volume window (fL) each histogram spans on Sysmex XP / KX-21 instruments, so a
     * transmitted channel array can be plotted on the same axis the analyzer prints:
     * WBC 0–300 fL, RBC 0–250 fL (counted 25–250), PLT 0–30 fL (counted 2–30).
     */
    private static final Map<String, double[]> VOLUME_WINDOW = Map.of(
            "WBC", new double[] {0.0, 300.0},
            "RBC", new double[] {0.0, 250.0},
            "PLT", new double[] {0.0, 30.0});

    /**
     * Discriminator bounds (fL) used when integrating a curve into its indices:
     * {@code {lower, upper, largeCellThreshold}}. Platelets are counted PL–PU (2–30 fL) with
     * P-LCR the fraction above the fixed 12 fL discriminator; red cells are counted RL–RU
     * (25–250 fL).
     */
    private static final Map<String, double[]> INDEX_BOUNDS = Map.of(
            "PLT", new double[] {2.0, 30.0, 12.0},
            "RBC", new double[] {25.0, 250.0, Double.NaN});

    /** Sysmex defines RDW-SD as the distribution width at 20% of peak height. */
    private static final double RDW_SD_HEIGHT_FRACTION = 0.20;

    private static final Pattern LABEL_SEPARATOR = Pattern.compile("[:~=|]");

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private HistogramExtractor() {
    }

    /**
     * The cell group a parameter names a histogram for, or null when it is an ordinary result.
     *
     * <p>Accepts both a clean tag ({@code RBC-H}) and the raw ASTM component form
     * ({@code ^^^RBC-H^1}) by testing each caret-delimited component.
     */
    public static String histogramGroup(String parameter) {
        String raw = parameter == null ? "" : parameter.trim();
        if (raw.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(raw);
        for (String component : raw.split("\\^")) {
            if (!component.isBlank()) {
                candidates.add(component.trim());
            }
        }
        for (String candidate : candidates) {
            for (Map.Entry<String, Pattern> entry : HISTOGRAM_TAGS.entrySet()) {
                if (entry.getValue().matcher(candidate).matches()) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Whether a parameter is a transmitted histogram rather than a measurement. Histograms are
     * routed to the graph and kept out of the numeric result rows.
     */
    public static boolean isHistogramParameter(String parameter) {
        return histogramGroup(parameter) != null;
    }

    /** Parses tagged R records into plottable curves, keyed by cell group. */
    public static Map<String, Histogram> extractFromResults(List<AstmRecord.Result> results) {
        Map<String, Histogram> histograms = new LinkedHashMap<>();
        if (results == null) {
            return histograms;
        }
        for (AstmRecord.Result result : results) {
            String group = histogramGroup(result.parameter());
            if (group == null || histograms.containsKey(group)) {
                continue;
            }
            Histogram histogram = parseArray(result.value(), group);
            if (histogram != null) {
                histograms.put(group, histogram);
            }
        }
        return histograms;
    }

    /**
     * Parses C records for histogram data. A comment qualifies only if it both names a cell group
     * and carries a long enough numeric array — a comment mentioning "PLT" in prose must not be
     * mistaken for a curve.
     */
    public static Map<String, Histogram> extractFromComments(List<AstmRecord.Comment> comments) {
        Map<String, Histogram> histograms = new LinkedHashMap<>();
        if (comments == null) {
            return histograms;
        }
        for (AstmRecord.Comment comment : comments) {
            String text = comment.text() == null ? "" : comment.text();
            String upper = text.toUpperCase();
            for (String group : HISTOGRAM_GROUPS) {
                if (upper.contains(group) && !histograms.containsKey(group)) {
                    Histogram histogram = parseArray(text, group);
                    if (histogram != null) {
                        histograms.put(group, histogram);
                    }
                }
            }
        }
        return histograms;
    }

    /**
     * Extracts a channel-frequency array from text (comma, space, caret or semicolon separated) and
     * scales it onto the group's real volume window.
     *
     * @return the curve, or null when the text is not a usable histogram (too few channels, or all
     *         zeroes — a parameter the analyzer did not measure)
     */
    static Histogram parseArray(String text, String group) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // Strip a leading "WBC:" / "RBC~" style label before reading the numbers.
        String[] labelSplit = LABEL_SEPARATOR.split(text, 2);
        String body = labelSplit[labelSplit.length - 1];

        List<Double> y = new ArrayList<>();
        Matcher numbers = NUMBER.matcher(body);
        while (numbers.find()) {
            try {
                double value = Double.parseDouble(numbers.group());
                if (value >= 0 && value < MAX_CHANNEL_FREQUENCY) {
                    y.add(value);
                }
            } catch (NumberFormatException ex) {
                // Not a channel value; skip it.
            }
        }
        if (y.size() < MIN_HISTOGRAM_CHANNELS) {
            return null;
        }
        if (y.stream().noneMatch(value -> value > 0)) {
            return null;
        }
        return onVolumeAxis(y, group);
    }

    /** Places a frequency array on the group's volume axis, or on a plain channel axis if unknown. */
    static Histogram onVolumeAxis(List<Double> y, String group) {
        double[] window = group == null ? null : VOLUME_WINDOW.get(group.toUpperCase());
        if (window != null && y.size() > 1) {
            double step = (window[1] - window[0]) / (y.size() - 1);
            List<Double> x = new ArrayList<>(y.size());
            for (int i = 0; i < y.size(); i++) {
                x.add(window[0] + i * step);
            }
            return new Histogram(List.copyOf(x), List.copyOf(y), Histogram.VOLUME_AXIS);
        }
        List<Double> x = new ArrayList<>(y.size());
        for (int i = 0; i < y.size(); i++) {
            x.add((double) i);
        }
        return new Histogram(List.copyOf(x), List.copyOf(y), Histogram.CHANNEL_AXIS);
    }

    /**
     * Derives the distribution indices the analyzer computes from a curve, by integrating between
     * the group's discriminators (PL–PU for platelets, RL–RU for red cells).
     *
     * <p>Platelets yield MPV (mean fL), PDW (fL) and P-LCR (% at or above 12 fL); red cells yield
     * MCV (mean fL), RDW-CV (%) and RDW-SD (fL). {@code relArea} is a <em>relative</em> cell mass,
     * not an absolute count: a true count needs the analyzer's aperture calibration, so the
     * transmitted count is always kept and only these shape indices are derived.
     *
     * @return the derived indices, or an empty map when the curve is not usable
     */
    public static Map<String, Double> deriveIndices(Histogram histogram, String group) {
        if (histogram == null || group == null) {
            return Map.of();
        }
        List<Double> y = histogram.y();
        List<Double> x = histogram.x();
        if (y.size() < 3) {
            return Map.of();
        }
        if (x.size() != y.size() || !histogram.isOnVolumeAxis()) {
            // Rebuild the axis from the group's window when the curve arrived on channel numbers.
            double[] window = VOLUME_WINDOW.get(group.toUpperCase());
            if (window == null) {
                return Map.of();
            }
            x = onVolumeAxis(y, group).x();
        }

        double[] bounds = INDEX_BOUNDS.get(group.toUpperCase());
        double lower = bounds == null ? x.get(0) : bounds[0];
        double upper = bounds == null ? x.get(x.size() - 1) : bounds[1];
        double largeCellThreshold = bounds == null ? Double.NaN : bounds[2];

        double area = 0;
        double weightedSum = 0;
        double peak = 0;
        for (int i = 0; i < y.size(); i++) {
            double xi = x.get(i);
            double yi = y.get(i);
            if (xi < lower || xi > upper || yi <= 0) {
                continue;
            }
            area += yi;
            weightedSum += xi * yi;
            peak = Math.max(peak, yi);
        }
        if (area <= 0) {
            return Map.of();
        }
        double mean = weightedSum / area;

        double varianceSum = 0;
        for (int i = 0; i < y.size(); i++) {
            double xi = x.get(i);
            double yi = y.get(i);
            if (xi < lower || xi > upper || yi <= 0) {
                continue;
            }
            varianceSum += Math.pow(xi - mean, 2) * yi;
        }
        double standardDeviation = Math.sqrt(varianceSum / area);

        Map<String, Double> indices = new LinkedHashMap<>();
        indices.put("rel_area", area);

        if ("PLT".equalsIgnoreCase(group)) {
            indices.put("MPV", round(mean, 1));
            indices.put("PDW", round(standardDeviation, 1));
            if (!Double.isNaN(largeCellThreshold)) {
                double large = 0;
                for (int i = 0; i < y.size(); i++) {
                    double xi = x.get(i);
                    double yi = y.get(i);
                    if (xi >= largeCellThreshold && xi <= upper && yi > 0) {
                        large += yi;
                    }
                }
                indices.put("P-LCR", round(large / area * 100.0, 1));
            }
        } else if ("RBC".equalsIgnoreCase(group)) {
            indices.put("MCV", round(mean, 1));
            if (mean != 0) {
                indices.put("RDW-CV", round(standardDeviation / mean * 100.0, 1));
            }
            double threshold = RDW_SD_HEIGHT_FRACTION * peak;
            Double lowestAbove = null;
            Double highestAbove = null;
            for (int i = 0; i < y.size(); i++) {
                double xi = x.get(i);
                double yi = y.get(i);
                if (xi < lower || xi > upper || yi <= 0) {
                    continue;
                }
                if (yi >= threshold) {
                    if (lowestAbove == null) {
                        lowestAbove = xi;
                    }
                    highestAbove = xi;
                }
            }
            if (lowestAbove != null) {
                indices.put("RDW-SD", round(highestAbove - lowestAbove, 1));
            }
        }
        return indices;
    }

    /**
     * Plateletcrit: {@code PCT (%) = PLT(10³/µL) × MPV(fL) / 10000}.
     *
     * <p>A count above 10000 is taken to be an absolute per-µL value and scaled back to 10³/µL,
     * because analyzers transmit both conventions.
     *
     * @return the plateletcrit, or null when either input is unusable
     */
    public static Double plateletcrit(String plateletCount, Double meanPlateletVolume) {
        if (plateletCount == null || meanPlateletVolume == null) {
            return null;
        }
        double count;
        try {
            count = Double.parseDouble(plateletCount.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (count > 10_000) {
            count /= 1000.0;
        }
        return round(count * meanPlateletVolume / 10_000.0, 3);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
