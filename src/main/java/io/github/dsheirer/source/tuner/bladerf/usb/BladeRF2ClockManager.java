package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRFSi5338.Output;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRF2SmbMode;
import io.github.dsheirer.source.tuner.bladerf.usb.BladeRationalRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the bladeRF 2 clock tree by mirroring the libbladeRF PLL and Si5338 helpers.
 * Handles the reference clock PLL (ADF400x) programming and the Si5338 sample clock output
 * so the FPGA/RFIC see consistent clocking regardless of the FX3 firmware defaults.
 */
public class BladeRF2ClockManager
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRF2ClockManager.class);
    private static final double RATIO_TOLERANCE = 0.00001;
    private static final int[][] SMB_DEFAULT_CONFIG = {
            {6, 0x08},
            {28, 0x0B},
            {29, 0x08},
            {30, 0xB0},
            {34, 0xE3},
            {39, 0x00},
            {86, 0x00},
            {87, 0x00},
            {88, 0x00},
            {89, 0x00},
            {90, 0x00},
            {91, 0x00},
            {92, 0x00},
            {93, 0x00},
            {94, 0x00},
            {95, 0x00}
    };
    private static final int[][] SMB_INPUT_CONFIG = {
            {6, 0x04},
            {28, 0x2B},
            {29, 0x28},
            {30, 0xA8}
    };
    private static final int[][] SMB_OUTPUT_CONFIG = {
            {34, 0x22}
    };
    private final BladeRFNiosAccess mNiosAccess;
    private final BladeRFSi5338 mSi5338;
    private final Object mLock = new Object();
    private boolean mReferenceConfigured;
    private int mLastSampleRate;

    public BladeRF2ClockManager(BladeRFNiosAccess niosAccess, BladeRFSi5338 si5338)
    {
        mNiosAccess = niosAccess;
        mSi5338 = si5338;
    }

    /**
     * Configures the PLL to the default reference frequency if it has not already been set.
     */
    public void initializeReferenceClock() throws SourceException
    {
        synchronized(mLock)
        {
            if(mReferenceConfigured || mNiosAccess == null)
            {
                return;
            }

            configureReferenceClock(BladeRF2Constants.DEFAULT_REFCLK_FREQUENCY);
            mReferenceConfigured = true;
        }
    }

    /**
     * Configures the PLL reference frequency.
     *
     * @param frequencyHz desired reference frequency in Hertz.
     */
    public void configureReferenceClock(long frequencyHz) throws SourceException
    {
        if(mNiosAccess == null)
        {
            throw new SourceException("bladeRF - unable to access PLL without NIOS access");
        }

        if(frequencyHz < BladeRF2Constants.PLL_REFCLK_MIN || frequencyHz > BladeRF2Constants.PLL_REFCLK_MAX)
        {
            throw new SourceException("bladeRF - PLL reference frequency out of range: " + frequencyHz);
        }

        PllRatio ratio = calculateRatio(frequencyHz, BladeRF2Constants.VCTCXO_FREQUENCY);
        boolean previouslyEnabled = isPllEnabled();

        if(!previouslyEnabled)
        {
            setPllEnabled(true);
        }

        writePllRegister(0, buildReferenceCounterLatch(ratio.getR()));
        writePllRegister(1, buildNCounterLatch(ratio.getN()));
        writePllRegister(2, buildFunctionLatch());

        if(!previouslyEnabled)
        {
            setPllEnabled(false);
        }

        LOG.debug("Configured PLL reference clock to {} Hz (R={} N={})", frequencyHz, ratio.getR(), ratio.getN());
    }

    /**
     * Programs the Si5338 RX output to the requested sample rate.
     *
     * @param sampleRateHz desired sample rate in Hertz.
     */
    public void configureSampleClock(int sampleRateHz) throws SourceException
    {
        synchronized(mLock)
        {
            if(mSi5338 == null || sampleRateHz <= 0)
            {
                return;
            }

            if(sampleRateHz == mLastSampleRate)
            {
                return;
            }

            BladeRationalRate requested = new BladeRationalRate(sampleRateHz, 0, 1);
            BladeRationalRate actual = mSi5338.setOutputFrequency(BladeRFSi5338.Output.RX, requested);
            mLastSampleRate = sampleRateHz;

            if(LOG.isDebugEnabled() && actual != null)
            {
                LOG.debug("Si5338 RX clock set to {} Hz (requested {}).", actual.toString(), requested.toString());
            }
        }
    }

    /**
     * Configures the SMB clock port mode.
     */
    public void setSmbMode(BladeRF2SmbMode mode) throws SourceException
    {
        if(mSi5338 == null)
        {
            throw new SourceException("bladeRF - SMB clock control is unavailable");
        }

        if(mode == null)
        {
            throw new SourceException("bladeRF - SMB clock mode is not specified");
        }

        synchronized(mLock)
        {
            applySmbDefaults();

            switch(mode)
            {
                case DISABLED:
                    return;
                case OUTPUT:
                    enableSmbOutput();
                    return;
                case INPUT:
                    enableSmbInput();
                    return;
                case UNAVAILABLE:
                    throw new SourceException("bladeRF - SMB clock port unavailable when expansion board is attached");
                case INVALID:
                default:
                    throw new SourceException("bladeRF - invalid SMB clock mode request");
            }
        }
    }

    /**
     * Reads the current SMB clock mode.
     */
    public BladeRF2SmbMode getSmbMode() throws SourceException
    {
        if(mSi5338 == null)
        {
            return BladeRF2SmbMode.UNAVAILABLE;
        }

        synchronized(mLock)
        {
            int driverFormat = Byte.toUnsignedInt(mSi5338.readRegister(39)) & 0x7;

            switch(driverFormat)
            {
                case 0x00:
                    break;
                case 0x01:
                    return BladeRF2SmbMode.OUTPUT;
                case 0x02:
                    return BladeRF2SmbMode.UNAVAILABLE;
                default:
                    throw new SourceException("bladeRF - SMB clock port is in an unexpected state (Si5338[39]=0x"
                            + Integer.toHexString(driverFormat) + ")");
            }

            int divider = Byte.toUnsignedInt(mSi5338.readRegister(28));
            if((divider & (1 << 5)) != 0)
            {
                return BladeRF2SmbMode.INPUT;
            }

            return BladeRF2SmbMode.DISABLED;
        }
    }

    /**
     * Configures the SMB clock output frequency using an integer value.
     */
    public BladeRationalRate configureSmbFrequency(long frequencyHz) throws SourceException
    {
        return configureSmbFrequency(new BladeRationalRate(frequencyHz, 0, 1));
    }

    /**
     * Configures the SMB clock output frequency.
     */
    public BladeRationalRate configureSmbFrequency(BladeRationalRate requested) throws SourceException
    {
        if(mSi5338 == null)
        {
            throw new SourceException("bladeRF - SMB clock control is unavailable");
        }

        if(requested == null)
        {
            throw new SourceException("bladeRF - SMB frequency request is not specified");
        }

        synchronized(mLock)
        {
            BladeRationalRate sanitized = requested.copy();
            sanitized.reduce();
            double frequency = sanitized.toDouble();

            if(frequency < BladeRF2Constants.SMB_FREQUENCY_MIN || frequency > BladeRF2Constants.SMB_FREQUENCY_MAX)
            {
                throw new SourceException("bladeRF - SMB frequency out of range: " + sanitized);
            }

            BladeRationalRate actual = mSi5338.setOutputFrequency(Output.SMB, sanitized);
            return (actual != null) ? actual : sanitized;
        }
    }

    /**
     * Reads the currently configured SMB clock output frequency.
     */
    public BladeRationalRate getSmbFrequency() throws SourceException
    {
        if(mSi5338 == null)
        {
            throw new SourceException("bladeRF - SMB clock control is unavailable");
        }

        synchronized(mLock)
        {
            return mSi5338.getOutputFrequency(Output.SMB);
        }
    }

    private void applySmbDefaults() throws SourceException
    {
        writeRegisterSet(SMB_DEFAULT_CONFIG);
    }

    private void enableSmbInput() throws SourceException
    {
        writeRegisterSet(SMB_INPUT_CONFIG);
        int current = Byte.toUnsignedInt(mSi5338.readRegister(39));
        current &= ~0x1;
        mSi5338.writeRegister(39, current);
    }

    private void enableSmbOutput() throws SourceException
    {
        int current = Byte.toUnsignedInt(mSi5338.readRegister(39));
        current |= 0x1;
        mSi5338.writeRegister(39, current);
        writeRegisterSet(SMB_OUTPUT_CONFIG);
    }

    private void writeRegisterSet(int[][] registers) throws SourceException
    {
        if(registers == null)
        {
            return;
        }

        for(int[] entry: registers)
        {
            if(entry.length != 2)
            {
                continue;
            }
            mSi5338.writeRegister(entry[0], entry[1]);
        }
    }

    private boolean isPllEnabled() throws SourceException
    {
        int gpio = mNiosAccess.readConfigGpio();
        return (gpio & BladeRF2Constants.CONFIG_GPIO_PLL_ENABLE) != 0;
    }

    private void setPllEnabled(boolean enabled) throws SourceException
    {
        int gpio = mNiosAccess.readConfigGpio();

        if(enabled)
        {
            gpio |= BladeRF2Constants.CONFIG_GPIO_PLL_ENABLE;
        }
        else
        {
            gpio &= ~BladeRF2Constants.CONFIG_GPIO_PLL_ENABLE;
        }

        mNiosAccess.writeConfigGpio(gpio);
    }

    private void writePllRegister(int register, int value) throws SourceException
    {
        mNiosAccess.writeAdf400x(register, value);
    }

    private PllRatio calculateRatio(long refFrequency, long clockFrequency) throws SourceException
    {
        if(clockFrequency < BladeRF2Constants.PLL_CLOCK_MIN || clockFrequency > BladeRF2Constants.PLL_CLOCK_MAX)
        {
            throw new SourceException("bladeRF - PLL clock frequency out of range: " + clockFrequency);
        }

        double target = (double)clockFrequency / (double)refFrequency;

        for(int r = 1; r < 16_383; r++)
        {
            int n = (int)Math.round(target * r);

            if(n < 1 || n > 8_191)
            {
                continue;
            }

            double ratio = (double)n / (double)r;
            if(Math.abs(ratio - target) < RATIO_TOLERANCE)
            {
                return new PllRatio(r, n);
            }
        }

        throw new SourceException("bladeRF - requested PLL ratio not achievable");
    }

    private int buildReferenceCounterLatch(int r)
    {
        int value = 0;
        value |= (r & ((1 << 14) - 1)) << 2;
        return value;
    }

    private int buildNCounterLatch(int n)
    {
        int value = 0;
        value |= (n & ((1 << 13) - 1)) << 8;
        return value;
    }

    private int buildFunctionLatch()
    {
        int value = 0;
        value |= (1 & ((1 << 3) - 1)) << 4; // muxout digital lock detect
        value |= 1 << 7; // positive PD polarity
        value |= 0x7 << 15; // current setting 1
        value |= 0x7 << 18; // current setting 2
        return value;
    }

    private static class PllRatio
    {
        private final int mR;
        private final int mN;

        PllRatio(int r, int n)
        {
            mR = r;
            mN = n;
        }

        int getR()
        {
            return mR;
        }

        int getN()
        {
            return mN;
        }
    }
}
