package com.hms.laboratory.device.kdps;

import com.hms.laboratory.device.astm.Histogram;
import java.util.List;
import java.util.Map;

/**
 * One sample decoded from a Sysmex K-DPS transmission.
 *
 * @param identifier the header's single identity field — the patient name for a named sample, or
 *                   the sample number for an unnamed one. Exposed as both {@code name} and
 *                   {@code sampleId} downstream, because the analyzer uses one field for either.
 * @param measuredAt the analyzer's timestamp, {@code yyyy-MM-dd HH:mm}, or blank if unreadable
 * @param dateKey    the same instant as a compact sortable key, used to match a graphic to a result
 * @param results    the numeric CBC row from the header frame — in K-DPS-only mode this is the
 *                   sample's <em>only</em> source of results, since no ASTM is transmitted
 * @param histograms the decoded distribution curves, keyed by cell group
 */
public record KdpsSample(String identifier, String measuredAt, String dateKey, List<KdpsResult> results,
                         Map<String, Histogram> histograms) {

    /** One fixed-width numeric field from the header frame's CBC row. */
    public record KdpsResult(String parameter, String value, String unit) {
    }
}
