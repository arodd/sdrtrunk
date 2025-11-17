package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Gain mode descriptor mirroring the entries exposed by libbladeRF for
 * bladeRF 2 receivers. Decoupled from the controller so other components can
 * reuse the metadata when populating UI choices or validating settings.
 */
public class BladeRF2GainMode
{
    private final String mName;
    private final int mMode;

    public BladeRF2GainMode(String name, int mode)
    {
        mName = name;
        mMode = mode;
    }

    public String getName()
    {
        return mName;
    }

    public int getMode()
    {
        return mMode;
    }
}
