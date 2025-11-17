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

import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.bladerf.rfic.BladeRF2RficController;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2ClockManager;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2Constants;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2Device;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2GainMode;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2Ranges;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2SmbMode;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFNiosAccess;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFCapabilities;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFFlashId;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFRange;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFSi5338;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbConstants;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbDevice;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFUsbProtocol;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFVersion;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFVctcxoTrim;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRationalRate;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Device;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;

/**
 * bladeRF 2 tuner controller that directly drives the native USB control plane.
 */
public class BladeRFTunerController extends USBTunerController
{
    private static final Logger mLog = LoggerFactory.getLogger(BladeRFTunerController.class);

    public static final long MINIMUM_TUNABLE_FREQUENCY_HZ = 20_000_000L;
    public static final long MAXIMUM_TUNABLE_FREQUENCY_HZ = 6_000_000_000L;
    public static final int DEFAULT_SAMPLE_RATE = 2_000_000;
    public static final long BANDWIDTH_AUTO = -1L;
    private static final int DC_HALF_BANDWIDTH = 5_000;
    private static final double USABLE_BANDWIDTH_PERCENT = 0.90;
    private static final int BUFFER_SIZE_SAMPLES = 4_096;
    private static final int BYTES_PER_SAMPLE = 4; //16-bit I + 16-bit Q
    private static final int FX3_STREAM_STATUS_BUSY = 0x40;
    private static final int FX3_STREAM_STATUS_ACCEPTED = 0x44;
    private static final int STREAM_TOGGLE_RETRIES = 3;
    private static final long STREAM_TOGGLE_RETRY_DELAY_MS = 10L;
    private static final boolean SAMPLE_CLOCK_AUTO_ENABLED =
            Boolean.parseBoolean(System.getProperty("sdrtrunk.bladerf.enableSampleClock", "false"));
    private static final int[] RECOMMENDED_SAMPLE_RATES = BladeRF2Ranges.getRecommendedSampleRates();
    private static final long[] RECOMMENDED_BANDWIDTHS = BladeRF2Ranges.getRecommendedBandwidths();
    private final BladeRFNativeBufferFactory mNativeBufferFactory = new BladeRFNativeBufferFactory();
    private BladeRF2Device mBladeRF2Device;
    private BladeRFVersion mFirmwareVersion;
    private BladeRFVersion mFpgaVersion;
    private long mUsbCapabilities;
    private final List<Integer> mSupportedSampleRates = new ArrayList<>();
    private final List<Long> mSupportedBandwidths = new ArrayList<>();
    private final List<GainModeInfo> mSupportedGainModes = new ArrayList<>();

    private BladeRFUsbProtocol mUsbProtocol;
    private BladeRFNiosAccess mNiosAccess;
    private BladeRF2RficController mRficController;
    private BladeRF2QuickTuneManager mQuickTuneManager;
    private BladeRF2ClockManager mClockManager;
    private BladeRFVctcxoTrim mVctcxoTrim;
    private volatile boolean mSampleClockUnavailable;
    private volatile boolean mSampleClockInitialized;
    private Thread mSampleClockWorker;
    private volatile boolean mSampleClockCancelRequested;
    private final Object mSampleClockWorkerLock = new Object();
    private volatile boolean mSampleClockUpdatePending;
    private volatile boolean mPendingSampleRate;
    private volatile int mPendingSampleRateValue = DEFAULT_SAMPLE_RATE;
    private volatile boolean mPendingBandwidth;
    private volatile long mPendingBandwidthValue = BANDWIDTH_AUTO;
    private volatile boolean mApplyingDeferredConfiguration;
    private volatile boolean mApplyFullConfigurationAfterUnlock;
    private volatile boolean mForceConfigurationUpdates;
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
    protected void deviceStart() throws SourceException
    {
        initializeUsbMetadata();

        if(!isBladeRF2() || mBladeRF2Device == null || mUsbProtocol == null || mNiosAccess == null
                || !isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - this branch only supports bladeRF 2 devices");
        }

        initializeDevice();

        mQuickTuneManager = new BladeRF2QuickTuneManager(mRficController, mNiosAccess);
        try
        {
            mRficController.ensureInitialized();
        }
        catch(SourceException se)
        {
            logRficStatusOnFailure();
            throw new SourceException("bladeRF - unable to initialize RFIC", se);
        }
        applyVctcxoTrim();
        ensureReferenceClockConfigured();
        if(!SAMPLE_CLOCK_AUTO_ENABLED)
        {
            mLog.info("bladeRF sample clock reprogramming disabled; launch with -Dsdrtrunk.bladerf.enableSampleClock=true to opt in");
        }
        try
        {
            resetQuickTuneQueues();
        }
        catch(SourceException se)
        {
            throw new SourceException("bladeRF - unable to clear quick tune queues", se);
        }
        applyConfiguredSettings();
        waitForSampleClockWorkerCompletion();
    }

