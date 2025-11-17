package io.github.dsheirer.source.tuner.bladerf.rfic;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2Constants;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFNiosAccess;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFRficCommand;

/**
 * Minimal RFIC controller that talks to the bladeRF2 FPGA-based control plane.
 * Provides just enough functionality for tuning (sample rate, bandwidth, frequency)
 * and FIR filter management so that the tuner can operate without libbladeRF.
 */
public class BladeRF2RficController
{
    private static final long DEFAULT_INIT_TIMEOUT_MS = 3_000L;
    private static final long STATUS_POLL_INTERVAL_MS = 50L;
    private static final int SAMPLE_RATE_MIN = 520_834;
    private static final int SAMPLE_RATE_MAX = 61_440_000;
    private static final int LOW_RATE_MAX = 2_083_334;
    private static final int SAMPLE_RATE_SWITCH_THRESHOLD = 40_000_000;
    private static final int SAMPLE_RATE_INTERMEDIATE = 30_000_000;
    private static final GainRange[] RX_GAIN_RANGES = new GainRange[]
            {
                    new GainRange(0, 1_300_000_000L, -16, 60, -17f),
                    new GainRange(1_300_000_000L, 4_000_000_000L, -15, 60, -11f),
                    new GainRange(4_000_000_000L, 6_000_000_000L, -12, 60, -2f)
            };

    private final BladeRFNiosAccess mNios;

    public BladeRF2RficController(BladeRFNiosAccess nios)
    {
        mNios = nios;
    }

    /**
     * Retrieves the current RFIC status flags exposed by the FPGA control plane.
     */
    public RficStatus readStatus() throws SourceException
    {
        long value = readGlobalCommand(BladeRFRficCommand.STATUS);
        boolean initialized = ((value >> BladeRF2Constants.RFIC_STATUS_INIT_SHIFT)
                & BladeRF2Constants.RFIC_STATUS_INIT_MASK) != 0;
        boolean lastSuccess = ((value >> BladeRF2Constants.RFIC_STATUS_WQ_SUCCESS_SHIFT)
                & BladeRF2Constants.RFIC_STATUS_WQ_SUCCESS_MASK) != 0;
        int queueLength = (int)((value >> BladeRF2Constants.RFIC_STATUS_WQ_LENGTH_SHIFT)
                & BladeRF2Constants.RFIC_STATUS_WQ_LENGTH_MASK);
        return new RficStatus(initialized, lastSuccess, queueLength);
    }

    /**
     * Queries the RFIC initialization state maintained by the NIOS firmware.
     */
    public InitializationState getInitializationState() throws SourceException
    {
        int stateValue = (int)readGlobalCommand(BladeRFRficCommand.INIT);
        return InitializationState.fromValue(stateValue);
    }

    /**
     * Requests a new RFIC initialization state.
     */
    public void setInitializationState(InitializationState state) throws SourceException
    {
        if(state == null)
        {
            throw new IllegalArgumentException("RFIC initialization state is required");
        }
        writeGlobalCommand(BladeRFRficCommand.INIT, state.getValue());
    }

    /**
     * Blocks until the RFIC reports it is initialized, attempting to transition it to the ON state if necessary.
     */
    public void ensureInitialized() throws SourceException
    {
        ensureInitialized(DEFAULT_INIT_TIMEOUT_MS);
    }

    /**
     * Blocks until the RFIC reports it is initialized, attempting to transition it to the ON state if necessary.
     *
     * @param timeoutMs maximum time to wait before giving up
     */
    public void ensureInitialized(long timeoutMs) throws SourceException
    {
        long timeout = Math.max(timeoutMs, STATUS_POLL_INTERVAL_MS);
        long deadline = System.currentTimeMillis() + timeout;
        SourceException lastError = null;

        while(System.currentTimeMillis() < deadline)
        {
            InitializationState state;
            try
            {
                state = getInitializationState();
            }
            catch(SourceException se)
            {
                lastError = se;
                sleep(STATUS_POLL_INTERVAL_MS);
                continue;
            }

            if(state == InitializationState.ON)
            {
                return;
            }

            try
            {
                setInitializationState(InitializationState.ON);
                waitForWriteQueueIdle(deadline - System.currentTimeMillis());
            }
            catch(SourceException se)
            {
                lastError = se;
            }

            sleep(STATUS_POLL_INTERVAL_MS);
        }

        if(lastError != null)
        {
            throw new SourceException("bladeRF - RFIC failed to reach initialized state", lastError);
        }

        throw new SourceException("bladeRF - RFIC failed to reach initialized state");
    }

