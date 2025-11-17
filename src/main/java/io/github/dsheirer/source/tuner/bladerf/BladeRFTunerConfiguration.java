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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.bladerf.BladeRFTunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;

/**
 * Configuration for bladeRF tuners.
 */
public class BladeRFTunerConfiguration extends TunerConfiguration
{
    public static final String DEFAULT_GAIN_MODE = "Automatic";

    private int mSampleRate = BladeRFTunerController.DEFAULT_SAMPLE_RATE;
    private long mBandwidth = BladeRFTunerController.BANDWIDTH_AUTO;
    private String mGainModeName = DEFAULT_GAIN_MODE;
    private int mOverallGain = 0;
    private int mChannelId = 0;
    private boolean mBiasTEnabled = false;

    public BladeRFTunerConfiguration()
    {
        super(BladeRFTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ,
                BladeRFTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ);
    }

    public BladeRFTunerConfiguration(String uniqueID)
    {
        super(uniqueID);
    }

    @JsonIgnore
    @Override
    public TunerType getTunerType()
    {
        return TunerType.BLADE_RF;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "sample_rate_hz")
    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "bandwidth_hz")
    public long getBandwidth()
    {
        return mBandwidth;
    }

    public void setBandwidth(long bandwidth)
    {
        mBandwidth = bandwidth;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "gain_mode")
    public String getGainModeName()
    {
        return mGainModeName;
    }

    public void setGainModeName(String gainModeName)
    {
        mGainModeName = gainModeName;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "overall_gain")
    public int getOverallGain()
    {
        return mOverallGain;
    }

    public void setOverallGain(int overallGain)
    {
        mOverallGain = overallGain;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "channel_id")
    public int getChannelId()
    {
        return mChannelId;
    }

    public void setChannelId(int channelId)
    {
        mChannelId = channelId;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "bias_t_enabled")
    public boolean isBiasTEnabled()
    {
        return mBiasTEnabled;
    }

    public void setBiasTEnabled(boolean enabled)
    {
        mBiasTEnabled = enabled;
    }
}
