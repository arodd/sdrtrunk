package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * RFIC command identifiers used by the FPGA-based tuning interface.
 * These values mirror the bladerf_rfic_command enum in bladeRF2 firmware.
 */
public enum BladeRFRficCommand
{
    STATUS(0x00),
    INIT(0x01),
    ENABLE(0x02),
    SAMPLERATE(0x03),
    FREQUENCY(0x04),
    BANDWIDTH(0x05),
    GAIN_MODE(0x06),
    GAIN(0x07),
    RSSI(0x08),
    FILTER(0x09),
    TX_MUTE(0x0A),
    FASTLOCK(0x0B);

    private final int mValue;

    BladeRFRficCommand(int value)
    {
        mValue = value & 0xFF;
    }

    public int getValue()
    {
        return mValue;
    }
}
