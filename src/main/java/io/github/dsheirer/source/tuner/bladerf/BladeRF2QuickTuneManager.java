package io.github.dsheirer.source.tuner.bladerf;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.bladerf.rfic.BladeRF2RficController;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2Constants;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFNiosAccess;

/**
 * Coordinates the FPGA fastlock and retune2 plumbing so bladeRF2 devices can perform quick retunes without libbladeRF.
 */
public class BladeRF2QuickTuneManager
{
    private static final BandPortMap[] RX_PORTS = new BandPortMap[]
            {
                    new BandPortMap(0, 0, BladeRF2Constants.RFFE_CONTROL_SPDT_SHUTDOWN,
                            BladeRF2Constants.AD936X_B_BALANCED),
                    new BandPortMap(70_000_000L, 3_000_000_000L, BladeRF2Constants.RFFE_CONTROL_SPDT_LOWBAND,
                            BladeRF2Constants.AD936X_B_BALANCED),
                    new BandPortMap(3_000_000_000L, 6_000_000_000L, BladeRF2Constants.RFFE_CONTROL_SPDT_HIGHBAND,
                            BladeRF2Constants.AD936X_A_BALANCED)
            };

    private static final BandPortMap[] TX_PORTS = new BandPortMap[]
            {
                    new BandPortMap(0, 0, BladeRF2Constants.RFFE_CONTROL_SPDT_SHUTDOWN,
                            BladeRF2Constants.AD936X_TXB),
                    new BandPortMap(46_875_000L, 3_000_000_000L, BladeRF2Constants.RFFE_CONTROL_SPDT_LOWBAND,
                            BladeRF2Constants.AD936X_TXB),
                    new BandPortMap(3_000_000_000L, 6_000_000_000L, BladeRF2Constants.RFFE_CONTROL_SPDT_HIGHBAND,
                            BladeRF2Constants.AD936X_TXA)
            };

    private final BladeRF2RficController mRficController;
    private final BladeRFNiosAccess mNiosAccess;
    private int mNextRxProfile;
    private int mNextTxProfile;

    public BladeRF2QuickTuneManager(BladeRF2RficController rficController, BladeRFNiosAccess niosAccess)
    {
        mRficController = rficController;
        mNiosAccess = niosAccess;
    }

    /**
     * Captures the current tuning configuration into a fastlock profile that can be replayed quickly later.
     */
    public synchronized BladeRF2QuickTuneProfile captureProfile(int channel, long frequency) throws SourceException
    {
        boolean tx = isTxChannel(channel);
        int niosProfile = tx ? mNextTxProfile : mNextRxProfile;
        if(niosProfile >= BladeRF2Constants.NIOS_FASTLOCK_PROFILE_COUNT)
        {
            throw new SourceException("bladeRF - quick tune profile limit reached; reconnect the device to reset");
        }

        if(tx)
        {
            mNextTxProfile++;
        }
        else
        {
            mNextRxProfile++;
        }

        int rffeProfile = niosProfile % BladeRF2Constants.RFFE_FASTLOCK_PROFILE_COUNT;
        mRficController.storeFastlockProfile(channel, rffeProfile);
        mNiosAccess.saveFastlockProfile(tx, rffeProfile, niosProfile);

        BandPortMap map = findBandPort(tx, frequency);
        if(map == null)
        {
            throw new SourceException("bladeRF - frequency " + frequency + "Hz is outside the quick tune range");
        }

        int port = tx ? encodeTxPort(map) : encodeRxPort(map);
        int spdt = tx ? encodeTxSpdt(map) : encodeRxSpdt(map);
        return new BladeRF2QuickTuneProfile(channel, niosProfile, rffeProfile, port, spdt);
    }

    /**
     * Schedules a retune at the provided timestamp using a previously captured profile.
     */
    public BladeRFNiosAccess.Retune2Response scheduleRetune(int channel, long timestamp, BladeRF2QuickTuneProfile profile)
            throws SourceException
    {
        validateProfile(channel, profile);
        return mNiosAccess.retune2(channel, timestamp, profile.getNiosProfile(), profile.getRffeProfile(),
                profile.getPort(), profile.getSpdt());
    }

    /**
     * Performs the retune immediately using the supplied profile.
     */
    public BladeRFNiosAccess.Retune2Response retuneNow(int channel, BladeRF2QuickTuneProfile profile) throws SourceException
    {
        return scheduleRetune(channel, BladeRF2Constants.RETUNE_NOW, profile);
    }

    /**
     * Clears the scheduled retune queue for the provided channel.
     */
    public void clearQueue(int channel) throws SourceException
    {
        mNiosAccess.retune2(channel, BladeRF2Constants.RETUNE_CLEAR_QUEUE, 0, 0, 0, 0);
    }

    private void validateProfile(int channel, BladeRF2QuickTuneProfile profile) throws SourceException
    {
        if(profile == null)
        {
            throw new SourceException("bladeRF - quick tune profile is required");
        }

        if(profile.getChannel() != channel)
        {
            throw new SourceException("bladeRF - quick tune profile must match the active channel");
        }
    }

    private BandPortMap findBandPort(boolean tx, long frequency)
    {
        BandPortMap[] maps = tx ? TX_PORTS : RX_PORTS;
        for(BandPortMap map: maps)
        {
            if(map.matches(frequency))
            {
                return map;
            }
        }
        return null;
    }

    private int encodeTxPort(BandPortMap map)
    {
        return (map.getRficPort() << 6) & 0xC0;
    }

    private int encodeRxPort(BandPortMap map)
    {
        int port = 0;
        int rficPort = map.getRficPort();
        if(rficPort < 3)
        {
            port |= (3 << (rficPort << 1));
        }
        else
        {
            port |= (1 << (rficPort - 3));
        }
        port |= BladeRF2Constants.RETUNE_PORT_IS_RX_MASK;
        return port & 0xFF;
    }

    private int encodeTxSpdt(BandPortMap map)
    {
        int value = map.getSpdt() & BladeRF2Constants.RFFE_CONTROL_SPDT_MASK;
        return ((value << 6) | (value << 4)) & 0xF0;
    }

    private int encodeRxSpdt(BandPortMap map)
    {
        int value = map.getSpdt() & BladeRF2Constants.RFFE_CONTROL_SPDT_MASK;
        return ((value << 2) | value) & 0x0F;
    }

    private boolean isTxChannel(int channel)
    {
        return (channel & 0x01) == 1;
    }

    private static class BandPortMap
    {
        private final long mMinimum;
        private final long mMaximum;
        private final int mSpdt;
        private final int mRficPort;

        BandPortMap(long minimum, long maximum, int spdt, int rficPort)
        {
            mMinimum = minimum;
            mMaximum = maximum;
            mSpdt = spdt;
            mRficPort = rficPort;
        }

        boolean matches(long frequency)
        {
            return frequency >= mMinimum && frequency <= mMaximum;
        }

        int getSpdt()
        {
            return mSpdt;
        }

        int getRficPort()
        {
            return mRficPort;
        }
    }
}
