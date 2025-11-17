package io.github.dsheirer.source.tuner.bladerf;

/**
 * Minimal set of constants used by the bladeRF controller. These values mirror the
 * definitions in libbladeRF's public headers for compatibility.
 */
public final class BladeRFConstants
{
    private BladeRFConstants()
    {
    }

    public static final int GAIN_DEFAULT = 0;
    public static final int GAIN_MGC = 1;
    public static final int GAIN_FASTATTACK_AGC = 2;
    public static final int GAIN_SLOWATTACK_AGC = 3;
    public static final int GAIN_HYBRID_AGC = 4;
}
