package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.BufferUtils;

/**
 * Minimal NIOS access helper supporting the packet formats required for initialization.
 */
public class BladeRFNiosAccess
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRFNiosAccess.class);
    private static final byte MAGIC_8X8 = (byte)'A';
    private static final byte MAGIC_8X16 = (byte)'B';
    private static final byte MAGIC_8X32 = (byte)'C';
    private static final byte FLAG_WRITE = 0x01;
    private static final byte FLAG_SUCCESS = 0x02;
    private static final int PACKET_LENGTH = 16;
    private static final byte MAGIC_16X64 = (byte)'E';
    private static final byte RFIC_TARGET_ID = 0x01;

    private final BladeRFUsbProtocol mProtocol;

    public BladeRFNiosAccess(BladeRFUsbProtocol protocol)
    {
        mProtocol = protocol;
    }

    public BladeRFVersion readFpgaVersion() throws SourceException
    {
        int versionWord = read8x32(BladeRF2Constants.NIOS_TARGET_VERSION, (byte)0);
        int major = versionWord & 0xFF;
        int minor = (versionWord >> 8) & 0xFF;
        int patch = (versionWord >> 16) & 0xFFFF;
        return new BladeRFVersion(major, minor, patch);
    }

    /**
     * Reads the FPGA configuration GPIO register.
     */
    public int readConfigGpio() throws SourceException
    {
        return read8x32(BladeRF2Constants.NIOS_TARGET_CONTROL, (byte)0);
    }

    /**
     * Writes the FPGA configuration GPIO register.
     */
    public void writeConfigGpio(int value) throws SourceException
    {
        write8x32(BladeRF2Constants.NIOS_TARGET_CONTROL, (byte)0, value);
    }

    /**
     * Reads the RFFE control register.
     */
    public int readRffeControl() throws SourceException
    {
        return read8x32(BladeRF2Constants.NIOS_TARGET_RFFE_CONTROL, (byte)0);
    }

    /**
     * Writes the RFFE control register.
     */
    public void writeRffeControl(int value) throws SourceException
    {
        write8x32(BladeRF2Constants.NIOS_TARGET_RFFE_CONTROL, (byte)0, value);
    }

    /**
     * Reads a Si5338 register via the 8x8 NIOS packet path.
     */
    public byte readSi5338(int address) throws SourceException
    {
        return (byte)read8x8(BladeRF2Constants.NIOS_PKT_8X8_TARGET_SI5338, (byte)(address & 0xFF));
    }

    /**
     * Writes a Si5338 register via the 8x8 NIOS packet path.
     */
    public void writeSi5338(int address, int value) throws SourceException
    {
        write8x8(BladeRF2Constants.NIOS_PKT_8X8_TARGET_SI5338, (byte)(address & 0xFF), value & 0xFF);
    }

    /**
     * Reads a INA219 register via the 8x16 NIOS packet path.
     */
    public int readIna219(int address) throws SourceException
    {
        return read8x16(BladeRF2Constants.NIOS_PKT_8X16_TARGET_INA219, (byte)(address & 0xFF));
    }

    /**
     * Writes a INA219 register via the 8x16 NIOS packet path.
     */
    public void writeIna219(int address, int value) throws SourceException
    {
        write8x16(BladeRF2Constants.NIOS_PKT_8X16_TARGET_INA219, (byte)(address & 0xFF), value & 0xFFFF);
    }

    /**
     * Reads the AD56x1 VCTCXO trim DAC register.
     */
    public int readTrimDac() throws SourceException
    {
        return read8x16(BladeRF2Constants.NIOS_PKT_8X16_TARGET_AD56X1_DAC, (byte)0);
    }

    /**
     * Writes the AD56x1 VCTCXO trim DAC register.
     */
    public void writeTrimDac(int value) throws SourceException
    {
        write8x16(BladeRF2Constants.NIOS_PKT_8X16_TARGET_AD56X1_DAC, (byte)0, value & 0xFFFF);
    }

    /**
     * Reads an ADF400x register via the 8x32 packet path.
     */
    public int readAdf400x(int register) throws SourceException
    {
        byte address = (byte)(register & 0xFF);
        return read8x32(BladeRF2Constants.NIOS_PKT_8X32_TARGET_ADF400X, address);
    }

    /**
     * Writes an ADF400x register via the 8x32 packet path.
     */
    public void writeAdf400x(int register, int value) throws SourceException
    {
        int sanitized = value & ~0x3;
        sanitized |= (register & 0x3);
        write8x32(BladeRF2Constants.NIOS_PKT_8X32_TARGET_ADF400X, (byte)0, sanitized);
    }

    /**
     * Saves the currently staged RFIC fastlock profile into NIOS memory so it can be recalled later.
     *
     * @param transmit true if the profile belongs to a TX channel.
     * @param rffeProfile fastlock profile slot within the RFIC (0-7).
     * @param niosProfile backing profile index stored in the FPGA (0-255).
     */
    public void saveFastlockProfile(boolean transmit, int rffeProfile, int niosProfile) throws SourceException
    {
        int sanitizedNios = niosProfile & 0xFFFF;
        int sanitizedRffe = rffeProfile & 0xFF;
        int value = (sanitizedRffe << 16) | sanitizedNios;
        byte address = (byte)(transmit ? 1 : 0);
        write8x32(BladeRF2Constants.NIOS_TARGET_FASTLOCK, address, value);
    }

    /**
     * Reads a 64-bit RFIC command response for the provided command and channel.
     */
    public long readRficCommand(int channel, BladeRFRficCommand command) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        pack16x64(buffer, command, channel, 0, false);
        mProtocol.exchangePeripheralPacket(buffer);
        return unpack16x64(buffer);
    }

    /**
     * Issues a RFIC command write with the provided value.
     */
    public void writeRficCommand(int channel, BladeRFRficCommand command, long value) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        pack16x64(buffer, command, channel, value, true);
        mProtocol.exchangePeripheralPacket(buffer);
        unpack16x64(buffer);
    }

    /**
     * Issues a retune2 request to the FPGA so a previously captured quick-tune profile can be scheduled.
     *
     * @param channel bladeRF channel constant (matches BLADERF_CHANNEL_*).
     * @param timestamp timestamp for the operation (0 for immediate, -1 to clear queue).
     * @param niosProfile profile index stored in the FPGA memory.
     * @param rffeProfile fastlock slot inside the RFIC.
     * @param port RFIC port selection bits.
     * @param spdt External SPDT selection bits.
     */
    public Retune2Response retune2(int channel, long timestamp, int niosProfile, int rffeProfile, int port, int spdt)
            throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        packRetune2(buffer, channel, timestamp, niosProfile, rffeProfile, port, spdt);
        mProtocol.exchangePeripheralPacket(buffer);
        Retune2Response response = unpackRetune2(buffer);
        if(!response.isSuccess())
        {
            if(timestamp == BladeRF2Constants.RETUNE_NOW)
            {
                throw new SourceException("bladeRF - retune operation failed");
            }
            throw new SourceException("bladeRF - retune queue is full");
        }
        return response;
    }

    private ByteBuffer allocatePacket()
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(PACKET_LENGTH);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.clear();
        return buffer;
    }

    private void clearPacket(ByteBuffer buffer)
    {
        for(int i = 0; i < PACKET_LENGTH; i++)
        {
            buffer.put(i, (byte)0);
        }
    }

    private int read8x32(byte target, byte address) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);

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
            logNiosFailure("read8x32", target, address);
            throw new SourceException("NIOS read failed for target " + target);
        }

        int b0 = Byte.toUnsignedInt(buffer.get(5));
        int b1 = Byte.toUnsignedInt(buffer.get(6));
        int b2 = Byte.toUnsignedInt(buffer.get(7));
        int b3 = Byte.toUnsignedInt(buffer.get(8));

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private void write8x32(byte target, byte address, int value) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);

        buffer.put(0, MAGIC_8X32);
        buffer.put(1, target);
        buffer.put(2, FLAG_WRITE);
        buffer.put(3, (byte)0);
        buffer.put(4, address);
        buffer.putInt(5, value);
        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X32)
        {
            throw new SourceException("Unexpected NIOS packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            logNiosFailure("write8x32", target, address);
            throw new SourceException("NIOS write failed for target " + target);
        }
    }

    private int read8x8(byte target, byte address) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);

        buffer.put(0, MAGIC_8X8);
        buffer.put(1, target);
        buffer.put(2, (byte)0);
        buffer.put(3, (byte)0);
        buffer.put(4, address);
        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X8)
        {
            throw new SourceException("Unexpected NIOS 8x8 packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            logNiosFailure("read8x8", target, address);
            throw new SourceException("NIOS 8x8 read failed for target " + target);
        }

        return Byte.toUnsignedInt(buffer.get(5));
    }

    private void write8x8(byte target, byte address, int value) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);

        buffer.put(0, MAGIC_8X8);
        buffer.put(1, target);
        buffer.put(2, FLAG_WRITE);
        buffer.put(3, (byte)0);
        buffer.put(4, address);
        buffer.put(5, (byte)(value & 0xFF));
        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X8)
        {
            throw new SourceException("Unexpected NIOS 8x8 packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            logNiosFailure("write8x8", target, address);
            throw new SourceException("NIOS 8x8 write failed for target " + target);
        }
    }

    private int read8x16(byte target, byte address) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);
        buffer.put(0, MAGIC_8X16);
        buffer.put(1, target);
        buffer.put(2, (byte)0);
        buffer.put(3, (byte)0);
        buffer.put(4, address);
        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X16)
        {
            throw new SourceException("Unexpected NIOS 8x16 packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            logNiosFailure("read8x16", target, address);
            throw new SourceException("NIOS 8x16 read failed for target " + target);
        }

        int b0 = Byte.toUnsignedInt(buffer.get(5));
        int b1 = Byte.toUnsignedInt(buffer.get(6));
        return b0 | (b1 << 8);
    }

    private void write8x16(byte target, byte address, int value) throws SourceException
    {
        ByteBuffer buffer = allocatePacket();
        clearPacket(buffer);
        buffer.put(0, MAGIC_8X16);
        buffer.put(1, target);
        buffer.put(2, FLAG_WRITE);
        buffer.put(3, (byte)0);
        buffer.put(4, address);
        buffer.putShort(5, (short)(value & 0xFFFF));
        buffer.rewind();
        mProtocol.exchangePeripheralPacket(buffer);

        if(buffer.get(0) != MAGIC_8X16)
        {
            throw new SourceException("Unexpected NIOS 8x16 packet header");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            logNiosFailure("write8x16", target, address);
            throw new SourceException("NIOS 8x16 write failed for target " + target);
        }
    }

    private void pack16x64(ByteBuffer buffer, BladeRFRficCommand command, int channel, long value, boolean write)
    {
        buffer.clear();
        buffer.put(0, MAGIC_16X64);
        buffer.put(1, RFIC_TARGET_ID);
        buffer.put(2, write ? FLAG_WRITE : 0);
        buffer.put(3, (byte)0);
        short address = encodeAddress(command, channel);
        buffer.putShort(4, address);
        buffer.putLong(6, value);
        buffer.putShort(14, (short)0);
        buffer.rewind();
    }

    private short encodeAddress(BladeRFRficCommand command, int channel)
    {
        int channelNibble = (channel < 0) ? 0x0F : (channel & 0x0F);
        return (short)((channelNibble << 8) | (command.getValue() & 0xFF));
    }

    private long unpack16x64(ByteBuffer buffer) throws SourceException
    {
        buffer.rewind();
        byte magic = buffer.get(0);
        if(magic != MAGIC_16X64)
        {
            throw new SourceException("Unexpected response from bladeRF RFIC command");
        }

        byte flags = buffer.get(2);
        if((flags & FLAG_SUCCESS) == 0)
        {
            int address = Short.toUnsignedInt(buffer.getShort(4));
            int command = address & 0xFF;
            int channel = (address >> 8) & 0x0F;
            throw new SourceException("bladeRF RFIC command failed (cmd=0x" + Integer.toHexString(command)
                    + ", channel=" + channel + ", flags=0x" + Integer.toHexString(flags & 0xFF) + ")");
        }

        return buffer.getLong(6);
    }

    private void logNiosFailure(String operation, byte target, byte address)
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("NIOS {} failure (target=0x{}, address=0x{})", operation,
                    Integer.toHexString(Byte.toUnsignedInt(target)), Integer.toHexString(Byte.toUnsignedInt(address)));
        }
    }

    private void packRetune2(ByteBuffer buffer, int channel, long timestamp, int niosProfile, int rffeProfile, int port,
                             int spdt)
    {
        buffer.clear();
        buffer.put(0, BladeRF2Constants.RETUNE_MAGIC);
        for(int offset = 0; offset < Long.BYTES; offset++)
        {
            buffer.put(1 + offset, (byte)((timestamp >> (8 * offset)) & 0xFF));
        }
        buffer.putShort(9, (short)(niosProfile & 0xFFFF));
        buffer.put(11, (byte)(rffeProfile & 0xFF));
        buffer.put(12, (byte)applyModulePort(channel, port));
        buffer.put(13, (byte)(spdt & 0xFF));
        buffer.put(14, (byte)0);
        buffer.put(15, (byte)0);
        buffer.rewind();
    }

    private int applyModulePort(int channel, int port)
    {
        int sanitized = port & ~BladeRF2Constants.RETUNE_PORT_IS_RX_MASK;
        if(!isTxChannel(channel))
        {
            sanitized |= BladeRF2Constants.RETUNE_PORT_IS_RX_MASK;
        }
        return sanitized & 0xFF;
    }

    private boolean isTxChannel(int channel)
    {
        return (channel & 0x01) == 1;
    }

    private Retune2Response unpackRetune2(ByteBuffer buffer) throws SourceException
    {
        buffer.rewind();
        byte magic = buffer.get();
        if(magic != BladeRF2Constants.RETUNE_MAGIC)
        {
            throw new SourceException("Unexpected response from bladeRF retune command");
        }

        long duration = 0;
        for(int offset = 0; offset < Long.BYTES; offset++)
        {
            duration |= (Byte.toUnsignedLong(buffer.get())) << (8 * offset);
        }

        int flags = Byte.toUnsignedInt(buffer.get());
        return new Retune2Response(duration,
                (flags & BladeRF2Constants.RETUNE_FLAG_TIMESTAMP_VALID) != 0,
                (flags & BladeRF2Constants.RETUNE_FLAG_SUCCESS) != 0);
    }

    /**
     * Response metadata for retune2 commands.
     */
    public static class Retune2Response
    {
        private final long mDuration;
        private final boolean mTimestampValid;
        private final boolean mSuccess;

        Retune2Response(long duration, boolean timestampValid, boolean success)
        {
            mDuration = duration;
            mTimestampValid = timestampValid;
            mSuccess = success;
        }

        public long getDuration()
        {
            return mDuration;
        }

        public boolean isTimestampValid()
        {
            return mTimestampValid;
        }

        public boolean isSuccess()
        {
            return mSuccess;
        }
    }
}
