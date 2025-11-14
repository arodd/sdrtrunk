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

package io.github.dsheirer.source.tuner.bladerf.api;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.Buffer;
import java.util.List;

/**
 * Minimal libbladeRF binding for the functionality required by sdrtrunk.
 */
public interface BladeRFLibrary extends Library
{
    BladeRFLibrary INSTANCE = Native.load("bladeRF", BladeRFLibrary.class);

    int BLADERF_SERIAL_LENGTH = 33;
    int BLADERF_CHANNEL_RX0 = 0;
    int BLADERF_RX = 0;
    int BLADERF_RX_X1 = 0;
    int BLADERF_FORMAT_SC16_Q11 = 0;
    int BLADERF_GAIN_DEFAULT = 0;
    int BLADERF_GAIN_MGC = 1;
    int BLADERF_GAIN_FASTATTACK_AGC = 2;
    int BLADERF_GAIN_SLOWATTACK_AGC = 3;
    int BLADERF_GAIN_HYBRID_AGC = 4;

    int BLADERF_LNA_GAIN_UNKNOWN = 0;
    int BLADERF_LNA_GAIN_BYPASS = 1;
    int BLADERF_LNA_GAIN_MID = 2;
    int BLADERF_LNA_GAIN_MAX = 3;

    int BLADERF_ERR_TIMEOUT = -6;

    int bladerf_open(PointerByReference device, String deviceIdentifier);

    void bladerf_close(Pointer device);

    String bladerf_get_board_name(Pointer device);

    int bladerf_get_serial(Pointer device, byte[] serial);

    int bladerf_set_sample_rate(Pointer device, int channel, int rate, IntByReference actual);

    int bladerf_set_bandwidth(Pointer device, int channel, int bandwidth, IntByReference actual);

    int bladerf_set_frequency(Pointer device, int channel, long frequency);

    int bladerf_get_sample_rate_range(Pointer device, int channel, PointerByReference range);

    int bladerf_get_bandwidth_range(Pointer device, int channel, PointerByReference range);

    int bladerf_get_gain_range(Pointer device, int channel, PointerByReference range);

    int bladerf_get_gain_modes(Pointer device, int channel, PointerByReference modes);

    int bladerf_set_lna_gain(Pointer device, int gain);

    int bladerf_set_rxvga1(Pointer device, int gain);

    int bladerf_set_rxvga2(Pointer device, int gain);

    int bladerf_set_gain_mode(Pointer device, int channel, int mode);

    int bladerf_set_gain(Pointer device, int channel, int gain);

    int bladerf_sync_config(Pointer device, int layout, int format, int numBuffers, int bufferSize,
                            int numTransfers, int timeoutMs);

    int bladerf_sync_rx(Pointer device, Buffer samples, int numSamples, Pointer metadata, int timeoutMs);

    int bladerf_enable_module(Pointer device, int channel, boolean enable);

    int bladerf_get_channel_count(Pointer device, int direction);

    int bladerf_set_bias_tee(Pointer device, int channel, boolean enable);

    void bladerf_free_range(Pointer range);

    void bladerf_free_gain_modes(Pointer modes);

    String bladerf_strerror(int error);

    /**
     * Represents the bladerf_range structure.
     */
    class Range extends Structure
    {
        public long min;
        public long max;
        public long step;
        public float scale;

        public Range() {}

        public Range(Pointer pointer)
        {
            super(pointer);
            if(pointer != null)
            {
                read();
            }
        }
        @Override
        protected List<String> getFieldOrder()
        {
            return List.of("min", "max", "step", "scale");
        }
    }

    /**
     * Represents the bladerf_gain_modes structure.
     */
    class GainMode extends Structure
    {
        public Pointer name;
        public int mode;

        public GainMode() {}

        public GainMode(Pointer pointer)
        {
            super(pointer);
            if(pointer != null)
            {
                read();
            }
        }

        @Override
        protected List<String> getFieldOrder()
        {
            return List.of("name", "mode");
        }

        public String getName()
        {
            if(name == null)
            {
                return "";
            }

            try
            {
                return name.getString(0);
            }
            catch(Throwable t)
            {
                return "";
            }
        }
    }
}