    /**
     * Convenience helper that reports if the RFIC is initialized.
     */
    public boolean isInitialized() throws SourceException
    {
        return getInitializationState() == InitializationState.ON;
    }

    /**
     * Enables or disables the requested RFIC channel.
     */
    public void setRfEnabled(int channel, boolean enabled) throws SourceException
    {
        mNios.writeRficCommand(channel, BladeRFRficCommand.ENABLE, enabled ? 1L : 0L);
    }

    /**
     * Reads the current RSSI indication for the requested channel.
     */
    public RssiMeasurement readRssi(int channel) throws SourceException
    {
        long value = mNios.readRficCommand(channel, BladeRFRficCommand.RSSI);
        int multiplier = (int)((value >> BladeRF2Constants.RFIC_RSSI_MULT_SHIFT)
                & BladeRF2Constants.RFIC_RSSI_MULT_MASK);

        if(multiplier == 0)
        {
            return new RssiMeasurement(0, 0);
        }

        short preRaw = (short)((value >> BladeRF2Constants.RFIC_RSSI_PRE_SHIFT)
                & BladeRF2Constants.RFIC_RSSI_PRE_MASK);
        short symRaw = (short)((value >> BladeRF2Constants.RFIC_RSSI_SYM_SHIFT)
                & BladeRF2Constants.RFIC_RSSI_SYM_MASK);
        int pre = Math.round(preRaw / (float)multiplier);
        int sym = Math.round(symRaw / (float)multiplier);
        return new RssiMeasurement(pre, sym);
    }

    /**
     * Indicates if the requested TX channel is currently muted.
     */
    public boolean isTxMuted(int channel) throws SourceException
    {
        ensureTxChannel(channel);
        long value = mNios.readRficCommand(channel, BladeRFRficCommand.TX_MUTE);
        return value != 0;
    }

    /**
     * Sets the TX mute state for the requested channel.
     */
    public void setTxMute(int channel, boolean muted) throws SourceException
    {
        ensureTxChannel(channel);
        mNios.writeRficCommand(channel, BladeRFRficCommand.TX_MUTE, muted ? 1L : 0L);
    }

    /**
     * Configures the RFIC sample rate, enabling/disabling the 4x FIR filters when required.
     *
     * @return actual sample rate reported by the RFIC after configuration.
     */
    public int configureSampleRate(int channel, int requestedRate) throws SourceException
    {
        int clamped = clamp(requestedRate, SAMPLE_RATE_MIN, SAMPLE_RATE_MAX);
        int currentRate = readSampleRate(channel);
        boolean oldLow = isLowRate(currentRate);
        boolean newLow = isLowRate(clamped);

        RxFilter currentRxFilter = readRxFilter(channel);
        int txChannel = channel | 0x01;
        TxFilter currentTxFilter = readTxFilter(txChannel);

        if(newLow)
        {
            boolean crossingBoundary = (currentRate > SAMPLE_RATE_SWITCH_THRESHOLD && clamped < LOW_RATE_MAX)
                    || (clamped > SAMPLE_RATE_SWITCH_THRESHOLD && currentRate < LOW_RATE_MAX);

            if(crossingBoundary)
            {
                writeSampleRate(channel, SAMPLE_RATE_INTERMEDIATE);
            }

            if(currentRxFilter != RxFilter.DEC4 || currentTxFilter != TxFilter.INT4)
            {
                setRxFilter(channel, RxFilter.DEC4);
                setTxFilter(txChannel, TxFilter.INT4);
            }
        }

        writeSampleRate(channel, clamped);

        if(oldLow && !newLow)
        {
            if(currentRxFilter != RxFilter.DEFAULT || currentTxFilter != TxFilter.DEFAULT)
            {
                setRxFilter(channel, RxFilter.DEFAULT);
                setTxFilter(txChannel, TxFilter.DEFAULT);
            }
        }

        return readSampleRate(channel);
    }

    public int readSampleRate(int channel) throws SourceException
    {
        return (int)mNios.readRficCommand(channel, BladeRFRficCommand.SAMPLERATE);
    }