    @Override
    protected void deviceStop()
    {
        streamingCleanup();
        try
        {
            resetQuickTuneQueues();
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to clear bladeRF2 quick tune queues during shutdown", se);
        }
        // Ensure downstream managers know the sample rate can change again.
        setLockedSampleRate(false);
        mRficController = null;
        mQuickTuneManager = null;
        mClockManager = null;
        mSampleClockUnavailable = !SAMPLE_CLOCK_AUTO_ENABLED;
        mSampleClockInitialized = false;
        mPendingSampleRate = false;
        mPendingSampleRateValue = DEFAULT_SAMPLE_RATE;
        mPendingBandwidth = false;
        mPendingBandwidthValue = BANDWIDTH_AUTO;
        mApplyingDeferredConfiguration = false;
        mSampleClockUpdatePending = false;
        if(SAMPLE_CLOCK_AUTO_ENABLED)
        {
            cancelSampleClockWorker(false);
        }
        mVctcxoTrim = null;
        mNiosAccess = null;
        mUsbProtocol = null;
        mBladeRF2Device = null;
        mFirmwareVersion = null;
        mFpgaVersion = null;
        mUsbCapabilities = 0;
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
        waitForSampleClockWorkerCompletion();
        if(isBladeRF2ControllerAvailable() && mUsbProtocol != null && mNiosAccess != null)
        {
            try
            {
                if(SAMPLE_CLOCK_AUTO_ENABLED)
                {
                    cancelSampleClockWorker(false);
                }
                configureBladeRF2Streaming();
                setRfEnabled(true);
                setBladeRF2StreamingEnabled(true);
            }
            catch(SourceException se)
            {
                mLog.error("Unable to start bladeRF2 streaming", se);
                setErrorMessage(se.getMessage());
            }
        }
        else
        {
            setErrorMessage("bladeRF - bladeRF2 control plane unavailable");
        }
    }

    @Override
    protected void streamingCleanup()
    {
        if(isBladeRF2ControllerAvailable() && mUsbProtocol != null)
        {
            try
            {
                setBladeRF2StreamingEnabled(false);
                setRfEnabled(false);
            }
            catch(SourceException se)
            {
                mLog.debug("Unable to disable bladeRF2 streaming", se);
            }
        }

        schedulePendingSampleClockInitialization();
    }

    private void initializeDevice() throws SourceException
    {
        if(mBladeRF2Device != null)
        {
            mBoardName = mBladeRF2Device.getBoardName();
            mBiasTSupported = mBoardName.startsWith("bladerf2");
        }

        updateSerial();
        loadChannelCount();
        queryDeviceCapabilities();
    }

    private void applyConfiguredSettings() throws SourceException
    {
        applyConfiguredSettingsInternal(false);
    }

    private void applyConfiguredSettingsInternal(boolean retryingWithoutSampleClock) throws SourceException
    {
        boolean previousForce = mForceConfigurationUpdates;
        mForceConfigurationUpdates = true;
        try
        {
            setChannelInternal(mConfiguredChannelId);
            setSampleRate(mConfiguredSampleRate);
            setBandwidth(mConfiguredBandwidth);
            setGainMode(mConfiguredGainModeName, mConfiguredOverallGain);
            setTunedFrequency(mFrequency);
            setBiasTInternal(mConfiguredBiasTEnabled);
            if(isBladeRF2ControllerAvailable())
            {
                mRficController.writeManualGain(getChannelConstant(), mConfiguredOverallGain, mFrequency);
            }
        }
        catch(SourceException se)
        {
            if(shouldRetryWithoutSampleClock(retryingWithoutSampleClock))
            {
                mLog.warn("bladeRF sample clock configuration failed; disabling Si5338 access and retrying", se);
                cancelSampleClockWorker(true);
                applyConfiguredSettingsInternal(true);
                return;
            }
            throw se;
        }
        finally
        {
            mForceConfigurationUpdates = previousForce;
        }
    }

