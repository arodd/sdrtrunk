package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Simple representation of the SPI flash manufacturer and device identifiers.
 */
public class BladeRFFlashId
{
    private final int mManufacturerId;
    private final int mDeviceId;

    public BladeRFFlashId(int manufacturerId, int deviceId)
    {
        mManufacturerId = manufacturerId & 0xFF;
        mDeviceId = deviceId & 0xFF;
    }

    public static BladeRFFlashId fromPackedValue(int packed)
    {
        int device = packed & 0xFF;
        int manufacturer = (packed >> 8) & 0xFF;
        return new BladeRFFlashId(manufacturer, device);
    }

    public int getManufacturerId()
    {
        return mManufacturerId;
    }

    public int getDeviceId()
    {
        return mDeviceId;
    }

    /**
     * Returns the packed 16-bit value that the FX3 firmware reports.
     */
    public int toPackedValue()
    {
        return ((mManufacturerId & 0xFF) << 8) | (mDeviceId & 0xFF);
    }

    @Override
    public String toString()
    {
        return String.format("FlashId{mid=0x%02X, did=0x%02X}", mManufacturerId, mDeviceId);
    }
}
