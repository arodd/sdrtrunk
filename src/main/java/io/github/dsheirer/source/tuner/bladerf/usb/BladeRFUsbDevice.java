package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.usb4java.BufferUtils;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;

/**
 * Provides convenient access to immutable USB descriptor information for a bladeRF device.
 */
public class BladeRFUsbDevice
{
    private static final int MAX_DESCRIPTOR_LENGTH = 255;

    private final DeviceDescriptor mDescriptor = new DeviceDescriptor();
    private final DeviceHandle mHandle;

    private String mManufacturer;
    private String mProduct;
    private String mSerial;
    private BladeRFVersion mFirmwareVersion;

    public BladeRFUsbDevice(Device device, DeviceHandle handle) throws SourceException
    {
        mHandle = handle;
        int status = LibUsb.getDeviceDescriptor(device, mDescriptor);
        if(status != LibUsb.SUCCESS)
        {
            throw new SourceException("Unable to query bladeRF device descriptor: " + LibUsb.errorName(status));
        }

        mManufacturer = readDescriptorString(mDescriptor.iManufacturer());
        mProduct = readDescriptorString(mDescriptor.iProduct());
        mSerial = readDescriptorString(mDescriptor.iSerialNumber());
    }

    public String getManufacturer()
    {
        return mManufacturer;
    }

    public String getProduct()
    {
        return mProduct;
    }

    public String getSerialNumber()
    {
        return mSerial;
    }

    public String getBoardName()
    {
        if(mProduct != null && mProduct.toLowerCase().contains("bladerf2"))
        {
            return "bladerf2";
        }
        return "bladerf";
    }

    public BladeRFVersion getFirmwareVersion()
    {
        if(mFirmwareVersion == null)
        {
            try
            {
                String descriptor = readDescriptorString(BladeRFUsbConstants.STRING_INDEX_FIRMWARE_VERSION);
                if(descriptor != null && !descriptor.isEmpty())
                {
                    mFirmwareVersion = BladeRFVersion.parse(descriptor);
                }
            }
            catch(SourceException se)
            {
                //Ignore and leave version null; some firmware builds lack this descriptor.
            }
        }

        return mFirmwareVersion;
    }

    private String readDescriptorString(byte descriptorIndex) throws SourceException
    {
        if(descriptorIndex == 0)
        {
            return "";
        }

        ByteBuffer buffer = BufferUtils.allocateByteBuffer(MAX_DESCRIPTOR_LENGTH);
        int result = LibUsb.getStringDescriptor(mHandle, descriptorIndex, BladeRFUsbConstants.DEFAULT_LANG_ID, buffer);
        if(result < 0)
        {
            throw new SourceException("Unable to read USB string descriptor: " + LibUsb.errorName(result));
        }

        if(result <= 2)
        {
            return "";
        }

        byte[] data = new byte[result];
        for(int x = 0; x < result; x++)
        {
            data[x] = buffer.get(x);
        }

        return new String(data, 2, result - 2, StandardCharsets.UTF_16LE).trim();
    }
}
