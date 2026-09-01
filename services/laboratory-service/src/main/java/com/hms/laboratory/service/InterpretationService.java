package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.NotFoundException;
import com.hms.laboratory.domain.HistogramFlagNote;
import com.hms.laboratory.domain.InterpretiveRule;
import com.hms.laboratory.domain.InterpretiveRuleCondition;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.MorphologyThreshold;
import com.hms.laboratory.domain.ParameterScale;
import com.hms.laboratory.repo.HistogramFlagNoteRepository;
import com.hms.laboratory.repo.InterpretiveRuleRepository;
import com.hms.laboratory.repo.MorphologyThresholdRepository;
import com.hms.laboratory.repo.ParameterScaleRepository;
import com.hms.laboratory.web.dto.LabDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a set of measured values into the narrative a pathologist signs.
 *
 * <p>Ported from {@code reports/report_generator.py} in smkazi/HaematologyIS — the interpretive
 * comments, the Sysmex histogram flag explanations, and the derived peripheral-smear morphology.
 * That code was in daily use in a working laboratory, and the wording is a pathologist's, not mine.
 *
 * <p><strong>Three tiers of number, deliberately kept apart.</strong> A reference interval decides
 * whether a value is flagged H or L. An interpretive threshold, wider, decides whether it earns a
 * sentence — haemoglobin flags below 11.5 g/dL for a woman but only comments below 9.0, because a
 * report that printed a paragraph for every out-of-range number is a report nobody reads. And a
 * morphology cut-off is a third number again: a red cell is called microcytic below MCV 76 while the
 * microcytosis comment fires below 70. Collapsing them would lose meaning.
 *
 * <p><strong>Everything numeric here is configuration.</strong> In the source these were Python
 * literals, so a laboratory that wanted anaemia to comment below 10.0 needed a code change. They are
 * rows now. What stays in code is the <em>shape</em>: that morphology reads size, then chromia, then
 * anisocytosis, because each clause carries behaviour and a new clause needs new logic.
 *
 * <p><strong>This is decision support, not a diagnosis.</strong> It is deterministic and auditable —
 * no model, no inference — and a pathologist's own smear comment overrides the derived one, as it
 * did in the source. Nothing here writes to a patient record; it annotates a report a human signs.
 */
@Service
public class InterpretationService {

    /** Histogram flag fields arrive as {@code PL*}, {@code (RU)}, {@code T1 T2} — codes, plus noise. */
    private static final Pattern FLAG_CODE = Pattern.compile("[A-Z]+\\d*");

    private final InterpretiveRuleRepository rules;
    private final HistogramFlagNoteRepository histogramNotes;
    private final ParameterScaleRepository scales;
    private final MorphologyThresholdRepository thresholds;
    private final AuditService audit;

    public InterpretationService(InterpretiveRuleRepository rules,
                                 HistogramFlagNoteRepository histogramNotes,
                                 ParameterScaleRepository scales,
                                 MorphologyThresholdRepository thresholds,
                                 AuditService audit) {
        this.rules = rules;
        this.histogramNotes = histogramNotes;
        this.scales = scales;
        this.thresholds = thresholds;
        this.audit = audit;
    }

    /**
     * The interpretation for one order's results.
     *
     * @param manualMorphology a smear comment entered by a human, or null. When present it is used
     *                         verbatim and nothing is derived — a pathologist who looked down a
     *                         microscope outranks an inference from indices.
     */
    @Transactional(readOnly = true)
    public LabDtos.InterpretationView interpret(List<LabResult> results, String manualMorphology) {
        Map<String, BigDecimal> values = normalisedValues(results);

        List<String> notes = new ArrayList<>(ruleNotes(values));
        notes.addAll(histogramFlagNotes(results));

        LabDtos.MorphologyView morphology = manualMorphology != null && !manualMorphology.isBlank()
                ? new LabDtos.MorphologyView(manualMorphology, null, null, false)
                : deriveMorphology(values);

        return new LabDtos.InterpretationView(List.copyOf(notes), morphology);
    }

    // ---- configuration ---------------------------------------------------------

    /** Every rule, active or not, so an administrator can see what is switched off. */
    @Transactional(readOnly = true)
    public List<LabDtos.InterpretiveRuleResponse> allRules() {
        return rules.findAllByOrderByDisplayOrderAsc().stream().map(rule ->
                new LabDtos.InterpretiveRuleResponse(rule.getId(), rule.getCode(), rule.getLabel(),
                        rule.getMessage(), rule.getDisplayOrder(), rule.isActive(),
                        rule.getConditions().stream()
                                .map(c -> new LabDtos.RuleConditionResponse(c.getId(),
                                        c.parameterAliases(), c.getOperator(), c.getThreshold()))
                                .toList()))
                .toList();
    }

