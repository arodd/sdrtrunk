package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.bladerf.rfic.BladeRF2RficController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.DeviceHandle;

/**
 * Convenience wrapper for bladeRF 2 USB metadata and FPGA control helpers.
 * Encapsulates the FX3 vendor protocol, NIOS peripheral access, RFIC control plane,
 * and capability calculations so callers do not have to repeat the bootstrap sequence.
 */
public class BladeRF2Device
{
    private static final Logger mLog = LoggerFactory.getLogger(BladeRF2Device.class);

    private final BladeRFUsbDevice mUsbDevice;
    private final BladeRFUsbProtocol mUsbProtocol;
    private final BladeRFNiosAccess mNiosAccess;
    private final BladeRF2RficController mRficController;
    private final BladeRFIna219 mIna219;
    private final BladeRFSi5338 mSi5338;
    private final BladeRFVctcxoTrim mVctcxoTrim;
    private final BladeRFVersion mFpgaVersion;
    private final long mCapabilities;

    /**
     * Creates a BladeRF2Device wrapper for the specified USB device handle.
     *
     * @param usbDevice descriptor helper that exposes strings and firmware version metadata.
     * @param handle open usb4java device handle.
     */
    public BladeRF2Device(BladeRFUsbDevice usbDevice, DeviceHandle handle) throws SourceException
    {
        mUsbDevice = usbDevice;
        mUsbProtocol = new BladeRFUsbProtocol(handle);
        waitForFirmwareReady();
        mUsbProtocol.selectRfLinkInterface();

        mNiosAccess = new BladeRFNiosAccess(mUsbProtocol);

        BladeRFVersion fpgaVersion = null;
        try
        {
            fpgaVersion = mNiosAccess.readFpgaVersion();
            if(fpgaVersion != null)
            {
                mLog.debug("bladeRF fpga version {}", fpgaVersion);
            }
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to read bladeRF FPGA version", se);
        }
        mFpgaVersion = fpgaVersion;
        mCapabilities = BladeRFCapabilities.combined(getFirmwareVersion(), mFpgaVersion);
        BladeRF2Compatibility.validate(getFirmwareVersion(), mFpgaVersion);
        mRficController = new BladeRF2RficController(mNiosAccess);
        mIna219 = initializeIna219();
        mSi5338 = initializeSi5338();
        mVctcxoTrim = new BladeRFVctcxoTrim(mUsbProtocol, mNiosAccess);
    }

    private void waitForFirmwareReady() throws SourceException
    {
        final int maxAttempts = 30;
        SourceException lastError = null;

        for(int attempt = 0; attempt < maxAttempts; attempt++)
        {
            try
            {
                int ready = mUsbProtocol.readVendorInt(BladeRFUsbConstants.CMD_QUERY_DEVICE_READY, (short)0, (short)0);
                if(ready == 1)
                {
                    if(attempt > 0)
                    {
                        mLog.debug("bladeRF firmware became ready after {} attempts", attempt + 1);
                    }
                    return;
                }
            }
            catch(SourceException se)
            {
                lastError = se;
            }

            try
            {
                Thread.sleep(1_000L);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if(lastError != null)
        {
            throw new SourceException("bladeRF firmware did not become ready", lastError);
        }

        throw new SourceException("bladeRF firmware did not become ready");
    }

    public BladeRFUsbProtocol getUsbProtocol()
    {
        return mUsbProtocol;
    }

    public BladeRFNiosAccess getNiosAccess()
    {
        return mNiosAccess;
    }

    public BladeRF2RficController getRficController()
    {
        return mRficController;
    }

    public BladeRFIna219 getIna219()
    {
        return mIna219;
    }

    public BladeRFSi5338 getSi5338()
    {
        return mSi5338;
    }

    public BladeRFVctcxoTrim getVctcxoTrim()
    {
        return mVctcxoTrim;
    }

    public BladeRFVersion getFirmwareVersion()
    {
        return mUsbDevice.getFirmwareVersion();
    }

    public BladeRFVersion getFpgaVersion()
    {
        return mFpgaVersion;
    }

    public long getCapabilities()
    {
        return mCapabilities;
    }

    public String getSerialNumber()
    {
        return mUsbDevice.getSerialNumber();
    }

    public String getBoardName()
    {
        return mUsbDevice.getBoardName();
    }

    private BladeRFIna219 initializeIna219()
    {
        try
        {
            BladeRFIna219 ina219 = new BladeRFIna219(mNiosAccess);
            ina219.initialize();
            return ina219;
        }
        catch(SourceException se)
        {
            mLog.debug("Unable to initialize INA219 power monitor", se);
            return null;
        }
    }

    private BladeRFSi5338 initializeSi5338()
    {
        return new BladeRFSi5338(mNiosAccess);
    }
}
