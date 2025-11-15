package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Simple representation of a bladeRF range descriptor.
 * Mirrors the structure used by libbladeRF for min/max/step metadata so the Java
 * control plane can share the same limits without depending on the native library.
 */
public class BladeRFRange
{
    private final long mMin;
    private final long mMax;
    private final long mStep;
    private final long mScale;

    public BladeRFRange(long min, long max, long step, long scale)
    {
        mMin = min;
        mMax = max;
        mStep = step;
        mScale = scale;
    }

    public long getMin()
    {
        return mMin;
    }

    public long getMax()
    {
        return mMax;
    }

    public long getStep()
    {
        return mStep;
    }

    public long getScale()
    {
        return mScale;
    }

    public boolean contains(long value)
    {
        return value >= mMin && value <= mMax;
    }

    public long clamp(long value)
    {
        if(value < mMin)
        {
            return mMin;
        }
        if(value > mMax)
        {
            return mMax;
        }
        return value;
    }
}
