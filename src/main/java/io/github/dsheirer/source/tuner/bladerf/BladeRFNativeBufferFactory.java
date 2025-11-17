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

import io.github.dsheirer.buffer.AbstractNativeBufferFactory;
import io.github.dsheirer.buffer.INativeBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Factory for bladeRF native buffers.
 */
class BladeRFNativeBufferFactory extends AbstractNativeBufferFactory
{
    private static final int MAX_BUFFER_POOL_SIZE = 32;
    private final ArrayDeque<short[]> mBufferPool = new ArrayDeque<>();

    @Override
    public INativeBuffer getBuffer(ByteBuffer samples, long timestamp)
    {
        ByteBuffer duplicate = samples.duplicate();
        duplicate.order(ByteOrder.LITTLE_ENDIAN);
        duplicate.rewind();

        ShortBuffer shortBuffer = duplicate.asShortBuffer();
        int length = shortBuffer.remaining();
        short[] data = borrowBuffer(length);
        shortBuffer.get(data, 0, length);

        return new BladeRFNativeBuffer(data, length, timestamp, getSamplesPerMillisecond(), this::recycleBuffer);
    }

    /**
     * Borrows a short array from the pool or allocates a new one if none are available.
     * @param length of the short array needed
     * @return a short array of at least the requested length
     */
    private synchronized short[] borrowBuffer(int length)
    {
        Iterator<short[]> iterator = mBufferPool.iterator();

        while(iterator.hasNext())
        {
            short[] candidate = iterator.next();

            if(candidate.length >= length)
            {
                iterator.remove();
                return candidate;
            }
        }

        return new short[length];
    }

    /**
     * Returns a short array to the pool for reuse.
     * @param buffer to recycle
     */
    private synchronized void recycleBuffer(short[] buffer)
    {
        if(buffer != null && mBufferPool.size() < MAX_BUFFER_POOL_SIZE)
        {
            mBufferPool.addFirst(buffer);
        }
    }
}
