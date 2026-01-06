/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.buffer;

import java.nio.ByteBuffer;

/**
 * Implements a factory for creating ByteNativeBuffer instances
 */
public class ByteNativeBufferFactory extends AbstractNativeBufferFactory
{
    private static final int MIN_FRAGMENT_BYTES = ByteNativeBuffer.BYTES_PER_FRAGMENT;
    private DcCorrectionManager mDcCorrectionManager = new DcCorrectionManager();

    @Override
    public INativeBuffer getBuffer(ByteBuffer samples, long timestamp)
    {
        ByteBuffer buffer = samples.slice();
        int available = buffer.remaining();
        int usable = available - (available % MIN_FRAGMENT_BYTES);

        if(usable == 0)
        {
            return null;
        }

        buffer.limit(usable);

        byte[] copy = new byte[usable];
        buffer.get(copy);

        if(mDcCorrectionManager.shouldCalculateDc())
        {
            calculateDc(copy);
        }

        return new ByteNativeBuffer(copy, timestamp, mDcCorrectionManager.getAverageDc(), getSamplesPerMillisecond());
    }

    /**
     * Calculates the average DC in the sample stream so that it can be subtracted from the samples when the
     * native buffer is used.
     * @param samples containing DC offset
     */
    private void calculateDc(byte[] samples)
    {
        float dcAccumulator = 0;

        for(byte sample: samples)
        {
            dcAccumulator += (sample & 0xFF);
        }

        dcAccumulator /= samples.length;
        dcAccumulator -= 127.5f;
        dcAccumulator /= 128.0f;
        mDcCorrectionManager.adjust(dcAccumulator);
    }
}
