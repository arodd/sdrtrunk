package io.github.dsheirer.source.tuner.bladerf.usb;

import java.util.Objects;

/**
 * Simple rational rate helper that mirrors libbladeRF's bladerf_rational_rate
 * structure. Used when programming the Si5338 so we can reuse the existing
 * algorithms without the native library.
 */
public class BladeRationalRate
{
    private long mInteger;
    private long mNumerator;
    private long mDenominator;

    public BladeRationalRate()
    {
        this(0, 0, 1);
    }

    public BladeRationalRate(long integer, long numerator, long denominator)
    {
        mInteger = integer;
        mNumerator = numerator;
        mDenominator = Math.max(denominator, 1);
        reduce();
    }

    public BladeRationalRate copy()
    {
        return new BladeRationalRate(mInteger, mNumerator, mDenominator);
    }

    public long getInteger()
    {
        return mInteger;
    }

    public void setInteger(long integer)
    {
        mInteger = integer;
    }

    public long getNumerator()
    {
        return mNumerator;
    }

    public void setNumerator(long numerator)
    {
        mNumerator = numerator;
    }

    public long getDenominator()
    {
        return mDenominator;
    }

    public void setDenominator(long denominator)
    {
        mDenominator = Math.max(denominator, 1);
    }

    /**
     * Reduces the rational to an integer plus a simplified fractional component.
     */
    public void reduce()
    {
        if(mDenominator <= 0)
        {
            mDenominator = 1;
        }

        if(mNumerator >= mDenominator)
        {
            long whole = mNumerator / mDenominator;
            mInteger += whole;
            mNumerator -= whole * mDenominator;
        }

        long gcd = greatestCommonDivisor(mNumerator, mDenominator);
        if(gcd > 0)
        {
            mNumerator /= gcd;
            mDenominator /= gcd;
        }
    }

    /**
     * Doubles the rational value. Used when the hardware requires a doubled
     * reference clock (e.g. LMS sample clocks).
     */
    public void doubleValue()
    {
        mInteger *= 2;
        mNumerator *= 2;
        reduce();
    }

    public double toDouble()
    {
        double fraction = (mDenominator == 0) ? 0.0 : (double)mNumerator / (double)mDenominator;
        return mInteger + fraction;
    }

    @Override
    public String toString()
    {
        if(mNumerator == 0)
        {
            return Long.toString(mInteger);
        }
        return mInteger + " + " + mNumerator + "/" + mDenominator;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }
        if(!(o instanceof BladeRationalRate))
        {
            return false;
        }
        BladeRationalRate that = (BladeRationalRate)o;
        return mInteger == that.mInteger && mNumerator == that.mNumerator && mDenominator == that.mDenominator;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mInteger, mNumerator, mDenominator);
    }

    private long greatestCommonDivisor(long a, long b)
    {
        long x = Math.abs(a);
        long y = Math.abs(b);
        while(y != 0)
        {
            long temp = y;
            y = x % y;
            x = temp;
        }
        return x;
    }
}
