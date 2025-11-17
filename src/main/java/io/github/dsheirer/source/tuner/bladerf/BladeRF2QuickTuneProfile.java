package io.github.dsheirer.source.tuner.bladerf;

/**
 * Captures the parameters required by the FPGA/NIOS retune2 path so that a bladeRF2 can retune quickly.
 */
public class BladeRF2QuickTuneProfile
{
    private final int mChannel;
    private final int mNiosProfile;
    private final int mRffeProfile;
    private final int mPort;
    private final int mSpdt;

    public BladeRF2QuickTuneProfile(int channel, int niosProfile, int rffeProfile, int port, int spdt)
    {
        mChannel = channel;
        mNiosProfile = niosProfile & 0xFFFF;
        mRffeProfile = rffeProfile & 0xFF;
        mPort = port & 0xFF;
        mSpdt = spdt & 0xFF;
    }

    public int getChannel()
    {
        return mChannel;
    }

    public int getNiosProfile()
    {
        return mNiosProfile;
    }

    public int getRffeProfile()
    {
        return mRffeProfile;
    }

    public int getPort()
    {
        return mPort;
    }

    public int getSpdt()
    {
        return mSpdt;
    }

    public boolean isTransmit()
    {
        return (mChannel & 0x01) == 1;
    }
}
