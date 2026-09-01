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

    public boolean isOnVolumeAxis() {
        return VOLUME_AXIS.equals(xLabel);
    }

    public int channelCount() {
        return y.size();
    }
}