    @Transactional
    public LabDtos.InterpretiveRuleResponse updateRule(String code,
                                                       LabDtos.UpdateInterpretiveRuleRequest request) {
        InterpretiveRule rule = rules.findByCode(code)
                .orElseThrow(() -> new NotFoundException("No interpretive rule '" + code + "'"));
        if (request.message() != null && !request.message().isBlank()) {
            rule.setMessage(request.message().trim());
        }
        if (request.active() != null) {
            rule.setActive(request.active());
        }
        rules.save(rule);
        audit.record("INTERPRETIVE_RULE_UPDATED", "InterpretiveRule", rule.getId(),
                "rule " + code + " active=" + rule.isActive());
        return allRules().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<LabDtos.MorphologyThresholdResponse> allThresholds() {
        return thresholds.findAll().stream()
                .sorted(java.util.Comparator.comparing(MorphologyThreshold::getCode))
                .map(t -> new LabDtos.MorphologyThresholdResponse(t.getCode(), t.getThreshold(), t.getNote()))
                .toList();
    }

    // ---- rules -----------------------------------------------------------------

    private List<String> ruleNotes(Map<String, BigDecimal> values) {
        List<String> notes = new ArrayList<>();
        for (InterpretiveRule rule : rules.findByActiveTrueOrderByDisplayOrderAsc()) {
            if (rule.getConditions().isEmpty()) {
                // A rule with no conditions would otherwise vacuously match and print on every
                // report. Skipped rather than trusted: a half-configured rule must not shout.
                continue;
            }
            boolean matched = true;
            for (InterpretiveRuleCondition condition : rule.getConditions()) {
                Optional<BigDecimal> value = firstPresent(values, condition.parameterAliases());
                if (value.isEmpty() || !compare(value.get(), condition.getOperator(), condition.getThreshold())) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                notes.add(rule.getMessage());
            }
        }
        return notes;
    }

    /**
     * Compares against a threshold.
     *
     * <p>An unrecognised operator returns false rather than throwing. The alternative is that one bad
     * configuration row takes down every report in the laboratory; a rule that fails to fire is
     * visible in review, and the CHECK constraint on the column already refuses the value at write
     * time. This is the belt to that braces.
     */
    private static boolean compare(BigDecimal value, String operator, BigDecimal threshold) {
        int cmp = value.compareTo(threshold);
        return switch (operator) {
            case "<" -> cmp < 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static Optional<BigDecimal> firstPresent(Map<String, BigDecimal> values, List<String> aliases) {
        for (String alias : aliases) {
            BigDecimal value = values.get(alias);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    // ---- histogram flag codes --------------------------------------------------

    private List<String> histogramFlagNotes(List<LabResult> results) {
        Map<String, String> byCode = new HashMap<>();
        for (HistogramFlagNote note : histogramNotes.findByActiveTrue()) {
            byCode.put(note.getCode().toUpperCase(Locale.ROOT), note.getMessage());
        }

        // Ordered and de-duplicated: several parameters commonly carry the same code, and printing
        // the same paragraph four times is how a report stops being read.
        Set<String> messages = new LinkedHashSet<>();
        for (LabResult result : results) {
            String flag = result.getFlag();
            if (flag == null || flag.isBlank()) {
                continue;
            }
            Matcher matcher = FLAG_CODE.matcher(flag.toUpperCase(Locale.ROOT));
            while (matcher.find()) {
                String message = byCode.get(matcher.group());
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        return List.copyOf(messages);
    }

    // ---- morphology ------------------------------------------------------------

    private LabDtos.MorphologyView deriveMorphology(Map<String, BigDecimal> values) {
        Map<String, BigDecimal> cutoff = new HashMap<>();
        for (MorphologyThreshold threshold : thresholds.findAll()) {
            cutoff.put(threshold.getCode(), threshold.getThreshold());
        }

        String red = redCellMorphology(values, cutoff);
        String white = whiteCellMorphology(values, cutoff);
        String platelets = plateletMorphology(values, cutoff);

        if (red == null && white == null && platelets == null) {
            return null;
        }
        return new LabDtos.MorphologyView(null, red, white, true, platelets);
    }

    private String redCellMorphology(Map<String, BigDecimal> values, Map<String, BigDecimal> cutoff) {
        BigDecimal mcv = values.get("MCV");
        BigDecimal mch = values.get("MCH");
        BigDecimal mchc = values.get("MCHC");
        BigDecimal rdwCv = values.get("RDW-CV");
        BigDecimal rdwSd = values.get("RDW-SD");
        if (mcv == null && mch == null && mchc == null) {
            return null;
        }

        String size = "Normocytic";
        if (below(mcv, cutoff.get("MCV_MICROCYTIC"))) {
            size = "Microcytic";
        } else if (above(mcv, cutoff.get("MCV_MACROCYTIC"))) {
            size = "Macrocytic";
        }

        boolean hypochromic = below(mch, cutoff.get("MCH_HYPOCHROMIC"))
                || below(mchc, cutoff.get("MCHC_HYPOCHROMIC"));
        String description = size + (hypochromic ? " hypochromic" : " normochromic");

        // RDW-SD is the direct width in femtolitres and is preferred when the analyzer sent it;
        // RDW-CV is a coefficient of variation and moves with mean cell volume, so it is only the
        // fallback. Taking whichever is present rather than both, because an instrument that sends
        // one does not send the other.
        boolean anisocytosis = rdwSd != null
                ? above(rdwSd, cutoff.get("RDW_SD_ANISO"))
                : above(rdwCv, cutoff.get("RDW_CV_ANISO"));
        if (anisocytosis) {
            description += " with anisocytosis";
        }
        return description;
    }

    private String whiteCellMorphology(Map<String, BigDecimal> values, Map<String, BigDecimal> cutoff) {
        BigDecimal wbc = values.get("WBC");
        if (wbc == null) {
            return null;
        }

        String base;
        if (above(wbc, cutoff.get("WBC_HIGH"))) {
            base = "Leucocytosis";
        } else if (below(wbc, cutoff.get("WBC_LOW"))) {
            base = "Leucopenia";
        } else {
            base = "Total & differential leucocyte count within normal limits";
        }

        List<String> extra = new ArrayList<>(2);
        BigDecimal neut = values.get("NEUT%");
        if (above(neut, cutoff.get("NEUT_PCT_HIGH"))) {
            extra.add("neutrophilia");
        } else if (below(neut, cutoff.get("NEUT_PCT_LOW"))) {
            extra.add("neutropenia");
        }
        BigDecimal lym = values.get("LYM%");
        if (above(lym, cutoff.get("LYM_PCT_HIGH"))) {
            extra.add("lymphocytosis");
        } else if (below(lym, cutoff.get("LYM_PCT_LOW"))) {
            extra.add("lymphopenia");
        }
        return extra.isEmpty() ? base : base + " with " + String.join(" & ", extra);
    }

    private String plateletMorphology(Map<String, BigDecimal> values, Map<String, BigDecimal> cutoff) {
        BigDecimal platelets = values.get("PLT");
        if (platelets == null) {
            return null;
        }
        if (below(platelets, cutoff.get("PLT_LOW"))) {
            return "Decreased on smear (Thrombocytopenia)";
        }
        if (above(platelets, cutoff.get("PLT_HIGH"))) {
            return "Increased on smear (Thrombocytosis)";
        }
        return "Adequate on smear";
    }

    private static boolean below(BigDecimal value, BigDecimal threshold) {
        return value != null && threshold != null && value.compareTo(threshold) < 0;
    }

    private static boolean above(BigDecimal value, BigDecimal threshold) {
        return value != null && threshold != null && value.compareTo(threshold) > 0;
    }

    // ---- value extraction ------------------------------------------------------

    /**
     * The numeric values, keyed by parameter and normalised onto one scale.
     *
     * <p>The normalisation is the load-bearing part, ported as-is. An analyzer may transmit WBC as
     * {@code 7.36} or {@code 7360} depending on model and configuration, and a threshold written
     * against one scale never fires against the other — silently, with no error, just a comment that
     * stops appearing. So a value above the configured guard is taken to be on the absolute scale and
     * divided.
     */
    private Map<String, BigDecimal> normalisedValues(List<LabResult> results) {
        Map<String, ParameterScale> byParameter = new HashMap<>();
        for (ParameterScale scale : scales.findAll()) {
            byParameter.put(scale.getParameter().toUpperCase(Locale.ROOT), scale);
        }

        Map<String, BigDecimal> values = new HashMap<>();
        for (LabResult result : results) {
            String parameter = result.getParameter();
            String raw = result.getValue();
            if (parameter == null || raw == null || raw.isBlank()) {
                continue;
            }
            BigDecimal value;
            try {
                value = new BigDecimal(raw.trim());
            } catch (NumberFormatException ex) {
                // Masked and out-of-range readings arrive as *** or +++ and carry no number. The
                // ingest already stores those as no value; this is the belt to that braces.
                continue;
            }
            String key = parameter.toUpperCase(Locale.ROOT);
            ParameterScale scale = byParameter.get(key);
            if (scale != null && value.compareTo(scale.getAbove()) > 0) {
                value = value.divide(scale.getDivideBy(), 4, java.math.RoundingMode.HALF_UP);
            }
            values.put(key, value);
        }
        return values;
    }
}
