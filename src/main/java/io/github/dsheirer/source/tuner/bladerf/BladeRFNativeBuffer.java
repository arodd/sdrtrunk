/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.bladerf;

import io.github.dsheirer.buffer.AbstractNativeBuffer;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.util.Iterator;

/**
 * Native buffer wrapper for bladeRF signed 16-bit IQ samples.
 */
class BladeRFNativeBuffer extends AbstractNativeBuffer
{
    private static final int MAX_COMPLEX_FRAGMENT_SIZE = 2048;
    private static final float SAMPLE_SCALE = 1.0f / 2048.0f;
    private final short[] mSamples;

    BladeRFNativeBuffer(short[] samples, long timestamp, float samplesPerMillisecond)
    {
        super(timestamp, samplesPerMillisecond);
        mSamples = samples;
    }

    @Override
    public int sampleCount()
    {
        return mSamples.length / 2;
    }

    @Override
    public Iterator<ComplexSamples> iterator()
    {
        return new ComplexIterator();
    }

    @Override
    public Iterator<InterleavedComplexSamples> iteratorInterleaved()
    {
        return new InterleavedIterator();
    }

    private class ComplexIterator implements Iterator<ComplexSamples>
    {
        private int mOffset = 0;

        @Override
        public boolean hasNext()
        {
            return mOffset < mSamples.length;
        }

        @Override
        public ComplexSamples next()
        {
            int remainingShorts = mSamples.length - mOffset;
            int complexSamples = Math.min(MAX_COMPLEX_FRAGMENT_SIZE, remainingShorts / 2);
            float[] i = new float[complexSamples];
            float[] q = new float[complexSamples];
            long timestamp = getFragmentTimestamp(mOffset);

            for(int x = 0; x < complexSamples; x++)
            {
                i[x] = mSamples[mOffset++] * SAMPLE_SCALE;
                q[x] = mSamples[mOffset++] * SAMPLE_SCALE;
            }

            return new ComplexSamples(i, q, timestamp);
        }
    }

    private class InterleavedIterator implements Iterator<InterleavedComplexSamples>
    {
        private int mOffset = 0;

        @Override
        public boolean hasNext()
        {
            return mOffset < mSamples.length;
        }

        @Override
        public InterleavedComplexSamples next()
        {
            int remainingShorts = mSamples.length - mOffset;
            int complexSamples = Math.min(MAX_COMPLEX_FRAGMENT_SIZE, remainingShorts / 2);
            float[] converted = new float[complexSamples * 2];
            long timestamp = getFragmentTimestamp(mOffset);

            int index = 0;
            for(int x = 0; x < complexSamples; x++)
            {
                converted[index++] = mSamples[mOffset++] * SAMPLE_SCALE;
                converted[index++] = mSamples[mOffset++] * SAMPLE_SCALE;
            }

            return new InterleavedComplexSamples(converted, timestamp);
        }
    }
}
