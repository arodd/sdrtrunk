package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.usb4java.BufferUtils;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience wrapper around the FX3 vendor commands and peripheral transfers used by bladeRF devices.
 * This class centralises the low-level usb4java interactions so higher level controllers can focus on
 * sequencing and error handling.
 */
public class BladeRFUsbProtocol
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRFUsbProtocol.class);
    private final DeviceHandle mHandle;
    private static final int CAL_CACHE_CHUNK = BladeRFUsbConstants.CALIBRATION_TRANSFER_BYTES;
    private final Object mPeripheralLock = new Object();
    private static final int PERIPHERAL_MAX_ATTEMPTS = 3;
    private static final int PERIPHERAL_RECOVERY_DELAY_MS = 10;
    private static final boolean TRACE_PERIPHERAL =
            Boolean.parseBoolean(System.getProperty("sdrtrunk.bladerf.tracePeripherals", "false"));

    public BladeRFUsbProtocol(DeviceHandle handle)
    {
        mHandle = handle;
    }

    /**
     * Selects the RF link interface so the peripheral endpoints are active.
     */
    public void selectRfLinkInterface() throws SourceException
    {
        setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
    }

    /**
     * Issues a vendor control transfer and returns the resulting int value.
     */
    public int readVendorInt(byte command, short value, short index) throws SourceException
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(Integer.BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
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
        buffer.order(ByteOrder.LITTLE_ENDIAN);
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
        synchronized(mPeripheralLock)
        {
            IntBuffer transferred = BufferUtils.allocateIntBuffer();
            for(int attempt = 1; attempt <= PERIPHERAL_MAX_ATTEMPTS; attempt++)
            {
                buffer.rewind();
                transferred.put(0, 0);
                logPeripheralBuffer("request attempt " + attempt, buffer);
                int status = LibUsb.bulkTransfer(mHandle, BladeRFUsbConstants.PERIPHERAL_ENDPOINT_OUT, buffer, transferred,
                        BladeRFUsbConstants.PERIPHERAL_TIMEOUT_MS);
                if(status != LibUsb.SUCCESS)
                {
                    if(shouldRetryPeripheral(status, attempt))
                    {
                        recoverPeripheralInterface();
                        continue;
                    }
                    logPeripheralFailure("OUT", attempt, status);
                    throw new SourceException("bladeRF peripheral OUT transfer failed: " + LibUsb.errorName(status));
                }

                buffer.rewind();
                transferred.put(0, 0);
                status = LibUsb.bulkTransfer(mHandle, BladeRFUsbConstants.PERIPHERAL_ENDPOINT_IN, buffer, transferred,
                        BladeRFUsbConstants.PERIPHERAL_TIMEOUT_MS);
                if(status != LibUsb.SUCCESS)
                {
                    if(shouldRetryPeripheral(status, attempt))
                    {
                        recoverPeripheralInterface();
                        continue;
                    }
                    logPeripheralFailure("IN", attempt, status);
                    throw new SourceException("bladeRF peripheral IN transfer failed: " + LibUsb.errorName(status));
                }

                buffer.rewind();
                logPeripheralBuffer("response attempt " + attempt, buffer);
                return;
            }

            throw new SourceException("bladeRF peripheral transfer failed after retries");
        }
    }

    private boolean shouldRetryPeripheral(int status, int attempt)
    {
        if(attempt >= PERIPHERAL_MAX_ATTEMPTS)
        {
            return false;
        }

        return status == LibUsb.ERROR_TIMEOUT
                || status == LibUsb.ERROR_IO
                || status == LibUsb.ERROR_PIPE
                || status == LibUsb.ERROR_OVERFLOW;
    }

    private void recoverPeripheralInterface() throws SourceException
    {
        if(TRACE_PERIPHERAL && LOG.isDebugEnabled())
        {
            LOG.debug("bladeRF peripheral recovery: switching to NULL interface");
        }
        setInterface(BladeRFUsbConstants.USB_IF_NULL);
        sleepQuietly();
        if(TRACE_PERIPHERAL && LOG.isDebugEnabled())
        {
            LOG.debug("bladeRF peripheral recovery: switching to RF_LINK interface");
        }
        setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
        sleepQuietly();
    }

    /**
     * Resets the RF link interface by toggling through the NULL interface before re-selecting RF_LINK.
     */
    public void resetRfLinkInterface() throws SourceException
    {
        setInterface(BladeRFUsbConstants.USB_IF_NULL);
        sleepQuietly();
        setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
        sleepQuietly();
    }

    private void sleepQuietly()
    {
        try
        {
            Thread.sleep(PERIPHERAL_RECOVERY_DELAY_MS);
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void logPeripheralBuffer(String prefix, ByteBuffer buffer)
    {
        if(!TRACE_PERIPHERAL)
        {
            return;
        }

        ByteBuffer duplicate = buffer.duplicate();
        duplicate.rewind();
        StringBuilder sb = new StringBuilder();
        while(duplicate.hasRemaining())
        {
            sb.append(String.format("%02x ", duplicate.get() & 0xFF));
        }
        LOG.info("bladeRF peripheral {}: {}", prefix, sb.toString().trim());
    }

    private void logPeripheralFailure(String direction, int attempt, int status)
    {
        if(LOG.isWarnEnabled())
        {
            LOG.warn("bladeRF peripheral {} transfer failed on attempt {} with status {}", direction, attempt,
                    LibUsb.errorName(status));
        }
    }

    /**
     * Reads the SPI flash manufacturer/device identifiers.
     */
    public BladeRFFlashId queryFlashId() throws SourceException
    {
        int packed = readVendorInt(BladeRFUsbConstants.CMD_QUERY_FLASH_ID, (short)0, (short)0);
        return BladeRFFlashId.fromPackedValue(packed);
    }

    /**
     * Retrieves the FX3 firmware's cached calibration values.
     */
    public byte[] readCalibrationCache() throws SourceException
    {
        setInterface(BladeRFUsbConstants.USB_IF_SPI_FLASH);
        try
        {
            byte[] data = new byte[BladeRFUsbConstants.CALIBRATION_CACHE_SIZE];
            int offset = 0;
            while(offset < data.length)
            {
                int chunk = Math.min(CAL_CACHE_CHUNK, data.length - offset);
                ByteBuffer buffer = BufferUtils.allocateByteBuffer(chunk);
                int length = controlTransfer(
                        LibUsb.ENDPOINT_IN | LibUsb.REQUEST_TYPE_VENDOR | LibUsb.RECIPIENT_DEVICE,
                        BladeRFUsbConstants.CMD_READ_CAL_CACHE, (short)0, (short)offset, buffer);
                if(length != chunk)
                {
                    throw new SourceException("bladeRF - calibration cache transfer truncated (" + length + "/" + chunk + ")");
                }
                buffer.rewind();
                buffer.get(data, offset, chunk);
                offset += chunk;
            }
            return data;
        }
        finally
        {
            setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
        }
    }

    /**
     * Invalidates the cached calibration table stored in the FX3.
     */
    public void invalidateCalibrationCache() throws SourceException
    {
        controlTransfer(LibUsb.ENDPOINT_OUT | LibUsb.REQUEST_TYPE_VENDOR | LibUsb.RECIPIENT_DEVICE,
                BladeRFUsbConstants.CMD_INVALIDATE_CAL_CACHE, (short)0, (short)0,
                BufferUtils.allocateByteBuffer(0));
    }

    /**
     * Refreshes the calibration cache by reading from SPI flash.
     */
    public void refreshCalibrationCache() throws SourceException
    {
        setInterface(BladeRFUsbConstants.USB_IF_SPI_FLASH);
        try
        {
            int result = readVendorInt(BladeRFUsbConstants.CMD_REFRESH_CAL_CACHE, (short)0, (short)0);
            if(result != 0)
            {
                throw new SourceException("bladeRF - calibration cache refresh failed (0x"
                        + Integer.toHexString(result) + ")");
            }
        }
        finally
        {
            setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
        }
    }

    /**
     * Enables or disables the FX3 firmware loopback mode.
     */
    public void setLoopbackEnabled(boolean enable) throws SourceException
    {
        int result = readVendorInt(BladeRFUsbConstants.CMD_SET_LOOPBACK, (short)(enable ? 1 : 0), (short)0);
        if(result != 0)
        {
            throw new SourceException("bladeRF - loopback " + (enable ? "enable" : "disable")
                    + " failed with status 0x" + Integer.toHexString(result));
        }

        // Toggling loopback forces the firmware to reconfigure the RF link interface.
        setInterface(BladeRFUsbConstants.USB_IF_NULL);
        setInterface(BladeRFUsbConstants.USB_IF_RF_LINK);
    }

    /**
     * Indicates if loopback mode is currently enabled.
     */
    public boolean isLoopbackEnabled() throws SourceException
    {
        int result = readVendorInt(BladeRFUsbConstants.CMD_GET_LOOPBACK, (short)0, (short)0);
        return result != 0;
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

    private void setInterface(int alternate) throws SourceException
    {
        int status = LibUsb.setInterfaceAltSetting(mHandle, BladeRFUsbConstants.USB_INTERFACE, alternate);
        if(status != LibUsb.SUCCESS)
        {
            throw new SourceException("bladeRF - unable to select USB interface " + alternate
                    + ": " + LibUsb.errorName(status));
        }
    }
}