    private void writeSampleRate(int channel, int value) throws SourceException
    {
        mNios.writeRficCommand(channel, BladeRFRficCommand.SAMPLERATE, Integer.toUnsignedLong(value));
    }

    public void writeFrequency(int channel, long frequency) throws SourceException
    {
        mNios.writeRficCommand(channel, BladeRFRficCommand.FREQUENCY, frequency);
    }

    public long readFrequency(int channel) throws SourceException
    {
        return mNios.readRficCommand(channel, BladeRFRficCommand.FREQUENCY);
    }

    public int writeBandwidth(int channel, int bandwidth) throws SourceException
    {
        mNios.writeRficCommand(channel, BladeRFRficCommand.BANDWIDTH, Integer.toUnsignedLong(bandwidth));
        return readBandwidth(channel);
    }

    public int readBandwidth(int channel) throws SourceException
    {
        return (int)mNios.readRficCommand(channel, BladeRFRficCommand.BANDWIDTH);
    }

    public void writeGainMode(int channel, int gainMode) throws SourceException
    {
        mNios.writeRficCommand(channel, BladeRFRficCommand.GAIN_MODE, Integer.toUnsignedLong(gainMode));
    }

    public void writeManualGain(int channel, int requestedGain, long frequency) throws SourceException
    {
        GainRange range = findRxGainRange(frequency);
        int clamped = clamp(requestedGain, range.getMinimum(), range.getMaximum());
        int stageValue = Math.round(clamped - range.getOffset());
        stageValue = clamp(stageValue, range.getStageMinimum(), range.getStageMaximum());
        mNios.writeRficCommand(channel, BladeRFRficCommand.GAIN, Integer.toUnsignedLong(stageValue));
    }

    /**
     * Stores the currently configured tuning parameters into the requested fastlock profile slot.
     */
    public void storeFastlockProfile(int channel, int profile) throws SourceException
    {
        int sanitized = profile & 0xFF;
        mNios.writeRficCommand(channel, BladeRFRficCommand.FASTLOCK, Integer.toUnsignedLong(sanitized));
    }

    private GainRange findRxGainRange(long frequency)
    {
        for(GainRange range: RX_GAIN_RANGES)
        {
            if(range.matches(frequency))
            {
                return range;
            }
        }

        return RX_GAIN_RANGES[0];
    }

    private RxFilter readRxFilter(int channel) throws SourceException
    {
        long value = mNios.readRficCommand(channel & 0x0F, BladeRFRficCommand.FILTER);
        return RxFilter.fromValue((int)value);
    }

    private TxFilter readTxFilter(int txChannel) throws SourceException
    {
        long value = mNios.readRficCommand(txChannel & 0x0F, BladeRFRficCommand.FILTER);
        return TxFilter.fromValue((int)value);
    }

    private void setRxFilter(int channel, RxFilter filter) throws SourceException
    {
        mNios.writeRficCommand(channel & 0x0F, BladeRFRficCommand.FILTER, filter.getValue());
    }

    private void setTxFilter(int txChannel, TxFilter filter) throws SourceException
    {
        mNios.writeRficCommand(txChannel & 0x0F, BladeRFRficCommand.FILTER, filter.getValue());
    }

    private long readGlobalCommand(BladeRFRficCommand command) throws SourceException
    {
        return mNios.readRficCommand(-1, command);
    }

    private void writeGlobalCommand(BladeRFRficCommand command, long value) throws SourceException
    {
        mNios.writeRficCommand(-1, command, value);
    }

    private void waitForWriteQueueIdle(long timeoutMs) throws SourceException
    {
        long remaining = Math.max(timeoutMs, STATUS_POLL_INTERVAL_MS);
        long deadline = System.currentTimeMillis() + remaining;

        while(System.currentTimeMillis() < deadline)
        {
            RficStatus status = readStatus();

            if(status.getPendingWriteCount() == 0)
            {
                if(status.isLastWriteSuccessful())
                {
                    return;
                }
                throw new SourceException("bladeRF - RFIC reported the last write failed");
            }

            sleep(STATUS_POLL_INTERVAL_MS);
        }

        throw new SourceException("bladeRF - RFIC command queue did not drain");
    }

    private void sleep(long millis) throws SourceException
    {
        try
        {
            Thread.sleep(millis);
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new SourceException("bladeRF - interrupted while waiting for RFIC status", ie);
        }
    }

