package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java port of the Si5338 clock configuration helpers used by libbladeRF.
 * Provides read/write access to the multisynth blocks so the Java control plane
 * can inspect or adjust the clock tree without loading the native library.
 */
public class BladeRFSi5338
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRFSi5338.class);

    private static final int SI5338_EN_A = 0x01;
    private static final int SI5338_EN_B = 0x02;
    private static final long VCO_FREQUENCY = 38_400_000L * 66L;
    private static final int ENABLE_BASE = 36;
    private static final int R_BASE = 31;
    private static final int MULTISYNTH_BASE = 53;
    private static final int MULTISYNTH_STRIDE = 11;

    private final BladeRFNiosAccess mNiosAccess;

    public BladeRFSi5338(BladeRFNiosAccess niosAccess)
    {
        mNiosAccess = niosAccess;
    }

    public BladeRationalRate getOutputFrequency(Output output) throws SourceException
    {
        MultiSynth multiSynth = new MultiSynth(output);
        readMultisynth(multiSynth);
        BladeRationalRate rate = computeFrequency(multiSynth);
        LOG.debug("Si5338 {} frequency {}", output, rate);
        return rate;
    }

    public BladeRationalRate setOutputFrequency(Output output, BladeRationalRate requested) throws SourceException
    {
        if(requested == null)
        {
            throw new SourceException("Requested rate is not specified");
        }

        MultiSynth multiSynth = new MultiSynth(output);
        BladeRationalRate sanitized = requested.copy();
        sanitized.reduce();
        calculateMultisynth(multiSynth, sanitized);
        writeMultisynth(multiSynth);
        BladeRationalRate actual = computeFrequency(multiSynth);
        LOG.debug("Si5338 {} configured to {} (requested {})", output, actual, requested);
        return actual;
    }

    public byte readRegister(int address) throws SourceException
    {
        return mNiosAccess.readSi5338(address);
    }

    public void writeRegister(int address, int value) throws SourceException
    {
        mNiosAccess.writeSi5338(address, value);
    }

    private void readMultisynth(MultiSynth multiSynth) throws SourceException
    {
        int enableReg = Byte.toUnsignedInt(readRegister(ENABLE_BASE + multiSynth.getIndex()));
        multiSynth.setEnable(enableReg & 0x07);

        for(int i = 0; i < multiSynth.regs.length; i++)
        {
            multiSynth.regs[i] = readRegister(multiSynth.getBase() + i);
        }

        int rValue = Byte.toUnsignedInt(readRegister(R_BASE + multiSynth.getIndex()));
        int power = (rValue >> 2) & 0x07;
        multiSynth.setR(1 << power);
        unpackRegs(multiSynth);
    }

    private void writeMultisynth(MultiSynth multiSynth) throws SourceException
    {
        int enableReg = Byte.toUnsignedInt(readRegister(ENABLE_BASE + multiSynth.getIndex()));
        enableReg |= multiSynth.getEnable();
        writeRegister(ENABLE_BASE + multiSynth.getIndex(), enableReg);

        for(int i = 0; i < multiSynth.regs.length; i++)
        {
            writeRegister(multiSynth.getBase() + i, Byte.toUnsignedInt(multiSynth.regs[i]));
        }

        int rCount = multiSynth.getR() >> 1;
        int rPower = 0;
        while(rCount > 0)
        {
            rCount >>= 1;
            rPower++;
        }
        int value = 0xC0 | (rPower << 2);
        writeRegister(R_BASE + multiSynth.getIndex(), value);
    }

    private void calculateMultisynth(MultiSynth multiSynth, BladeRationalRate rate) throws SourceException
    {
        BladeRationalRate req = rate.copy();
        if(multiSynth.isSampleClock())
        {
            req.doubleValue();
        }

        int rValue = 1;
        while(req.getInteger() < 5_000_000L && rValue < 32)
        {
            req.doubleValue();
            rValue <<= 1;
        }
        if(rValue == 32 && req.getInteger() < 5_000_000L)
        {
            throw new SourceException("Requested sample rate requires Si5338 R divider > 32");
        }

        BladeRationalRate abc = new BladeRationalRate(0,
                VCO_FREQUENCY * req.getDenominator(),
                req.getInteger() * req.getDenominator() + req.getNumerator());
        abc.reduce();

        long integer = abc.getInteger();
        if(integer < 8)
        {
            switch((int)integer)
            {
                case 0:
                case 1:
                case 2:
                case 3:
                case 5:
                case 7:
                    throw new SourceException("Si5338 integer divider too small: " + integer);
            }
        }
        else if(integer > 567)
        {
            throw new SourceException("Si5338 integer divider too large: " + integer);
        }

        while(abc.getNumerator() > (1 << 30) || abc.getDenominator() > (1 << 30))
        {
            abc.setNumerator(abc.getNumerator() >> 1);
            abc.setDenominator(abc.getDenominator() >> 1);
        }

        multiSynth.setA((int)abc.getInteger());
        multiSynth.setB((int)abc.getNumerator());
        multiSynth.setC((int)abc.getDenominator());
        multiSynth.setR(rValue);
        packRegs(multiSynth);
    }

    private BladeRationalRate computeFrequency(MultiSynth multiSynth)
    {
        BladeRationalRate abc = new BladeRationalRate(multiSynth.getA(), multiSynth.getB(), multiSynth.getC());
        BladeRationalRate rate = new BladeRationalRate();
        long numerator = VCO_FREQUENCY * abc.getDenominator();
        long denominator = (long)multiSynth.getR() * (abc.getInteger() * abc.getDenominator() + abc.getNumerator());
        if(multiSynth.isSampleClock())
        {
            denominator *= 2;
        }
        rate.setInteger(0);
        rate.setNumerator(numerator);
        rate.setDenominator(denominator);
        rate.reduce();
        return rate;
    }

    private void unpackRegs(MultiSynth multiSynth)
    {
        int[] regs = new int[multiSynth.regs.length];
        for(int i = 0; i < regs.length; i++)
        {
            regs[i] = Byte.toUnsignedInt(multiSynth.regs[i]);
        }

        long p1 = ((regs[2] & 0x3) << 16) | (regs[1] << 8) | regs[0];
        long p2 = (regs[5] << 22) | (regs[4] << 14) | (regs[3] << 6) | ((regs[2] >> 2) & 0x3F);
        long p3 = ((regs[9] & 0x3F) << 24) | (regs[8] << 16) | (regs[7] << 8) | regs[6];
        multiSynth.setC((int)p3);
        int a = (int)((p1 + 512) / 128);
        long temp = (p1 + 512) - 128L * a;
        temp = (temp * multiSynth.getC()) + p2;
        temp = (temp + 64) / 128;
        multiSynth.setA(a);
        multiSynth.setB((int)temp);
    }

    private void packRegs(MultiSynth multiSynth)
    {
        long temp = (long)multiSynth.getA() * multiSynth.getC() + multiSynth.getB();
        temp = temp * 128;
        temp = temp / multiSynth.getC() - 512;
        long p1 = temp;
        long p2 = ((long)multiSynth.getB() * 128) % multiSynth.getC();
        long p3 = multiSynth.getC();

        multiSynth.regs[0] = (byte)(p1 & 0xFF);
        multiSynth.regs[1] = (byte)((p1 >> 8) & 0xFF);
        multiSynth.regs[2] = (byte)(((p2 & 0x3F) << 2) | ((p1 >> 16) & 0x3));
        multiSynth.regs[3] = (byte)((p2 >> 6) & 0xFF);
        multiSynth.regs[4] = (byte)((p2 >> 14) & 0xFF);
        multiSynth.regs[5] = (byte)((p2 >> 22) & 0xFF);
        multiSynth.regs[6] = (byte)(p3 & 0xFF);
        multiSynth.regs[7] = (byte)((p3 >> 8) & 0xFF);
        multiSynth.regs[8] = (byte)((p3 >> 16) & 0xFF);
        multiSynth.regs[9] = (byte)((p3 >> 24) & 0x3F);
    }

    public enum Output
    {
        RX(1, SI5338_EN_A),
        TX(2, SI5338_EN_A | SI5338_EN_B),
        SMB(3, SI5338_EN_A);

        private final int mIndex;
        private final int mEnableMask;

        Output(int index, int enableMask)
        {
            mIndex = index;
            mEnableMask = enableMask;
        }

        int getIndex()
        {
            return mIndex;
        }

        int getEnableMask()
        {
            return mEnableMask;
        }
    }

    private static class MultiSynth
    {
        private final Output mOutput;
        private final int mBase;
        private int mEnable;
        private int mA;
        private int mB;
        private int mC;
        private int mR = 1;
        private final byte[] regs = new byte[10];

        MultiSynth(Output output)
        {
            mOutput = output;
            mBase = MULTISYNTH_BASE + output.getIndex() * MULTISYNTH_STRIDE;
            mEnable = output.getEnableMask();
        }

        int getIndex()
        {
            return mOutput.getIndex();
        }

        int getBase()
        {
            return mBase;
        }

        boolean isSampleClock()
        {
            return mOutput == Output.RX || mOutput == Output.TX;
        }

        int getEnable()
        {
            return mEnable;
        }

        void setEnable(int enable)
        {
            mEnable = enable;
        }

        int getA()
        {
            return mA;
        }

        void setA(int a)
        {
            mA = a;
        }

        int getB()
        {
            return mB;
        }

        void setB(int b)
        {
            mB = b;
        }

        int getC()
        {
            return mC;
        }

        void setC(int c)
        {
            mC = c;
        }

        int getR()
        {
            return mR;
        }

        void setR(int r)
        {
            mR = Math.max(1, r);
        }
    }
}
