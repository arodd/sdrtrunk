package io.github.dsheirer.source.tuner.bladerf;

/**
 * Minimal set of libbladeRF constants needed by the Java controller. These values mirror the
 * definitions in libbladeRF's public headers so the app can avoid loading the native library
 * unless the legacy control path is required.
 */
public final class BladeRFConstants
{
    private BladeRFConstants()
    {
    }

    public static final int SERIAL_LENGTH = 33;
    public static final int RX_DIRECTION = 0;
    public static final int SYNC_LAYOUT_RX_X1 = 0;
    public static final int SYNC_FORMAT_SC16_Q11 = 0;

    public static final int GAIN_DEFAULT = 0;
    public static final int GAIN_MGC = 1;
    public static final int GAIN_FASTATTACK_AGC = 2;
    public static final int GAIN_SLOWATTACK_AGC = 3;
    public static final int GAIN_HYBRID_AGC = 4;
}
