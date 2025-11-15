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

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.bladerf.api.BladeRFLibrary;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFNiosAccess;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFCapabilities;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbConstants;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbDevice;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbProtocol;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFVersion;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Device;
import org.usb4java.DeviceHandle;

/**
 * bladeRF tuner controller that leverages the libbladeRF control plane and shared USB streaming.
 */
public class BladeRFTunerController extends USBTunerController
{
    private static final Logger mLog = LoggerFactory.getLogger(BladeRFTunerController.class);

    public static final long MINIMUM_TUNABLE_FREQUENCY_HZ = 20_000_000L;
    public static final long MAXIMUM_TUNABLE_FREQUENCY_HZ = 6_000_000_000L;
    public static final int DEFAULT_SAMPLE_RATE = 2_000_000;
    public static final long BANDWIDTH_AUTO = -1L;
    private static final long RANGE_STEP_DEFAULT = 2_000_000L;
    private static final int[] RECOMMENDED_SAMPLE_RATES = new int[]
            {1_000_000, 1_536_000, 2_000_000, 2_400_000, 2_800_000, 3_000_000, 4_000_000,
                    5_000_000, 6_000_000, 7_000_000, 8_000_000, 10_000_000, 12_000_000,
                    14_000_000, 15_000_000, 20_000_000, 24_000_000, 28_000_000, 30_000_000,
                    30_720_000, 32_000_000, 36_000_000, 38_400_000, 40_000_000, 44_800_000,
                    48_000_000, 52_000_000, 56_000_000, 61_440_000};
    private static final long[] RECOMMENDED_BANDWIDTHS = new long[]
            {1_500_000, 2_000_000, 2_500_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000,
                    7_000_000, 8_000_000, 10_000_000, 12_000_000, 14_000_000, 15_000_000,
                    20_000_000, 24_000_000, 28_000_000, 30_000_000, 36_000_000, 40_000_000, 48_000_000};
    private static final int DC_HALF_BANDWIDTH = 5_000;
    private static final double USABLE_BANDWIDTH_PERCENT = 0.90;
    private static final int BUFFER_SIZE_SAMPLES = 4_096;
    private static final int NUM_BUFFERS = 32;
    private static final int NUM_TRANSFERS = 16;
    private static final int STREAM_TIMEOUT_MS = 1_000;
    private static final int BYTES_PER_SAMPLE = 4; //16-bit I + 16-bit Q
    private final BladeRFLibrary mLibrary = BladeRFLibrary.INSTANCE;
    private final BladeRFNativeBufferFactory mNativeBufferFactory = new BladeRFNativeBufferFactory();
    private final int mBus;
    private final int mDeviceAddress;
    private BladeRFUsbDevice mUsbDevice;
    private BladeRFVersion mFirmwareVersion;
    private BladeRFVersion mFpgaVersion;
    private long mUsbCapabilities;
    private final List<Integer> mSupportedSampleRates = new ArrayList<>();
    private final List<Long> mSupportedBandwidths = new ArrayList<>();
    private final List<GainModeInfo> mSupportedGainModes = new ArrayList<>();

    private Pointer mDevice;
    private int mActualSampleRate = DEFAULT_SAMPLE_RATE;
    private long mBandwidthMin = DEFAULT_SAMPLE_RATE;
    private long mBandwidthMax = DEFAULT_SAMPLE_RATE;
    private int mGainMinimum = 0;
    private int mGainMaximum = 60;
    private GainModeInfo mActiveGainMode;
    private int mChannelCount = 1;
    private int mConfiguredChannelId = 0;
    private boolean mBiasTSupported = false;
    private boolean mConfiguredBiasTEnabled = false;
    private boolean mFreeRangeSupported = true;
    private boolean mFreeGainModesSupported = true;
    private long mFrequency = TunerConfiguration.DEFAULT_FREQUENCY;
    private String mSerial = "";
    private String mBoardName = "bladeRF";

    // Configured values that we attempt to apply whenever the device is running
    private int mConfiguredSampleRate = DEFAULT_SAMPLE_RATE;
    private long mConfiguredBandwidth = BANDWIDTH_AUTO;
    private String mConfiguredGainModeName = BladeRFTunerConfiguration.DEFAULT_GAIN_MODE;
    private int mConfiguredOverallGain = 0;

