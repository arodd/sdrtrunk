package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal INA219 helper that mirrors the initialization and telemetry helpers
 * used by libbladeRF. This allows the Java control plane to check the power
 * rails and expose voltage/current telemetry without libbladeRF.
 */
public class BladeRFIna219
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRFIna219.class);

    private static final int REG_CONFIGURATION = 0x00;
    private static final int REG_SHUNT_VOLTAGE = 0x01;
    private static final int REG_BUS_VOLTAGE = 0x02;
    private static final int REG_POWER = 0x03;
    private static final int REG_CURRENT = 0x04;
    private static final int REG_CALIBRATION = 0x05;
    private static final int RESET_BIT = 0x8000;
    private static final float DEFAULT_SHUNT_OHMS = 0.001f;
    private static final int MAX_RESET_POLLS = 50;

    private final BladeRFNiosAccess mNiosAccess;
    private final float mShuntResistance;
    private boolean mInitialized;

    public BladeRFIna219(BladeRFNiosAccess niosAccess)
    {
        this(niosAccess, DEFAULT_SHUNT_OHMS);
    }

    public BladeRFIna219(BladeRFNiosAccess niosAccess, float shuntResistance)
    {
        mNiosAccess = niosAccess;
        mShuntResistance = shuntResistance <= 0 ? DEFAULT_SHUNT_OHMS : shuntResistance;
    }

    public boolean isInitialized()
    {
        return mInitialized;
    }

    /**
     * Performs the configuration sequence used by libbladeRF. The routine
     * soft-resets the peripheral, programs the measurement resolution, and
     * loads the calibration constant derived from the shunt value.
     */
    public void initialize() throws SourceException
    {
        writeRegister(REG_CONFIGURATION, RESET_BIT);

        int attempts = 0;
        int config;
        do
        {
            config = readRegister(REG_CONFIGURATION);
            attempts++;
        }
        while((config & RESET_BIT) != 0 && attempts < MAX_RESET_POLLS);

        if((config & RESET_BIT) != 0)
        {
            throw new SourceException("INA219 reset timed out");
        }

        writeRegister(REG_CONFIGURATION, 0x019F);
        int calibration = (int)Math.round(0.04096 / (0.001 * mShuntResistance));
        writeRegister(REG_CALIBRATION, calibration);
        mInitialized = true;
        LOG.debug("INA219 calibrated with register 0x{}", Integer.toHexString(calibration));
    }

    public double readShuntVoltage() throws SourceException
    {
        int raw = readRegister(REG_SHUNT_VOLTAGE);
        return ((short)raw) * 1e-5d;
    }

    public double readBusVoltage() throws SourceException
    {
        int raw = readRegister(REG_BUS_VOLTAGE);
        if((raw & 0x1) != 0)
        {
            throw new SourceException("INA219 bus overflow reported");
        }
        return ((raw >> 3) & 0x1FFF) * 0.004d;
    }

    public double readCurrent() throws SourceException
    {
        int raw = readRegister(REG_CURRENT);
        return ((short)raw) * 0.001d;
    }

    public double readPower() throws SourceException
    {
        int raw = readRegister(REG_POWER);
        return ((short)raw) * 0.020d;
    }

    public PowerSnapshot readSnapshot() throws SourceException
    {
        return new PowerSnapshot(readShuntVoltage(), readBusVoltage(), readCurrent(), readPower());
    }

    private int readRegister(int register) throws SourceException
    {
        return mNiosAccess.readIna219(register);
    }

    private void writeRegister(int register, int value) throws SourceException
    {
        mNiosAccess.writeIna219(register, value);
    }

    /**
     * Power telemetry snapshot.
     */
    public static class PowerSnapshot
    {
        private final double mShuntVoltage;
        private final double mBusVoltage;
        private final double mCurrent;
        private final double mPower;

        PowerSnapshot(double shuntVoltage, double busVoltage, double current, double power)
        {
            mShuntVoltage = shuntVoltage;
            mBusVoltage = busVoltage;
            mCurrent = current;
            mPower = power;
        }

        public double getShuntVoltage()
        {
            return mShuntVoltage;
        }

        public double getBusVoltage()
        {
            return mBusVoltage;
        }

        public double getCurrent()
        {
            return mCurrent;
        }

        public double getPower()
        {
            return mPower;
        }
    }
}
