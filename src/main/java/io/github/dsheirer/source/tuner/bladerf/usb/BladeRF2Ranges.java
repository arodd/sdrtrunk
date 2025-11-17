package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.tuner.bladerf.BladeRFConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * bladeRF 2 range metadata replicated from bladerf2_common.h so the Java
 * control plane can answer capability queries without libbladeRF.
 */
public final class BladeRF2Ranges
{
    private static final BladeRFRange SAMPLE_RATE_RANGE_BASE = new BladeRFRange(520_834L, 61_440_000L, 2, 1);
    private static final BladeRFRange SAMPLE_RATE_RANGE_OVERSAMPLE = new BladeRFRange(6_250_000L, 122_880_000L, 2, 1);
    private static final BladeRFRange BANDWIDTH_RANGE = new BladeRFRange(200_000L, 56_000_000L, 1, 1);
    private static final int[] RECOMMENDED_SAMPLE_RATES = new int[]
            {1_000_000, 1_536_000, 2_000_000, 2_400_000, 2_800_000, 3_000_000, 4_000_000,
                    5_000_000, 6_000_000, 7_000_000, 8_000_000, 10_000_000, 12_000_000,
                    14_000_000, 15_000_000, 20_000_000, 24_000_000, 28_000_000, 30_000_000,
                    30_720_000, 32_000_000, 36_000_000, 38_400_000, 40_000_000, 44_800_000,
                    48_000_000, 52_000_000, 56_000_000, 61_440_000};
    private static final long[] RECOMMENDED_BANDWIDTHS = new long[]
            {1_500_000L, 2_000_000L, 2_500_000L, 3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L,
                    7_000_000L, 8_000_000L, 10_000_000L, 12_000_000L, 14_000_000L, 15_000_000L,
                    20_000_000L, 24_000_000L, 28_000_000L, 30_000_000L, 36_000_000L, 40_000_000L,
                    48_000_000L};
    private static final int GAIN_MIN = -16;
    private static final int GAIN_MAX = 60;
    private static final List<BladeRF2GainMode> GAIN_MODES = Collections.unmodifiableList(Arrays.asList(
            new BladeRF2GainMode("Automatic", BladeRFConstants.GAIN_DEFAULT),
            new BladeRF2GainMode("Manual", BladeRFConstants.GAIN_MGC),
            new BladeRF2GainMode("Fast", BladeRFConstants.GAIN_FASTATTACK_AGC),
            new BladeRF2GainMode("Slow", BladeRFConstants.GAIN_SLOWATTACK_AGC),
            new BladeRF2GainMode("Hybrid", BladeRFConstants.GAIN_HYBRID_AGC)));

    private BladeRF2Ranges()
    {
    }

    public static BladeRFRange getSampleRateRange()
    {
        return SAMPLE_RATE_RANGE_BASE;
    }

    public static BladeRFRange getOversampleRange()
    {
        return SAMPLE_RATE_RANGE_OVERSAMPLE;
    }

    public static BladeRFRange getBandwidthRange()
    {
        return BANDWIDTH_RANGE;
    }

    public static List<Integer> enumerateSampleRates(int defaultRate, boolean oversample)
    {
        BladeRFRange range = oversample ? SAMPLE_RATE_RANGE_OVERSAMPLE : SAMPLE_RATE_RANGE_BASE;
        List<Integer> values = new ArrayList<>();
        for(int rate: RECOMMENDED_SAMPLE_RATES)
        {
            if(range.contains(rate))
            {
                values.add(rate);
            }
        }

        if(values.isEmpty())
        {
            values.add((int)range.clamp(defaultRate));
        }
        else
        {
            if(values.get(0) != range.getMin())
            {
                values.add(0, (int)range.getMin());
            }

            int last = values.get(values.size() - 1);
            if(last != range.getMax())
            {
                values.add((int)range.getMax());
            }
        }

        return values;
    }

    public static List<Long> enumerateBandwidths(long defaultBandwidth)
    {
        List<Long> values = new ArrayList<>();
        for(long bandwidth: RECOMMENDED_BANDWIDTHS)
        {
            if(BANDWIDTH_RANGE.contains(bandwidth) && !values.contains(bandwidth))
            {
                values.add(bandwidth);
            }
        }

        if(values.isEmpty())
        {
            values.add(BANDWIDTH_RANGE.clamp(defaultBandwidth));
        }
        else
        {
            if(!values.contains(BANDWIDTH_RANGE.getMin()))
            {
                values.add(0, BANDWIDTH_RANGE.getMin());
            }

            long last = values.get(values.size() - 1);
            if(last != BANDWIDTH_RANGE.getMax())
            {
                values.add(BANDWIDTH_RANGE.getMax());
            }
        }

        return values;
    }

    public static List<BladeRF2GainMode> getGainModes()
    {
        return GAIN_MODES;
    }

    public static int getGainMinimum()
    {
        return GAIN_MIN;
    }

    public static int getGainMaximum()
    {
        return GAIN_MAX;
    }

    /**
     * Recommended sample rates exposed for controllers that still need a discrete list.
     *
     * @return copy of the recommended sample rate list
     */
    public static int[] getRecommendedSampleRates()
    {
        return Arrays.copyOf(RECOMMENDED_SAMPLE_RATES, RECOMMENDED_SAMPLE_RATES.length);
    }

    /**
     * Recommended bandwidth list exposed for controllers that still need discrete bandwidths.
     *
     * @return copy of the recommended bandwidth list
     */
    public static long[] getRecommendedBandwidths()
    {
        return Arrays.copyOf(RECOMMENDED_BANDWIDTHS, RECOMMENDED_BANDWIDTHS.length);
    }
}