    /**
     * Constructs an instance
     */
    public BladeRFTunerController(int bus, String portAddress, int deviceAddress, ITunerErrorListener tunerErrorListener)
    {
        super(bus, portAddress, MINIMUM_TUNABLE_FREQUENCY_HZ, MAXIMUM_TUNABLE_FREQUENCY_HZ, DC_HALF_BANDWIDTH,
                USABLE_BANDWIDTH_PERCENT, tunerErrorListener);
        mBus = bus;
        mDeviceAddress = deviceAddress;
        try
        {
            mFrequencyController.setSampleRate(mActualSampleRate);
        }
        catch(SourceException se)
        {
            mLog.warn("Unable to set initial frequency controller sample rate", se);
        }
        mNativeBufferFactory.setSamplesPerMillisecond(mActualSampleRate / 1000.0f);
    }

    @Override
    protected void preStart() throws SourceException
    {
        if(mDevice != null)
        {
            return;
        }

        PointerByReference deviceRef = new PointerByReference();
        String deviceIdentifier = String.format("*:device=%d:%d", mBus, mDeviceAddress);
        int status = mLibrary.bladerf_open(deviceRef, deviceIdentifier);

        if(status != 0)
        {
            throw new SourceException(errorMessage("open device", status));
        }

        mDevice = deviceRef.getValue();

        try
        {
            initializeDevice();
        }
        catch(SourceException se)
        {
            deviceStop();
            throw se;
        }
    }

    @Override
    protected void deviceStart() throws SourceException
    {
        initializeUsbMetadata();
    }

    @Override
    protected void deviceStop()
    {
        streamingCleanup();
        mUsbDevice = null;
        mFirmwareVersion = null;
        mFpgaVersion = null;
        mUsbCapabilities = 0;

        if(mDevice != null)
        {
            try
            {
                mLibrary.bladerf_close(mDevice);
            }
            catch(Exception e)
            {
                mLog.debug("bladeRF close failed", e);
            }

            mDevice = null;
        }
    }

    @Override
    protected INativeBufferFactory getNativeBufferFactory()
    {
        return mNativeBufferFactory;
    }

    @Override
    protected int getTransferBufferSize()
    {
        return BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE;
    }

    @Override
    protected void prepareStreaming()
    {
        if(mDevice == null)
        {
            return;
        }

        try
        {
            checkStatus(mLibrary.bladerf_sync_config(mDevice, BladeRFLibrary.BLADERF_RX_X1,
                    BladeRFLibrary.BLADERF_FORMAT_SC16_Q11, NUM_BUFFERS, BUFFER_SIZE_SAMPLES, NUM_TRANSFERS,
                    STREAM_TIMEOUT_MS), "configure sync stream");
            checkStatus(mLibrary.bladerf_enable_module(mDevice, getChannelConstant(), true),
                    "enable rx module");
        }
        catch(SourceException se)
        {
            mLog.error("Unable to start bladeRF streaming", se);
            setErrorMessage(se.getMessage());
        }
    }

    @Override
    protected void streamingCleanup()
    {
        if(mDevice != null)
        {
            int status = mLibrary.bladerf_enable_module(mDevice, getChannelConstant(), false);
            if(status != 0)
            {
                mLog.debug("Unable to disable bladeRF module: {}", errorMessage("disable module", status));
            }
        }
    }

    private void initializeDevice() throws SourceException
    {
        try
        {
            if(mUsbDevice != null)
            {
                mBoardName = mUsbDevice.getBoardName();
                mBiasTSupported = mBoardName.startsWith("bladerf2");
            }
            else
            {
                String boardName = mLibrary.bladerf_get_board_name(mDevice);
                if(boardName != null)
                {
                    mBoardName = boardName;
                    mBiasTSupported = boardName.startsWith("bladerf2");
                }
            }
        }
        catch(UnsatisfiedLinkError | NoClassDefFoundError e)
        {
            mLog.warn("Unable to query bladeRF board name", e);
        }

        updateSerial();
        loadChannelCount();
        queryDeviceCapabilities();
        applyConfiguredSettings();
    }

