package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.ByteBuffer;
import org.usb4java.BufferUtils;

/**
 * Minimal NIOS access helper supporting the packet formats required for initialization.
 */
public class BladeRFNiosAccess
{
    private static final byte MAGIC_8X32 = (byte)'C';
    private static final byte FLAG_WRITE = 0x01;
    private static final byte FLAG_SUCCESS = 0x02;
    private static final int PACKET_LENGTH = 16;
    private static final byte TARGET_VERSION = 0x00;

    private final BladeRFUsbProtocol mProtocol;

    public BladeRFNiosAccess(BladeRFUsbProtocol protocol)
    {
        mProtocol = protocol;
    }

    public BladeRFVersion readFpgaVersion() throws SourceException
    {
        int versionWord = read8x32(TARGET_VERSION, (byte)0);
        int major = (versionWord >> 24) & 0xFF;
        int minor = (versionWord >> 16) & 0xFF;
        int patch = versionWord & 0xFFFF;
        return new BladeRFVersion(major, minor, patch);
    }

    private int read8x32(byte target, byte address) throws SourceException
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(PACKET_LENGTH);
        for(int i = 0; i < PACKET_LENGTH; i++)
        {
            buffer.put(i, (byte)0);
        }

        buffer.put(0, MAGIC_8X32);
        buffer.put(1, target);
        buffer.put(2, (byte)0); // read operation
        buffer.put(3, (byte)0);
        buffer.put(4, address);

        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X32)
        {
            throw new SourceException("Unexpected NIOS packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            throw new SourceException("NIOS read failed for target " + target);
        }

        int b0 = Byte.toUnsignedInt(buffer.get(5));
        int b1 = Byte.toUnsignedInt(buffer.get(6));
        int b2 = Byte.toUnsignedInt(buffer.get(7));
        int b3 = Byte.toUnsignedInt(buffer.get(8));

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}
