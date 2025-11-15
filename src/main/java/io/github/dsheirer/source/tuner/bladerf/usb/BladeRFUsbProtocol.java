package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.ByteBuffer;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import java.nio.IntBuffer;
import org.usb4java.BufferUtils;

/**
 * Convenience wrapper around the FX3 vendor commands and peripheral transfers used by bladeRF devices.
 * This class centralises the low-level usb4java interactions so higher level controllers can focus on
 * sequencing and error handling.
 */
public class BladeRFUsbProtocol
{
    private final DeviceHandle mHandle;

    public BladeRFUsbProtocol(DeviceHandle handle)
    {
        mHandle = handle;
    }

    /**
     * Issues a vendor control transfer and returns the resulting int value.
     */
    public int readVendorInt(byte command, short value, short index) throws SourceException
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(Integer.BYTES);
        int length = controlTransfer(LibUsb.ENDPOINT_IN | LibUsb.REQUEST_TYPE_VENDOR | LibUsb.RECIPIENT_DEVICE,
                command, value, index, buffer);
        if(length != Integer.BYTES)
        {
            throw new SourceException("Unexpected response length from bladeRF vendor command" + command);
        }

        buffer.rewind();
        return buffer.getInt();
    }

    /**
     * Issues a vendor control transfer that carries a 32-bit payload to the device.
     */
    public void writeVendorInt(byte command, short value, short index, int data) throws SourceException
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(Integer.BYTES);
        buffer.putInt(data);
        buffer.rewind();
        controlTransfer(LibUsb.ENDPOINT_OUT | LibUsb.REQUEST_TYPE_VENDOR | LibUsb.RECIPIENT_DEVICE, command, value,
                index, buffer);
    }

    /**
     * Exchanges a 16-byte peripheral packet with the FPGA/FX3 peripheral endpoints. The supplied buffer is used
     * for both the request and the response.
     */
    public void exchangePeripheralPacket(ByteBuffer buffer) throws SourceException
    {
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        transferred.put(0, 0);
        int status = LibUsb.bulkTransfer(mHandle, BladeRFUsbConstants.PERIPHERAL_ENDPOINT_OUT, buffer, transferred,
                BladeRFUsbConstants.PERIPHERAL_TIMEOUT_MS);
        if(status != LibUsb.SUCCESS)
        {
            throw new SourceException("bladeRF peripheral OUT transfer failed: " + LibUsb.errorName(status));
        }

        buffer.rewind();
        transferred.put(0, 0);
        status = LibUsb.bulkTransfer(mHandle, BladeRFUsbConstants.PERIPHERAL_ENDPOINT_IN, buffer, transferred,
                BladeRFUsbConstants.PERIPHERAL_TIMEOUT_MS);
        if(status != LibUsb.SUCCESS)
        {
            throw new SourceException("bladeRF peripheral IN transfer failed: " + LibUsb.errorName(status));
        }
        buffer.rewind();
    }

    private int controlTransfer(int requestType, byte command, short value, short index, ByteBuffer buffer)
            throws SourceException
    {
        int result = LibUsb.controlTransfer(mHandle, (byte)requestType, command, value, index, buffer,
                BladeRFUsbConstants.CONTROL_TIMEOUT_MS);

        if(result < 0)
        {
            throw new SourceException("bladeRF vendor command failed: " + LibUsb.errorName(result));
        }

        return result;
    }
}
