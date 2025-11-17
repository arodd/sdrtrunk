package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal parser for the bladeRF calibration (BINKV) format.
 * Provides helpers to extract ASCII fields from the binary key/value block
 * stored in SPI flash.
 */
public final class BladeRFBinkvParser
{
    private BladeRFBinkvParser()
    {
    }

    /**
     * Reads the requested field from the calibration block.
     *
     * @param data calibration bytes.
     * @param field ASCII field name to extract.
     * @return field value as a string or {@code null} if not present.
     */
    public static String readField(byte[] data, String field) throws SourceException
    {
        if(data == null || data.length == 0 || field == null || field.isEmpty())
        {
            return null;
        }

        byte[] key = field.getBytes(StandardCharsets.US_ASCII);
        int offset = 0;

        while(offset < data.length)
        {
            int length = Byte.toUnsignedInt(data[offset]);
            if(length == 0 || length == 0xFF)
            {
                break;
            }

            int recordLength = 1 + length + 2;
            if(offset + recordLength > data.length)
            {
                throw new SourceException("bladeRF calibration cache truncated");
            }

            int storedCrc = Byte.toUnsignedInt(data[offset + 1 + length])
                    | (Byte.toUnsignedInt(data[offset + 2 + length]) << 8);
            int computed = computeCrc(data, offset, length + 1);
            if(storedCrc != computed)
            {
                throw new SourceException("bladeRF calibration cache checksum mismatch");
            }

            if(length >= key.length)
            {
                boolean match = true;
                for(int i = 0; i < key.length; i++)
                {
                    if(data[offset + 1 + i] != key[i])
                    {
                        match = false;
                        break;
                    }
                }

                if(match)
                {
                    int valueLength = length - key.length;
                    if(valueLength <= 0)
                    {
                        return "";
                    }
                    return new String(data, offset + 1 + key.length, valueLength, StandardCharsets.US_ASCII).trim();
                }
            }

            offset += recordLength;
        }

        return null;
    }

    private static int computeCrc(byte[] data, int offset, int length)
    {
        int crc = 0;

        for(int i = 0; i < length; i++)
        {
            crc ^= (Byte.toUnsignedInt(data[offset + i]) << 8);
            for(int b = 0; b < 8; b++)
            {
                boolean msb = (crc & 0x8000) != 0;
                crc = (crc << 1) & 0xFFFF;
                if(msb)
                {
                    crc ^= 0x1021;
                }
            }
        }

        return crc & 0xFFFF;
    }
}
