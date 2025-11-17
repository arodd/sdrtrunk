package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Simple version descriptor for bladeRF firmware/FPGA components.
 */
public class BladeRFVersion implements Comparable<BladeRFVersion>
{
    private final int mMajor;
    private final int mMinor;
    private final int mPatch;

    public BladeRFVersion(int major, int minor, int patch)
    {
        mMajor = Math.max(major, 0);
        mMinor = Math.max(minor, 0);
        mPatch = Math.max(patch, 0);
    }

    public static BladeRFVersion parse(String versionString)
    {
        if(versionString == null)
        {
            return null;
        }

        String normalized = versionString.trim();
        if(normalized.startsWith("v") || normalized.startsWith("V"))
        {
            normalized = normalized.substring(1);
        }

        String[] parts = normalized.split("\\.");
        int major = parts.length > 0 ? parsePart(parts[0]) : 0;
        int minor = parts.length > 1 ? parsePart(parts[1]) : 0;
        int patch = parts.length > 2 ? parsePart(parts[2]) : 0;
        return new BladeRFVersion(major, minor, patch);
    }

    private static int parsePart(String input)
    {
        try
        {
            return Integer.parseInt(input);
        }
        catch(NumberFormatException nfe)
        {
            return 0;
        }
    }

    public boolean atLeast(int major, int minor, int patch)
    {
        return compareTo(new BladeRFVersion(major, minor, patch)) >= 0;
    }

    public int getMajor()
    {
        return mMajor;
    }

    public int getMinor()
    {
        return mMinor;
    }

    public int getPatch()
    {
        return mPatch;
    }

    @Override
    public int compareTo(BladeRFVersion other)
    {
        if(other == null)
        {
            return 1;
        }

        if(mMajor != other.mMajor)
        {
            return Integer.compare(mMajor, other.mMajor);
        }

        if(mMinor != other.mMinor)
        {
            return Integer.compare(mMinor, other.mMinor);
        }

        return Integer.compare(mPatch, other.mPatch);
    }

    @Override
    public String toString()
    {
        return mMajor + "." + mMinor + "." + mPatch;
    }
}
