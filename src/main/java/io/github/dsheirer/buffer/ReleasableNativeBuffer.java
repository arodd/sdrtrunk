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

package io.github.dsheirer.buffer;

/**
 * Native buffer that supports explicit lifecycle management so that the underlying storage can be
 * returned to a pool as soon as all consumers finish with the buffer.
 */
public interface ReleasableNativeBuffer extends INativeBuffer, AutoCloseable
{
    /**
     * Retains the buffer for a downstream asynchronous consumer.
     *
     * @return this buffer for chaining
     */
    ReleasableNativeBuffer retain();

    /**
     * Releases this buffer.  When the reference count reaches zero the buffer may be recycled.
     */
    void release();

    /**
     * Convenience for try-with-resources compatibility.
     */
    @Override
    default void close()
    {
        release();
    }
}