    private boolean shouldRetryWithoutSampleClock(boolean retryingWithoutSampleClock)
    {
        return !retryingWithoutSampleClock
                && SAMPLE_CLOCK_AUTO_ENABLED
                && isBladeRF2ControllerAvailable()
                && !mSampleClockUnavailable;
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
        long previousFrequency = mFrequency;
        mFrequency = frequency;

        if(isBladeRF2ControllerAvailable())
        {
            try
            {
                mRficController.writeFrequency(getChannelConstant(), frequency);
            }
            catch(SourceException se)
            {
                mFrequency = previousFrequency;
                throw se;
            }
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
        if(mBladeRF2Device != null)
        {
            String serial = mBladeRF2Device.getSerialNumber();
            if(serial != null && !serial.isEmpty())
            {
                mSerial = serial;
            }
        }
    }

    private void initializeUsbMetadata() throws SourceException
    {
        Device device = getDevice();
        DeviceHandle handle = getDeviceHandle();

        if(device == null || handle == null)
        {
            return;
        }

        mFirmwareVersion = null;
        mBiasTSupported = false;
        BladeRFUsbDevice usbMetadata = null;
        try
        {
            usbMetadata = new BladeRFUsbDevice(device, handle);
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to read bladeRF USB metadata", se);
        }

        mBladeRF2Device = null;
        mUsbProtocol = null;
        mNiosAccess = null;
        mRficController = null;
        mClockManager = null;
        mVctcxoTrim = null;
        mFpgaVersion = null;

        if(usbMetadata != null)
        {
            String boardName = usbMetadata.getBoardName();
            if(boardName != null && !boardName.isEmpty())
            {
                mBoardName = boardName;
                mBiasTSupported = boardName.startsWith("bladerf2");
            }

            String serial = usbMetadata.getSerialNumber();
            if(serial != null && !serial.isEmpty())
            {
                mSerial = serial;
            }

            BladeRFVersion firmwareVersion = usbMetadata.getFirmwareVersion();
            if(firmwareVersion != null)
            {
                mFirmwareVersion = firmwareVersion;
                mLog.debug("bladeRF firmware version {}", firmwareVersion);
            }

            if(mBiasTSupported)
            {
                try
                {
                    mBladeRF2Device = new BladeRF2Device(usbMetadata, handle);
                    mUsbProtocol = mBladeRF2Device.getUsbProtocol();
                    mNiosAccess = mBladeRF2Device.getNiosAccess();
                    mRficController = mBladeRF2Device.getRficController();
                    BladeRFSi5338 si5338 = mBladeRF2Device.getSi5338();
                    if(si5338 != null)
                    {
                        mClockManager = new BladeRF2ClockManager(mNiosAccess, si5338);
                        mSampleClockUnavailable = !SAMPLE_CLOCK_AUTO_ENABLED;
                    }
                    else
                    {
                        mClockManager = null;
                        mSampleClockUnavailable = true;
                        mLog.debug("bladeRF Si5338 sample clock control unavailable; disabling automatic sample clock programming");
                    }
                    mSampleClockInitialized = false;
                    mSampleClockCancelRequested = false;
                    mVctcxoTrim = mBladeRF2Device.getVctcxoTrim();
                    mFpgaVersion = mBladeRF2Device.getFpgaVersion();
                    mUsbCapabilities = mBladeRF2Device.getCapabilities();
                }
                catch(SourceException se)
                {
                    mLog.warn("Unable to initialize bladeRF2 control plane", se);
                    mBladeRF2Device = null;
                    mUsbProtocol = null;
                    mNiosAccess = null;
                    mRficController = null;
                    mClockManager = null;
                    mSampleClockUnavailable = !SAMPLE_CLOCK_AUTO_ENABLED;
                    mSampleClockInitialized = false;
                    mSampleClockCancelRequested = false;
                    mFpgaVersion = null;
                    mUsbCapabilities = BladeRFCapabilities.combined(mFirmwareVersion, null);
                    throw new SourceException("bladeRF - unable to initialize bladeRF2 control plane", se);
                }

                return;
            }
        }

        mSampleClockUnavailable = !SAMPLE_CLOCK_AUTO_ENABLED;
        mSampleClockInitialized = false;
        mSampleClockCancelRequested = false;
        mUsbCapabilities = BladeRFCapabilities.combined(mFirmwareVersion, mFpgaVersion);
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
     * Gain modes available for the active device.
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

    /**
     * Indicates if the board and FPGA bitstream support quick retune scheduling.
     */
    public boolean isQuickRetuneSupported()
    {
        return isQuickTuneManagerAvailable()
                && (mUsbCapabilities & BladeRFCapabilities.CAP_SCHEDULED_RETUNE) != 0;
    }

    public BladeRF2QuickTuneProfile captureQuickTuneProfile() throws SourceException
    {
        return captureQuickTuneProfile(mFrequency);
    }

    public BladeRF2QuickTuneProfile captureQuickTuneProfile(long frequency) throws SourceException
    {
        if(!isQuickTuneManagerAvailable())
        {
            throw new SourceException("bladeRF - quick retune is not available on this device");
        }

        return mQuickTuneManager.captureProfile(getChannelConstant(), frequency);
    }

    public BladeRFNiosAccess.Retune2Response scheduleQuickRetune(long timestamp, BladeRF2QuickTuneProfile profile)
            throws SourceException
    {
        if(!isQuickRetuneSupported())
        {
            throw new SourceException("bladeRF - scheduled retunes are not supported by this FPGA image");
        }

        return mQuickTuneManager.scheduleRetune(getChannelConstant(), timestamp, profile);
    }

    public BladeRFNiosAccess.Retune2Response retuneImmediately(BladeRF2QuickTuneProfile profile) throws SourceException
    {
        if(!isQuickTuneManagerAvailable())
        {
            throw new SourceException("bladeRF - quick retune is not available on this device");
        }

        return mQuickTuneManager.retuneNow(getChannelConstant(), profile);
    }

    /**
     * Reads the SPI flash identifier bytes for bladeRF 2 devices.
     */
    public BladeRFFlashId getFlashId() throws SourceException
    {
        ensureBladeRF2Usb("query flash ID");
        return mUsbProtocol.queryFlashId();
    }

    /**
     * Retrieves the FX3 firmware calibration cache contents.
     */
    public byte[] readCalibrationCache() throws SourceException
    {
        ensureBladeRF2Usb("read calibration cache");
        requireStreamingInactive("read calibration cache");
        return mUsbProtocol.readCalibrationCache();
    }

    /**
     * Refreshes the FX3 firmware calibration cache by forcing a SPI flash read.
     */
    public void refreshCalibrationCache() throws SourceException
    {
        ensureBladeRF2Usb("refresh calibration cache");
        requireStreamingInactive("refresh calibration cache");
        mUsbProtocol.refreshCalibrationCache();
    }

    /**
     * Invalidates the FX3 firmware calibration cache.
     */
    public void invalidateCalibrationCache() throws SourceException
    {
        ensureBladeRF2Usb("invalidate calibration cache");
        requireStreamingInactive("invalidate calibration cache");
        mUsbProtocol.invalidateCalibrationCache();
    }

    /**
     * Retrieves the stored VCTCXO trim value from SPI flash.
     */
    public int getVctcxoTrim() throws SourceException
    {
        ensureVctcxoTrim("read VCTCXO trim");
        requireStreamingInactive("read VCTCXO trim");
        return mVctcxoTrim.getStoredTrim();
    }

    /**
     * Reads the raw AD56x1 DAC value currently programmed on the board.
     */
    public int readVctcxoTrimDac() throws SourceException
    {
        ensureVctcxoTrim("read VCTCXO trim DAC");
        return mVctcxoTrim.readDacValue();
    }

    /**
     * Writes a raw AD56x1 DAC value. Streaming must be stopped before invoking this.
     */
    public void writeVctcxoTrimDac(int value) throws SourceException
    {
        ensureVctcxoTrim("write VCTCXO trim DAC");
        requireStreamingInactive("write VCTCXO trim DAC");
        mVctcxoTrim.writeDacValue(value);
    }

    /**
     * Indicates if firmware loopback is currently enabled.
     */
    public boolean isLoopbackEnabled() throws SourceException
    {
        ensureBladeRF2Usb("query loopback state");
        return mUsbProtocol.isLoopbackEnabled();
    }

    /**
     * Enables or disables firmware loopback mode. Streaming must be stopped before toggling this state.
     */
    public void setLoopbackEnabled(boolean enabled) throws SourceException
    {
        ensureBladeRF2Usb("configure loopback");
        requireStreamingInactive("configure loopback");
        mUsbProtocol.setLoopbackEnabled(enabled);
    }

    /**
     * Reads the current SMB clock port mode.
     */
    public BladeRF2SmbMode getSmbClockMode() throws SourceException
    {
        ensureBladeRF2ClockManager("query SMB clock mode");
        return mClockManager.getSmbMode();
    }

    /**
     * Configures the SMB clock port mode.
     */
    public void setSmbClockMode(BladeRF2SmbMode mode) throws SourceException
    {
        ensureBladeRF2ClockManager("configure SMB clock mode");
        requireStreamingInactive("configure SMB clock mode");
        mClockManager.setSmbMode(mode);
    }

    /**
     * Retrieves the currently configured SMB clock output frequency.
     */
    public BladeRationalRate getSmbClockFrequency() throws SourceException
    {
        ensureBladeRF2ClockManager("query SMB clock frequency");
        return mClockManager.getSmbFrequency();
    }

    /**
     * Configures the SMB clock output frequency using an integer value.
     */
    public BladeRationalRate setSmbClockFrequency(long frequencyHz) throws SourceException
    {
        ensureBladeRF2ClockManager("configure SMB clock frequency");
        requireStreamingInactive("configure SMB clock frequency");
        return mClockManager.configureSmbFrequency(frequencyHz);
    }

    /**
     * Configures the SMB clock output frequency using a rational rate request.
     */
    public BladeRationalRate setSmbClockFrequency(BladeRationalRate rate) throws SourceException
    {
        ensureBladeRF2ClockManager("configure SMB clock frequency");
        requireStreamingInactive("configure SMB clock frequency");
        return mClockManager.configureSmbFrequency(rate);
    }

    public void clearQuickRetuneQueue() throws SourceException
    {
        if(!isQuickTuneManagerAvailable())
        {
            return;
        }

        mQuickTuneManager.clearQueue(getChannelConstant());
    }

    private void resetQuickTuneQueues() throws SourceException
    {
        if(!isQuickTuneManagerAvailable())
        {
            return;
        }

        int channelLimit = (mChannelCount > 0) ? mChannelCount : 1;
        for(int channelId = 0; channelId < channelLimit; channelId++)
        {
            mQuickTuneManager.clearQueue(getChannelConstant(channelId, false));
            mQuickTuneManager.clearQueue(getChannelConstant(channelId, true));
        }
    }

    /**
     * Retrieves the current RFIC status flags for bladeRF 2 devices.
     */
    public BladeRF2RficController.RficStatus getRficStatus() throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - RFIC status is available only for bladeRF2 devices");
        }

        return mRficController.readStatus();
    }

