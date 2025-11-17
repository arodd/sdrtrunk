package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the bladeRF 2 VCTCXO trim value. This mirrors the libbladeRF logic:
 * the stored calibration value is read from the SPI flash calibration region,
 * applied to the AD56x1 DAC, and exposed via read/write helpers.
 */
public class BladeRFVctcxoTrim
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRFVctcxoTrim.class);
    public static final int DEFAULT_TRIM = 0x1FFC;

    private final BladeRFUsbProtocol mUsbProtocol;
    private final BladeRFNiosAccess mNiosAccess;
    private Integer mStoredTrim;
    private boolean mStoredTrimLoaded;

    public BladeRFVctcxoTrim(BladeRFUsbProtocol usbProtocol, BladeRFNiosAccess niosAccess)
    {
        mUsbProtocol = usbProtocol;
        mNiosAccess = niosAccess;
    }

    /**
     * Applies the stored trim value by reading it from SPI flash (if needed) and
     * writing it to the DAC.
     */
    public void applyStoredTrim() throws SourceException
    {
        writeDacValue(getStoredTrim());
    }

    /**
     * Returns the cached trim value (reading and caching it from flash if required).
     */
    public int getStoredTrim() throws SourceException
    {
        synchronized(this)
        {
            if(!mStoredTrimLoaded)
            {
                mStoredTrim = readStoredTrimInternal();
                mStoredTrimLoaded = true;
            }
            return (mStoredTrim != null) ? mStoredTrim : DEFAULT_TRIM;
        }
    }

    /**
     * Reads the currently programmed DAC value.
     */
    public int readDacValue() throws SourceException
    {
        return mNiosAccess.readTrimDac();
    }

    /**
     * Writes a raw DAC value. Callers are responsible for providing the correct enable bits.
     */
    public void writeDacValue(int value) throws SourceException
    {
        mNiosAccess.writeTrimDac(value & 0xFFFF);
    }

    private int readStoredTrimInternal() throws SourceException
    {
        if(mUsbProtocol == null)
        {
            throw new SourceException("bladeRF calibration cache unavailable");
        }

        byte[] cache;
        try
        {
            cache = mUsbProtocol.readCalibrationCache();
        }
        catch(SourceException se)
        {
            LOG.warn("Unable to read calibration cache; defaulting trim to 0x{}", Integer.toHexString(DEFAULT_TRIM), se);
            return DEFAULT_TRIM;
        }

        String value;
        try
        {
            value = BladeRFBinkvParser.readField(cache, "DAC");
        }
        catch(SourceException se)
        {
            LOG.warn("Unable to decode calibration cache; defaulting trim to 0x{}", Integer.toHexString(DEFAULT_TRIM), se);
            return DEFAULT_TRIM;
        }
        if(value == null || value.isEmpty())
        {
            LOG.debug("SPI calibration cache missing DAC field; using default trim 0x{}", Integer.toHexString(DEFAULT_TRIM));
            return DEFAULT_TRIM;
        }

        try
        {
            return Integer.parseInt(value.trim());
        }
        catch(NumberFormatException nfe)
        {
            LOG.warn("Unable to parse VCTCXO trim '{}'; defaulting to 0x{}", value, Integer.toHexString(DEFAULT_TRIM));
            return DEFAULT_TRIM;
        }
    }
}