    private void applyConfiguredSettings() throws SourceException
    {
        setChannelInternal(mConfiguredChannelId);
        setSampleRate(mConfiguredSampleRate);
        setBandwidth(mConfiguredBandwidth);
        setGainMode(mConfiguredGainModeName, mConfiguredOverallGain);
        setTunedFrequency(mFrequency);
        setBiasTInternal(mConfiguredBiasTEnabled);
    }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.BLADE_RF;
    }

    @Override
    public int getBufferSampleCount()
    {
        return BUFFER_SIZE_SAMPLES;
    }

    @Override
    public double getCurrentSampleRate()
    {
        return mActualSampleRate;
    }

    @Override
    public long getTunedFrequency()
    {
        return mFrequency;
    }

    @Override
    public void setTunedFrequency(long frequency) throws SourceException
    {
        mFrequency = frequency;

        if(mDevice != null)
        {
            checkStatus(mLibrary.bladerf_set_frequency(mDevice, getChannelConstant(), frequency), "set frequency");
        }
    }

    @Override
    public void apply(TunerConfiguration config) throws SourceException
    {
        super.apply(config);

        if(config instanceof BladeRFTunerConfiguration bladeConfig)
        {
            setChannelInternal(bladeConfig.getChannelId());
            setSampleRate(bladeConfig.getSampleRate());
            setBandwidth(bladeConfig.getBandwidth());
            setGainMode(bladeConfig.getGainModeName(), bladeConfig.getOverallGain());
            setBiasTEnabled(bladeConfig.isBiasTEnabled());
        }
        else
        {
            throw new IllegalArgumentException("Invalid tuner configuration type [" + config.getClass() + "]");
        }
    }

    private void updateSerial()
    {
        if(mUsbDevice != null)
        {
            String serial = mUsbDevice.getSerialNumber();
            if(serial != null && !serial.isEmpty())
            {
                mSerial = serial;
                return;
            }
        }

        if(mDevice != null)
        {
            byte[] serial = new byte[BladeRFLibrary.BLADERF_SERIAL_LENGTH];
            int status = mLibrary.bladerf_get_serial(mDevice, serial);

            if(status == 0)
            {
                mSerial = new String(serial).trim();
            }
            else
            {
                mLog.warn("Unable to query bladeRF serial: {}", errorMessage("get serial", status));
            }
        }
    }

    private void initializeUsbMetadata()
    {
        Device device = getDevice();
        DeviceHandle handle = getDeviceHandle();

        if(device == null || handle == null)
        {
            return;
        }

        try
        {
            mUsbDevice = new BladeRFUsbDevice(device, handle);
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to read bladeRF USB metadata", se);
            mUsbDevice = null;
        }

        BladeRFUsbProtocol protocol = new BladeRFUsbProtocol(handle);
        waitForFirmwareReady(protocol);

        if(mUsbDevice != null)
        {
            String boardName = mUsbDevice.getBoardName();
            if(boardName != null && !boardName.isEmpty())
            {
                mBoardName = boardName;
                mBiasTSupported = boardName.startsWith("bladerf2");
            }

            String serial = mUsbDevice.getSerialNumber();
            if(serial != null && !serial.isEmpty())
            {
                mSerial = serial;
            }

            BladeRFVersion firmwareVersion = mUsbDevice.getFirmwareVersion();
            if(firmwareVersion != null)
            {
                mFirmwareVersion = firmwareVersion;
                mLog.debug("bladeRF firmware version {}", firmwareVersion);
            }
        }

        try
        {
            BladeRFNiosAccess niosAccess = new BladeRFNiosAccess(protocol);
            mFpgaVersion = niosAccess.readFpgaVersion();
            mLog.debug("bladeRF fpga version {}", mFpgaVersion);
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to read bladeRF FPGA version", se);
        }

        mUsbCapabilities = BladeRFCapabilities.combined(mFirmwareVersion, mFpgaVersion);
    }

    private void waitForFirmwareReady(BladeRFUsbProtocol protocol)
    {
        try
        {
            int ready = protocol.readVendorInt(BladeRFUsbConstants.CMD_QUERY_DEVICE_READY, (short)0, (short)0);
            if(ready == 0)
            {
                mLog.debug("bladeRF firmware not ready when queried");
            }
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to query bladeRF firmware readiness", se);
        }
    }

    public String getSerialNumber()
    {
        return mSerial;
    }

    public String getBoardName()
    {
        return mBoardName;
    }

    public BladeRFVersion getFirmwareVersion()
    {
        return mFirmwareVersion;
    }

    public BladeRFVersion getFpgaVersion()
    {
        return mFpgaVersion;
    }

    public long getUsbCapabilities()
    {
        return mUsbCapabilities;
    }

    /**
     * Configured sample rate in Hertz.
     */
    public int getConfiguredSampleRate()
    {
        return mConfiguredSampleRate;
    }

    public int getChannelCount()
    {
        return mChannelCount;
    }

    public int getConfiguredChannelId()
    {
        return mConfiguredChannelId;
    }

    /**
     * Available sample rates derived from the device capabilities.
     */
    public List<Integer> getSupportedSampleRates()
    {
        return Collections.unmodifiableList(mSupportedSampleRates);
    }

    /**
     * Available bandwidth values derived from the device capabilities (excludes Auto entry).
     */
    public List<Long> getSupportedBandwidths()
    {
        return Collections.unmodifiableList(mSupportedBandwidths);
    }

    /**
     * Gain modes reported by libbladeRF.
     */
    public List<GainModeInfo> getSupportedGainModes()
    {
        return Collections.unmodifiableList(mSupportedGainModes);
    }

    public int getMinimumGain()
    {
        return mGainMinimum;
    }

    public int getMaximumGain()
    {
        return mGainMaximum;
    }

    public int getConfiguredOverallGain()
    {
        return mConfiguredOverallGain;
    }

    public boolean isBiasTSupported()
    {
        return mBiasTSupported;
    }

    public boolean isBiasTEnabled()
    {
        return mConfiguredBiasTEnabled;
    }

    public void setChannel(int channelId) throws SourceException
    {
        int sanitized = clampChannel(channelId);

        if(sanitized == mConfiguredChannelId)
        {
            return;
        }

        boolean wasStreaming = false;
        boolean hadListeners = false;

        if(mDevice != null)
        {
            wasStreaming = isStreamingActive();
            hadListeners = hasBufferListeners();

            if(wasStreaming)
            {
                stopStreaming();
            }
        }

        mConfiguredChannelId = sanitized;

        if(mDevice != null)
        {
            applyConfiguredSettings();

            if(wasStreaming && hadListeners)
            {
                startStreaming();
            }
        }
    }

    public String getConfiguredGainModeName()
    {
        return mConfiguredGainModeName;
    }

    public long getConfiguredBandwidth()
    {
        return mConfiguredBandwidth;
    }

    public boolean isManualGainModeSelected()
    {
        GainModeInfo selected = findGainMode(mConfiguredGainModeName);
        return selected != null && selected.isManual();
    }

    public void setSampleRate(int requestedSampleRate) throws SourceException
    {
        if(requestedSampleRate <= 0)
        {
            requestedSampleRate = DEFAULT_SAMPLE_RATE;
        }

        mConfiguredSampleRate = requestedSampleRate;
        int rateToApply = selectNearestSampleRate(requestedSampleRate);

        if(mDevice != null)
        {
            IntByReference actual = new IntByReference(rateToApply);
            checkStatus(mLibrary.bladerf_set_sample_rate(mDevice, getChannelConstant(), rateToApply, actual), "set sample rate");
            mActualSampleRate = actual.getValue();

            try
            {
                mFrequencyController.setSampleRate(mActualSampleRate);
            }
            catch(SourceException se)
            {
                throw new SourceException("Unable to update frequency controller sample rate", se);
            }

            mNativeBufferFactory.setSamplesPerMillisecond(mActualSampleRate / 1000.0f);
        }
        else
        {
            mActualSampleRate = rateToApply;
            try
            {
                mFrequencyController.setSampleRate(mActualSampleRate);
            }
            catch(SourceException se)
            {
                throw new SourceException("Unable to update frequency controller sample rate", se);
            }
        }
    }

    public void setBandwidth(long bandwidth) throws SourceException
    {
        mConfiguredBandwidth = bandwidth;

        if(mDevice != null)
        {
            long toApply = (bandwidth == BANDWIDTH_AUTO) ? clampBandwidth(mActualSampleRate) : bandwidth;
            IntByReference actual = new IntByReference((int)toApply);
            checkStatus(mLibrary.bladerf_set_bandwidth(mDevice, getChannelConstant(), (int)toApply, actual), "set bandwidth");
        }
    }

    public void setGainMode(String gainModeName, int overallGain) throws SourceException
    {
        if(gainModeName == null || gainModeName.isEmpty())
        {
            gainModeName = BladeRFTunerConfiguration.DEFAULT_GAIN_MODE;
        }

        mConfiguredGainModeName = gainModeName;
        mConfiguredOverallGain = overallGain;

        if(mDevice != null)
        {
            GainModeInfo modeInfo = findGainMode(gainModeName);

            if(modeInfo == null && !mSupportedGainModes.isEmpty())
            {
                modeInfo = mSupportedGainModes.get(0);
            }

            if(modeInfo != null)
            {
                checkStatus(mLibrary.bladerf_set_gain_mode(mDevice, getChannelConstant(), modeInfo.getMode()), "set gain mode");
                mActiveGainMode = modeInfo;

                if(modeInfo.isManual())
                {
                    setOverallGain(overallGain);
                }
            }
        }
        else
        {
            mActiveGainMode = findGainMode(gainModeName);
        }
    }

    public void setOverallGain(int gain) throws SourceException
    {
        int clamped = clampGain(gain);
        mConfiguredOverallGain = clamped;

        if(mDevice != null && mActiveGainMode != null && mActiveGainMode.isManual())
        {
            checkStatus(mLibrary.bladerf_set_gain(mDevice, getChannelConstant(), clamped), "set gain");
        }
    }

    public void refreshChannelConfiguration() throws SourceException
    {
        setChannelInternal(mConfiguredChannelId);
        applyConfiguredSettings();
    }

    public void setBiasTEnabled(boolean enabled) throws SourceException
    {
        mConfiguredBiasTEnabled = enabled;
        setBiasTInternal(enabled);
    }

    private void setBiasTInternal(boolean enabled) throws SourceException
    {
        if(mDevice != null && mBiasTSupported)
        {
            checkStatus(mLibrary.bladerf_set_bias_tee(mDevice, getChannelConstant(), enabled), "set bias-tee");
        }
    }

    private int clampGain(int gain)
    {
        return Math.max(mGainMinimum, Math.min(mGainMaximum, gain));
    }

    private void queryDeviceCapabilities() throws SourceException
    {
        loadSampleRates();
        loadBandwidths();
        loadGainInformation();
    }

    private void loadChannelCount()
    {
        mChannelCount = 1;

        if(mDevice != null)
        {
            int count = mLibrary.bladerf_get_channel_count(mDevice, BladeRFLibrary.BLADERF_RX);
            if(count > 0)
            {
                mChannelCount = count;
            }
        }

        mConfiguredChannelId = clampChannel(mConfiguredChannelId);
    }

    private void loadSampleRates() throws SourceException
    {
        mSupportedSampleRates.clear();

        if(mDevice == null)
        {
            mSupportedSampleRates.add(DEFAULT_SAMPLE_RATE);
            return;
        }

        PointerByReference reference = new PointerByReference();
        checkStatus(mLibrary.bladerf_get_sample_rate_range(mDevice, getChannelConstant(), reference), "get sample rate range");
        Pointer pointer = reference.getValue();

        if(pointer == null)
        {
            mSupportedSampleRates.add(DEFAULT_SAMPLE_RATE);
            return;
        }

        try
        {
            BladeRFLibrary.Range range = new BladeRFLibrary.Range(pointer);

            long min = Math.max(range.min, 1);
            long max = Math.max(range.max, min);

            for(int rate: RECOMMENDED_SAMPLE_RATES)
            {
                if(rate >= min && rate <= max)
                {
                    mSupportedSampleRates.add(rate);
                }
            }

            if(mSupportedSampleRates.isEmpty())
            {
                mSupportedSampleRates.add((int)Math.max(Math.min(DEFAULT_SAMPLE_RATE, max), min));
            }
            else
            {
                if(mSupportedSampleRates.get(0) != min)
                {
                    mSupportedSampleRates.add(0, (int)min);
                }

                int last = mSupportedSampleRates.get(mSupportedSampleRates.size() - 1);
                if(last != max)
                {
                    mSupportedSampleRates.add((int)max);
                }
            }
        }
        finally
        {
            freeRange(pointer);
        }
    }

    private void loadBandwidths() throws SourceException
    {
        mSupportedBandwidths.clear();
        List<Long> calculated = new ArrayList<>();

        if(mDevice == null)
        {
            calculated.add((long)DEFAULT_SAMPLE_RATE);
            mBandwidthMin = DEFAULT_SAMPLE_RATE;
            mBandwidthMax = DEFAULT_SAMPLE_RATE;
        }
        else
        {
            PointerByReference reference = new PointerByReference();
            checkStatus(mLibrary.bladerf_get_bandwidth_range(mDevice, getChannelConstant(), reference), "get bandwidth range");
            Pointer pointer = reference.getValue();

            if(pointer == null)
            {
                calculated.add((long)DEFAULT_SAMPLE_RATE);
                mBandwidthMin = DEFAULT_SAMPLE_RATE;
                mBandwidthMax = DEFAULT_SAMPLE_RATE;
            }
            else
            {
                try
                {
                    BladeRFLibrary.Range range = new BladeRFLibrary.Range(pointer);

                    long min = Math.max(range.min, 1);
                    long max = Math.max(range.max, min);
                    mBandwidthMin = min;
                    mBandwidthMax = max;

                    for(long bandwidth: RECOMMENDED_BANDWIDTHS)
                    {
                        if(bandwidth >= min && bandwidth <= max)
                        {
                            calculated.add(bandwidth);
                        }
                    }

                    if(calculated.isEmpty())
                    {
                        calculated.add(Math.max(Math.min(DEFAULT_SAMPLE_RATE, max), min));
                    }
                    else
                    {
                        if(calculated.get(0) != min)
                        {
                            calculated.add(0, min);
                        }

                        long last = calculated.get(calculated.size() - 1);
                        if(last != max)
                        {
                            calculated.add(max);
                        }
                    }
                }
                finally
                {
                    freeRange(pointer);
                }
            }
        }

        mSupportedBandwidths.add(BANDWIDTH_AUTO);

        for(Long bandwidth: calculated)
        {
            if(!mSupportedBandwidths.contains(bandwidth))
            {
                mSupportedBandwidths.add(bandwidth);
            }
        }

        if(mSupportedBandwidths.size() == 1)
        {
            mSupportedBandwidths.add((long)DEFAULT_SAMPLE_RATE);
            mBandwidthMin = DEFAULT_SAMPLE_RATE;
            mBandwidthMax = DEFAULT_SAMPLE_RATE;
        }
    }

    private void loadGainInformation() throws SourceException
    {
        mSupportedGainModes.clear();

        if(mDevice == null)
        {
            mSupportedGainModes.add(new GainModeInfo("Manual", BladeRFLibrary.BLADERF_GAIN_MGC));
            mGainMinimum = 0;
            mGainMaximum = 60;
            return;
        }

        Pointer rangePointer = null;
        try
        {
            PointerByReference rangeReference = new PointerByReference();
            checkStatus(mLibrary.bladerf_get_gain_range(mDevice, getChannelConstant(), rangeReference), "get gain range");
            rangePointer = rangeReference.getValue();

            if(rangePointer == null)
            {
                mGainMinimum = 0;
                mGainMaximum = 60;
            }
            else
            {
                BladeRFLibrary.Range range = new BladeRFLibrary.Range(rangePointer);
                mGainMinimum = (int)range.min;
                mGainMaximum = (int)range.max;
            }
        }
        finally
        {
            freeRange(rangePointer);
        }

        Pointer modesPointer = null;
        try
        {
            PointerByReference modesReference = new PointerByReference();
            int count = mLibrary.bladerf_get_gain_modes(mDevice, getChannelConstant(), modesReference);

            if(count < 0)
            {
                throw new SourceException(errorMessage("get gain modes", count));
            }

            modesPointer = modesReference.getValue();

            if(modesPointer != null && count > 0)
            {
                BladeRFLibrary.GainMode first = new BladeRFLibrary.GainMode(modesPointer);
                BladeRFLibrary.GainMode[] modes = (BladeRFLibrary.GainMode[])first.toArray(count);

                for(BladeRFLibrary.GainMode mode: modes)
                {
                    mSupportedGainModes.add(new GainModeInfo(mode.getName(), mode.mode));
                }
            }
        }
        finally
        {
            freeGainModes(modesPointer);
        }

        if(mSupportedGainModes.isEmpty())
        {
            mSupportedGainModes.add(new GainModeInfo("Manual", BladeRFLibrary.BLADERF_GAIN_MGC));
        }
    }

    private int selectNearestSampleRate(int requestedSampleRate)
    {
        if(mSupportedSampleRates.isEmpty())
        {
            return requestedSampleRate;
        }

        int closest = mSupportedSampleRates.get(0);
        int delta = Math.abs(requestedSampleRate - closest);

        for(int rate: mSupportedSampleRates)
        {
            int currentDelta = Math.abs(requestedSampleRate - rate);
            if(currentDelta < delta)
            {
                closest = rate;
                delta = currentDelta;
            }
        }

        return closest;
    }

    private long clampBandwidth(long preference)
    {
        long bandwidth = Math.max(mBandwidthMin, Math.min(mBandwidthMax, preference));
        return bandwidth;
    }

    private GainModeInfo findGainMode(String name)
    {
        if(name == null)
        {
            return null;
        }

        for(GainModeInfo info: mSupportedGainModes)
        {
            if(info.getName().equalsIgnoreCase(name))
            {
                return info;
            }
        }

        return null;
    }

    private void freeRange(Pointer pointer)
    {
        if(pointer == null || !mFreeRangeSupported)
        {
            return;
        }

        try
        {
            mLibrary.bladerf_free_range(pointer);
        }
        catch(UnsatisfiedLinkError | NoSuchMethodError e)
        {
            mFreeRangeSupported = false;
            mLog.debug("libbladeRF does not provide bladerf_free_range; skipping future free attempts");
        }
        catch(Throwable t)
        {
            mFreeRangeSupported = false;
            mLog.debug("Unable to free bladeRF range pointer", t);
        }
    }

    private void freeGainModes(Pointer pointer)
    {
        if(pointer == null || !mFreeGainModesSupported)
        {
            return;
        }

        try
        {
            mLibrary.bladerf_free_gain_modes(pointer);
        }
        catch(UnsatisfiedLinkError | NoSuchMethodError e)
        {
            mFreeGainModesSupported = false;
            mLog.debug("libbladeRF does not provide bladerf_free_gain_modes; skipping future free attempts");
        }
        catch(Throwable t)
        {
            mFreeGainModesSupported = false;
            mLog.debug("Unable to free bladeRF gain modes pointer", t);
        }
    }

    private void checkStatus(int status, String action) throws SourceException
    {
        if(status != 0)
        {
            throw new SourceException(errorMessage(action, status));
        }
    }

    private String errorMessage(String action, int status)
    {
        return "bladeRF - unable to " + action + ": " + mLibrary.bladerf_strerror(status);
    }

    private int getChannelConstant()
    {
        return (getActiveChannelId() << 1);
    }

    private int clampChannel(int channelId)
    {
        return Math.max(0, channelId);
    }

    private int getActiveChannelId()
    {
        if(mChannelCount <= 0)
        {
            return 0;
        }

        return Math.min(mConfiguredChannelId, mChannelCount - 1);
    }

    private void setChannelInternal(int channelId)
    {
        mConfiguredChannelId = clampChannel(channelId);
    }

    /**
     * Gain mode descriptor used by the editor and controller.
     */
    public static class GainModeInfo
    {
        private final String mName;
        private final int mMode;

        public GainModeInfo(String name, int mode)
        {
            mName = (name == null || name.isEmpty()) ? "Manual" : capitalize(name);
            mMode = mode;
        }

        public String getName()
        {
            return mName;
        }

        public int getMode()
        {
            return mMode;
        }

        public boolean isManual()
        {
            return mMode == BladeRFLibrary.BLADERF_GAIN_MGC;
        }

        private String capitalize(String name)
        {
            if(name.length() < 2)
            {
                return name.toUpperCase();
            }

            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        @Override
        public String toString()
        {
            return getName();
        }
    }

}
