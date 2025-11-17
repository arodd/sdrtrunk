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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Factory for bladeRF native buffers.
 */
class BladeRFNativeBufferFactory extends AbstractNativeBufferFactory
{
    @Override
    public INativeBuffer getBuffer(ByteBuffer samples, long timestamp)
    {
        ByteBuffer duplicate = samples.duplicate();
        duplicate.order(ByteOrder.LITTLE_ENDIAN);
        duplicate.rewind();

        // Wrap the FX3 transfer buffer directly to avoid per-packet heap allocations while maintaining
        // little-endian ordering
        ShortBuffer shortBuffer = duplicate.asShortBuffer();

        return new BladeRFNativeBuffer(shortBuffer, timestamp, getSamplesPerMillisecond());
    }
}
