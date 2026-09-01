package com.hms.laboratory.device.astm;

import java.util.List;

/**
 * A cell-distribution curve as the analyzer measured it.
 *
 * @param x      channel positions, in femtolitres when the group's volume window is known
 * @param y      channel frequencies
 * @param xLabel {@code Volume (fL)} when x is a real volume axis, otherwise {@code Channel}
 */
public record Histogram(List<Double> x, List<Double> y, String xLabel) {

    public static final String VOLUME_AXIS = "Volume (fL)";
    public static final String CHANNEL_AXIS = "Channel";

    /**
     * Copies both axes on the way in, so a parsed curve is genuinely immutable.
     *
     * <p>Not a formality. This is measured patient data: it is persisted as JSONB, charted, and
     * used to derive MPV, PDW and RDW. A caller that could mutate the list behind a Histogram it
     * was handed could change a reported index without touching the code that reports it.
     */
    public Histogram {
        x = List.copyOf(x);
        y = List.copyOf(y);
    }

    public boolean isOnVolumeAxis() {
        return VOLUME_AXIS.equals(xLabel);
    }

    public int channelCount() {
        return y.size();
    }
}