    private void ensureTxChannel(int channel) throws SourceException
    {
        if(!isTxChannel(channel))
        {
            throw new SourceException("bladeRF - TX mute is only valid for TX channels");
        }
    }

    private boolean isTxChannel(int channel)
    {
        return (channel & 0x01) == 1;
    }

    private static boolean isLowRate(int rate)
    {
        return rate >= SAMPLE_RATE_MIN && rate <= LOW_RATE_MAX;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private enum RxFilter
    {
        BYPASS(0),
        CUSTOM(1),
        DEC1(2),
        DEC2(3),
        DEC4(4);

        private final long mValue;

        RxFilter(long value)
        {
            mValue = value;
        }

        long getValue()
        {
            return mValue;
        }

        static RxFilter fromValue(int value)
        {
            for(RxFilter filter: values())
            {
                if(filter.mValue == value)
                {
                    return filter;
                }
            }
            return DEFAULT;
        }

        static final RxFilter DEFAULT = DEC1;
    }

    private enum TxFilter
    {
        BYPASS(0),
        CUSTOM(1),
        INT1(2),
        INT2(3),
        INT4(4);

        private final long mValue;

        TxFilter(long value)
        {
            mValue = value;
        }

        long getValue()
        {
            return mValue;
        }

        static TxFilter fromValue(int value)
        {
            for(TxFilter filter: values())
            {
                if(filter.mValue == value)
                {
                    return filter;
                }
            }
            return DEFAULT;
        }

        static final TxFilter DEFAULT = BYPASS;
    }

    private static class GainRange
    {
        private final long mFrequencyMin;
        private final long mFrequencyMax;
        private final int mMinimum;
        private final int mMaximum;
        private final float mOffset;

        GainRange(long frequencyMin, long frequencyMax, int minimum, int maximum, float offset)
        {
            mFrequencyMin = frequencyMin;
            mFrequencyMax = frequencyMax;
            mMinimum = minimum;
            mMaximum = maximum;
            mOffset = offset;
        }

        boolean matches(long frequency)
        {
            return frequency >= mFrequencyMin && frequency <= mFrequencyMax;
        }

        int getMinimum()
        {
            return mMinimum;
        }

        int getMaximum()
        {
            return mMaximum;
        }

        float getOffset()
        {
            return mOffset;
        }

        int getStageMinimum()
        {
            return Math.round(mMinimum - mOffset);
        }

        int getStageMaximum()
        {
            return Math.round(mMaximum - mOffset);
        }
    }

    /**
     * Snapshot of the RFIC status flags provided by the FPGA.
     */
    public static class RficStatus
    {
        private final boolean mInitialized;
        private final boolean mLastWriteSuccessful;
        private final int mPendingWriteCount;

        RficStatus(boolean initialized, boolean lastWriteSuccessful, int pendingWriteCount)
        {
            mInitialized = initialized;
            mLastWriteSuccessful = lastWriteSuccessful;
            mPendingWriteCount = pendingWriteCount;
        }

        public boolean isInitialized()
        {
            return mInitialized;
        }

        public boolean isLastWriteSuccessful()
        {
            return mLastWriteSuccessful;
        }

        public int getPendingWriteCount()
        {
            return mPendingWriteCount;
        }
    }

    /**
     * RFIC initialization states mirrored from the NIOS firmware.
     */
    public enum InitializationState
    {
        OFF(BladeRF2Constants.RFIC_INIT_STATE_OFF),
        ON(BladeRF2Constants.RFIC_INIT_STATE_ON),
        STANDBY(BladeRF2Constants.RFIC_INIT_STATE_STANDBY);

        private final int mValue;

        InitializationState(int value)
        {
            mValue = value;
        }

        public int getValue()
        {
            return mValue;
        }

        static InitializationState fromValue(int value)
        {
            for(InitializationState state: values())
            {
                if(state.mValue == value)
                {
                    return state;
                }
            }
            return OFF;
        }
    }

    /**
     * Represents the RSSI components reported by the RFIC command.
     */
    public static class RssiMeasurement
    {
        private final int mPreRssi;
        private final int mSymbolRssi;

        RssiMeasurement(int preRssi, int symbolRssi)
        {
            mPreRssi = preRssi;
            mSymbolRssi = symbolRssi;
        }

        public int getPreRssi()
        {
            return mPreRssi;
        }

        public int getSymbolRssi()
        {
            return mSymbolRssi;
        }
    }
}