    /**
     * Returns the RFIC initialization state when using the bladeRF 2 control plane.
     */
    public BladeRF2RficController.InitializationState getRficInitializationState() throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - RFIC initialization state is available only for bladeRF2 devices");
        }

        return mRficController.getInitializationState();
    }

    /**
     * Requests a RFIC initialization state change for bladeRF 2 devices.
     */
    public void setRficInitializationState(BladeRF2RficController.InitializationState state) throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - RFIC initialization is available only for bladeRF2 devices");
        }

        mRficController.setInitializationState(state);
    }

    /**
     * Indicates if the bladeRF 2 RFIC reports it is initialized.
     */
    public boolean isRficInitialized() throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            return false;
        }

        return mRficController.isInitialized();
    }

    /**
     * Enables or disables the RF path. For legacy devices this maps to bladerf_enable_module.
     */
    public void setRfEnabled(boolean enable) throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - unable to " + (enable ? "enable" : "disable") + " RF path");
        }

        mRficController.setRfEnabled(getChannelConstant(), enable);
    }

    /**
     * Reads the RFIC RSSI measurement for the active channel.
     */
    public BladeRF2RficController.RssiMeasurement getRssi() throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - RSSI is available only for bladeRF2 devices");
        }

        return mRficController.readRssi(getChannelConstant());
    }

    /**
     * Indicates if the currently selected TX channel is muted.
     */
    public boolean isTxMuted() throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - TX mute is available only for bladeRF2 devices");
        }

        return mRficController.isTxMuted(getChannelConstant(true));
    }

    /**
     * Sets the mute state for the currently selected TX channel.
     */
    public void setTxMuted(boolean muted) throws SourceException
    {
        if(!isBladeRF2ControllerAvailable())
        {
            throw new SourceException("bladeRF - TX mute is available only for bladeRF2 devices");
        }

        mRficController.setTxMute(getChannelConstant(true), muted);
    }

    public void setChannel(int channelId) throws SourceException
    {
        int sanitized = clampChannel(channelId);

        if(sanitized == mConfiguredChannelId)
        {
            return;
        }

        boolean hardwareReady = isBladeRF2ControllerAvailable();
        boolean wasStreaming = false;
        boolean hadListeners = false;

        if(hardwareReady)
        {
            wasStreaming = isStreamingActive();
            hadListeners = hasBufferListeners();

            if(wasStreaming)
            {
                stopStreaming();
            }
        }

        mConfiguredChannelId = sanitized;

        if(hardwareReady)
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

        if(isLockedSampleRate() && isStreamingActive() && !mForceConfigurationUpdates)
        {
            markSampleRatePending(requestedSampleRate);
            return;
        }

        if(isBladeRF2ControllerAvailable())
        {
            mActualSampleRate = mRficController.configureSampleRate(getChannelConstant(), rateToApply);
            updateFrequencyControllerSampleRate();
            mNativeBufferFactory.setSamplesPerMillisecond(mActualSampleRate / 1000.0f);
            if(SAMPLE_CLOCK_AUTO_ENABLED && mSampleClockInitialized)
            {
                try
                {
                    updateBladeRF2SampleClock();
                }
                catch(SourceException se)
                {
                    mSampleClockUnavailable = true;
                    mSampleClockInitialized = false;
                    mSampleClockUpdatePending = false;
                    mLog.warn("Unable to reprogram bladeRF2 sample clock; continuing with existing Si5338 configuration", se);
                }
            }
            else if(SAMPLE_CLOCK_AUTO_ENABLED)
            {
                if(mSampleClockUnavailable)
                {
                    mSampleClockInitialized = false;
                    mSampleClockUpdatePending = false;
                }
                else
                {
                    mSampleClockInitialized = false;
                    requestSampleClockInitialization();
                }
            }
            clearSampleRatePending();
            return;
        }

        mActualSampleRate = rateToApply;

        updateFrequencyControllerSampleRate();
        mNativeBufferFactory.setSamplesPerMillisecond(mActualSampleRate / 1000.0f);
        clearSampleRatePending();
    }

    public void setBandwidth(long bandwidth) throws SourceException
    {
        mConfiguredBandwidth = bandwidth;

        if(isLockedSampleRate() && isStreamingActive() && !mForceConfigurationUpdates)
        {
            markBandwidthPending(bandwidth);
            return;
        }

        if(isBladeRF2ControllerAvailable())
        {
            long toApply = (bandwidth == BANDWIDTH_AUTO) ? clampBandwidth(mActualSampleRate) : bandwidth;
            mRficController.writeBandwidth(getChannelConstant(), (int)toApply);
            clearBandwidthPending();
        }
        else
        {
            clearBandwidthPending();
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

        GainModeInfo modeInfo = findGainMode(gainModeName);

        if(modeInfo == null && !mSupportedGainModes.isEmpty())
        {
            modeInfo = mSupportedGainModes.get(0);
        }

        if(modeInfo == null)
        {
            mActiveGainMode = null;
            return;
        }

        if(isBladeRF2ControllerAvailable())
        {
            mRficController.writeGainMode(getChannelConstant(), modeInfo.getMode());
            mActiveGainMode = modeInfo;

            if(modeInfo.isManual())
            {
                setOverallGain(overallGain);
            }
        }
        else
        {
            mActiveGainMode = modeInfo;
        }
    }

    public void setOverallGain(int gain) throws SourceException
    {
        int clamped = clampGain(gain);
        mConfiguredOverallGain = clamped;

        if(isBladeRF2ControllerAvailable() && mActiveGainMode != null && mActiveGainMode.isManual())
        {
            mRficController.writeManualGain(getChannelConstant(), clamped, mFrequency);
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

    @Override
    public void setLockedSampleRate(boolean locked)
    {
        boolean wasLocked = isLockedSampleRate();
        super.setLockedSampleRate(locked);

        if(wasLocked && !isLockedSampleRate() && hasPendingLockedConfiguration() && !mApplyingDeferredConfiguration)
        {
            applyDeferredConfigurationAfterUnlock();
        }
    }

    private void setBiasTInternal(boolean enabled) throws SourceException
    {
        if(isBladeRF2ControllerAvailable() && mBiasTSupported && mNiosAccess != null)
        {
            int current = mNiosAccess.readRffeControl();
            if(enabled)
            {
                current |= (1 << BladeRF2Constants.RFFE_CONTROL_RX_BIAS_EN);
            }
            else
            {
                current &= ~(1 << BladeRF2Constants.RFFE_CONTROL_RX_BIAS_EN);
            }
            mNiosAccess.writeRffeControl(current);
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
        mChannelCount = isBladeRF2() ? 2 : 1;

        mConfiguredChannelId = clampChannel(mConfiguredChannelId);
    }

    private void loadSampleRates() throws SourceException
    {
        mSupportedSampleRates.clear();

        if(isBladeRF2())
        {
            mSupportedSampleRates.addAll(BladeRF2Ranges.enumerateSampleRates(DEFAULT_SAMPLE_RATE, false));
            return;
        }

        mSupportedSampleRates.add(DEFAULT_SAMPLE_RATE);
    }

    private void loadBandwidths() throws SourceException
    {
        mSupportedBandwidths.clear();
        List<Long> calculated = new ArrayList<>();

        if(isBladeRF2())
        {
            calculated.addAll(BladeRF2Ranges.enumerateBandwidths(DEFAULT_SAMPLE_RATE));
            BladeRFRange range = BladeRF2Ranges.getBandwidthRange();
            mBandwidthMin = range.getMin();
            mBandwidthMax = range.getMax();
        }
        else
        {
            calculated.add((long)DEFAULT_SAMPLE_RATE);
            mBandwidthMin = DEFAULT_SAMPLE_RATE;
            mBandwidthMax = DEFAULT_SAMPLE_RATE;
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

        if(isBladeRF2())
        {
            populateBladeRF2GainInformation();
            return;
        }

        mSupportedGainModes.add(new GainModeInfo("Manual", BladeRFConstants.GAIN_MGC));
        mGainMinimum = 0;
        mGainMaximum = 60;
    }

    private void populateBladeRF2GainInformation()
    {
        for(BladeRF2GainMode mode: BladeRF2Ranges.getGainModes())
        {
            mSupportedGainModes.add(new GainModeInfo(mode.getName(), mode.getMode()));
        }
        mGainMinimum = BladeRF2Ranges.getGainMinimum();
        mGainMaximum = BladeRF2Ranges.getGainMaximum();
    }

    private void populateSampleRates(long min, long max)
    {
        long safeMin = Math.max(min, 1);
        long safeMax = Math.max(max, safeMin);

        for(int rate: RECOMMENDED_SAMPLE_RATES)
        {
            if(rate >= safeMin && rate <= safeMax)
            {
                mSupportedSampleRates.add(rate);
            }
        }

        if(mSupportedSampleRates.isEmpty())
        {
            mSupportedSampleRates.add((int)Math.max(Math.min(DEFAULT_SAMPLE_RATE, safeMax), safeMin));
        }
        else
        {
            if(mSupportedSampleRates.get(0) != safeMin)
            {
                mSupportedSampleRates.add(0, (int)safeMin);
            }

            int last = mSupportedSampleRates.get(mSupportedSampleRates.size() - 1);
            if(last != safeMax)
            {
                mSupportedSampleRates.add((int)safeMax);
            }
        }
    }

    private void populateBandwidths(List<Long> target, long min, long max)
    {
        long safeMin = Math.max(min, 1);
        long safeMax = Math.max(max, safeMin);

        for(long bandwidth: RECOMMENDED_BANDWIDTHS)
        {
            if(bandwidth >= safeMin && bandwidth <= safeMax && !target.contains(bandwidth))
            {
                target.add(bandwidth);
            }
        }

        if(target.isEmpty())
        {
            target.add(Math.max(Math.min(DEFAULT_SAMPLE_RATE, safeMax), safeMin));
        }
        else
        {
            if(!target.contains(safeMin))
            {
                target.add(0, safeMin);
            }

            long last = target.get(target.size() - 1);
            if(last != safeMax)
            {
                target.add(safeMax);
            }
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

    private boolean isBladeRF2()
    {
        return mBoardName != null && mBoardName.toLowerCase().contains("bladerf2");
    }

    private void ensureBladeRF2Usb(String action) throws SourceException
    {
        if(mUsbProtocol == null || !isBladeRF2())
        {
            throw new SourceException("bladeRF - unable to " + action + " without a bladeRF 2 USB session");
        }
    }

    private void ensureVctcxoTrim(String action) throws SourceException
    {
        if(mVctcxoTrim == null)
        {
            throw new SourceException("bladeRF - unable to " + action + " without VCTCXO trim support");
        }
    }

    private void markSampleRatePending(int requestedSampleRate)
    {
        if(!mPendingSampleRate)
        {
            mLog.info("bladeRF - deferring sample rate update until sample-rate lock is released");
        }
        mPendingSampleRate = true;
        mPendingSampleRateValue = requestedSampleRate;
        mApplyFullConfigurationAfterUnlock = true;
    }

    private void clearSampleRatePending()
    {
        mPendingSampleRate = false;
        mPendingSampleRateValue = mConfiguredSampleRate;
    }

    private void markBandwidthPending(long requestedBandwidth)
    {
        if(!mPendingBandwidth)
        {
            mLog.info("bladeRF - deferring bandwidth update until sample-rate lock is released");
        }
        mPendingBandwidth = true;
        mPendingBandwidthValue = requestedBandwidth;
        mApplyFullConfigurationAfterUnlock = true;
    }

    private void clearBandwidthPending()
    {
        mPendingBandwidth = false;
        mPendingBandwidthValue = mConfiguredBandwidth;
    }

    private boolean hasPendingLockedConfiguration()
    {
        return mPendingSampleRate || mPendingBandwidth;
    }

    private void applyDeferredConfigurationAfterUnlock()
    {
        if(!hasPendingLockedConfiguration() || isLockedSampleRate() || mApplyingDeferredConfiguration)
        {
            return;
        }

        boolean restartStreaming = false;
        mApplyingDeferredConfiguration = true;

        try
        {
            mLog.info("bladeRF - applying deferred configuration after sample-rate lock released");

            if(isStreamingActive())
            {
                restartStreaming = true;
                stopStreaming();
            }

            if(mPendingSampleRate)
            {
                try
                {
                    setSampleRate(mPendingSampleRateValue);
                }
                catch(SourceException se)
                {
                    mPendingSampleRate = true;
                    mLog.warn("bladeRF - unable to apply deferred sample rate update", se);
                }
            }
            else if(mApplyFullConfigurationAfterUnlock)
            {
                // Reapply the configured rate so the FIR decimation path is refreshed even when the value
                // didn't change while locked.
                try
                {
                    setSampleRate(mConfiguredSampleRate);
                }
                catch(SourceException se)
                {
                    mApplyFullConfigurationAfterUnlock = true;
                    mLog.warn("bladeRF - unable to reapply sample rate during full configuration reload", se);
                }
            }

            if(mPendingBandwidth && !isLockedSampleRate())
            {
                try
                {
                    setBandwidth(mPendingBandwidthValue);
                }
                catch(SourceException se)
                {
                    mPendingBandwidth = true;
                    mLog.warn("bladeRF - unable to apply deferred bandwidth update", se);
                }
            }

            if(mApplyFullConfigurationAfterUnlock && !hasPendingLockedConfiguration() && !isLockedSampleRate())
            {
                try
                {
                    applyConfiguredSettings();
                }
                catch(SourceException se)
                {
                    mApplyFullConfigurationAfterUnlock = true;
                    mLog.warn("bladeRF - unable to replay full configuration after lock release", se);
                }
            }
        }
        finally
        {
            mApplyingDeferredConfiguration = false;
            if(!hasPendingLockedConfiguration())
            {
                mApplyFullConfigurationAfterUnlock = false;
            }
            if(restartStreaming)
            {
                startStreaming();
            }
        }
    }

    private void ensureBladeRF2ClockManager(String action) throws SourceException
    {
        if(mClockManager == null)
        {
            throw new SourceException("bladeRF - unable to " + action + " without clock control support");
        }
    }

    private void requireStreamingInactive(String action) throws SourceException
    {
        if(isStreamingActive())
        {
            throw new SourceException("bladeRF - unable to " + action + " while streaming is active");
        }
    }

    private boolean isBladeRF2ControllerAvailable()
    {
        return isBladeRF2() && mRficController != null;
    }

    private boolean isQuickTuneManagerAvailable()
    {
        return isBladeRF2ControllerAvailable() && mQuickTuneManager != null;
    }

    private void updateFrequencyControllerSampleRate() throws SourceException
    {
        try
        {
            mFrequencyController.setSampleRate(mActualSampleRate);
        }
        catch(SourceException se)
        {
            throw new SourceException("Unable to update frequency controller sample rate", se);
        }
    }

    private void applyVctcxoTrim() throws SourceException
    {
        if(mVctcxoTrim == null)
        {
            return;
        }

        try
        {
            mVctcxoTrim.applyStoredTrim();
        }
        catch(SourceException se)
        {
            throw new SourceException("bladeRF - unable to apply VCTCXO trim", se);
        }
    }

    private void ensureReferenceClockConfigured() throws SourceException
    {
        if(mClockManager == null)
        {
            return;
        }

        try
        {
            mClockManager.initializeReferenceClock();
        }
        catch(SourceException se)
        {
            throw new SourceException("bladeRF - unable to configure PLL reference clock", se);
        }
    }

    private void updateBladeRF2SampleClock() throws SourceException
    {
        if(mClockManager == null || mSampleClockUnavailable || mActualSampleRate <= 0)
        {
            return;
        }

        mClockManager.configureSampleClock(mActualSampleRate);
    }

    private void requestSampleClockInitialization()
    {
        if(!SAMPLE_CLOCK_AUTO_ENABLED || mClockManager == null || mSampleClockUnavailable)
        {
            mSampleClockUpdatePending = false;
            return;
        }

        if(!scheduleSampleClockInitialization())
        {
            mSampleClockUpdatePending = true;
        }
        else
        {
            mSampleClockUpdatePending = false;
        }
    }

    private void schedulePendingSampleClockInitialization()
    {
        if(!mSampleClockUpdatePending)
        {
            return;
        }

        if(scheduleSampleClockInitialization())
        {
            mSampleClockUpdatePending = false;
        }
    }

    private boolean scheduleSampleClockInitialization()
    {
        if(!SAMPLE_CLOCK_AUTO_ENABLED || mClockManager == null || mSampleClockUnavailable || mSampleClockCancelRequested
                || isStreamingActive())
        {
            return false;
        }

        synchronized(mSampleClockWorkerLock)
        {
            if(mSampleClockWorker != null && mSampleClockWorker.isAlive())
            {
                return true;
            }

            mSampleClockWorker = new Thread(this::attemptSampleClockInitialization, "bladeRF2-sample-clock");
            mSampleClockWorker.setDaemon(true);
            mSampleClockWorker.start();
        }

        return true;
    }

    private void attemptSampleClockInitialization()
    {
        if(!SAMPLE_CLOCK_AUTO_ENABLED)
        {
            return;
        }

        final int maxAttempts = 3;
        try
        {
            for(int attempt = 1; attempt <= maxAttempts; attempt++)
            {
                if(mClockManager == null || mSampleClockUnavailable || mSampleClockCancelRequested || isStreamingActive())
                {
                    return;
                }

                try
                {
                    Thread.sleep(500L * attempt);
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return;
                }

                try
                {
                    updateBladeRF2SampleClock();
                    if(mSampleClockCancelRequested)
                    {
                        return;
                    }
                    mSampleClockInitialized = true;
                    mLog.debug("bladeRF2 sample clock configured after {} attempt(s)", attempt);
                    return;
                }
                catch(SourceException se)
                {
                    mLog.warn("Attempt {} to reprogram bladeRF2 sample clock failed; will retry", attempt, se);
                    if(mSampleClockCancelRequested)
                    {
                        return;
                    }
                }
            }

            mSampleClockUnavailable = true;
            mSampleClockInitialized = false;
            mLog.warn("Unable to reprogram bladeRF2 sample clock after retries; continuing with existing Si5338 configuration");
        }
        finally
        {
            synchronized(mSampleClockWorkerLock)
            {
                mSampleClockWorker = null;
                mSampleClockCancelRequested = false;
            }
        }
    }

    private void cancelSampleClockWorker(boolean markUnavailable)
    {
        Thread worker;
        synchronized(mSampleClockWorkerLock)
        {
            mSampleClockCancelRequested = true;
            worker = mSampleClockWorker;
            if(worker != null)
            {
                worker.interrupt();
            }
        }

        boolean workerStillAlive = false;
        if(worker != null)
        {
            try
            {
                worker.join(3_000L);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
            if(worker.isAlive())
            {
                mLog.debug("bladeRF - sample clock worker did not terminate after cancellation");
                workerStillAlive = true;
            }
            else
            {
                workerStillAlive = false;
            }
        }

        synchronized(mSampleClockWorkerLock)
        {
            if(!workerStillAlive && mSampleClockWorker == worker)
            {
                mSampleClockWorker = null;
            }
            if(!workerStillAlive)
            {
                mSampleClockCancelRequested = false;
            }
        }

        if(markUnavailable)
        {
            mSampleClockUnavailable = true;
            mSampleClockInitialized = false;
            mSampleClockUpdatePending = false;
        }
    }

    private void waitForSampleClockWorkerCompletion()
    {
        if(!SAMPLE_CLOCK_AUTO_ENABLED)
        {
            return;
        }
        cancelSampleClockWorker(false);
    }

    private void configureBladeRF2Streaming() throws SourceException
    {
        int gpio = mNiosAccess.readConfigGpio();
        gpio &= ~BladeRF2Constants.GPIO_TIMESTAMP;
        gpio &= ~BladeRF2Constants.GPIO_PACKET;
        gpio &= ~BladeRF2Constants.GPIO_8BIT_MODE;
        gpio &= ~BladeRF2Constants.GPIO_HIGHLY_PACKED_MODE;
        gpio = applyUsbSpeedFlags(gpio);
        mNiosAccess.writeConfigGpio(gpio);
    }

    private int applyUsbSpeedFlags(int gpio)
    {
        Device device = getDevice();
        if(device == null)
        {
            return gpio;
        }

        int speed = LibUsb.getDeviceSpeed(device);
        if(speed == LibUsb.SPEED_HIGH)
        {
            return gpio | BladeRF2Constants.GPIO_FEATURE_SMALL_DMA_XFER;
        }
        else if(speed == LibUsb.SPEED_SUPER)
        {
            return gpio & ~BladeRF2Constants.GPIO_FEATURE_SMALL_DMA_XFER;
        }

        return gpio;
    }

    private void setBladeRF2StreamingEnabled(boolean enable) throws SourceException
    {
        if(mUsbProtocol == null)
        {
            throw new SourceException("bladeRF - usb session unavailable");
        }

        SourceException lastFailure = null;

        for(int attempt = 1; attempt <= STREAM_TOGGLE_RETRIES; attempt++)
        {
            int result = mUsbProtocol.readVendorInt(BladeRFUsbConstants.CMD_RF_RX, (short)(enable ? 1 : 0), (short)0);

            if(result == 0 || result == FX3_STREAM_STATUS_ACCEPTED)
            {
                return;
            }

            if(result == FX3_STREAM_STATUS_BUSY)
            {
                lastFailure = new SourceException("bladeRF - FX3 stream " + (enable ? "enable" : "disable") +
                        " request busy (0x" + Integer.toHexString(result) + ")");
                mLog.warn("bladeRF - FX3 stream {} request busy (0x40) attempt {}/{} - resetting RF link interface",
                        enable ? "enable" : "disable", attempt, STREAM_TOGGLE_RETRIES);

                try
                {
                    mUsbProtocol.resetRfLinkInterface();
                }
                catch(SourceException se)
                {
                    lastFailure = se;
                    break;
                }

                try
                {
                    Thread.sleep(STREAM_TOGGLE_RETRY_DELAY_MS * attempt);
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }

                continue;
            }

            lastFailure = new SourceException("bladeRF - FX3 rejected stream " + (enable ? "enable" : "disable") +
                    " request (0x" + Integer.toHexString(result) + ")");
            break;
        }

        if(lastFailure == null)
        {
            lastFailure = new SourceException("bladeRF - FX3 rejected stream " + (enable ? "enable" : "disable") +
                    " request");
        }

        throw lastFailure;
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

    private int getChannelConstant()
    {
        return getChannelConstant(false);
    }

    private int getChannelConstant(boolean transmit)
    {
        return getChannelConstant(getActiveChannelId(), transmit);
    }

    private int getChannelConstant(int channelId, boolean transmit)
    {
        int sanitized = clampChannel(channelId);
        if(mChannelCount > 0)
        {
            sanitized = Math.min(sanitized, mChannelCount - 1);
        }

        int channel = sanitized << 1;
        if(transmit)
        {
            channel |= 0x01;
        }
        return channel;
    }

    private void logRficStatusOnFailure()
    {
        if(!isBladeRF2ControllerAvailable())
        {
            return;
        }

        try
        {
            BladeRF2RficController.RficStatus status = mRficController.readStatus();
            mLog.error("bladeRF RFIC status - initialized:{} pending:{} last-success:{}",
                    status.isInitialized(), status.getPendingWriteCount(), status.isLastWriteSuccessful());
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to query bladeRF RFIC status after failure", se);
        }
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
            return mMode == BladeRFConstants.GAIN_MGC;
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
