package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Capability bitmask helper mirroring libbladeRF's board capability tracking.
 */
public final class BladeRFCapabilities
{
    private BladeRFCapabilities()
    {
    }

    public static final long CAP_TIMESTAMPS = 1L << 2;
    public static final long CAP_SCHEDULED_RETUNE = 1L << 3;
    public static final long CAP_PKT_HANDLER_FMT = 1L << 4;
    public static final long CAP_VCTCXO_TRIMDAC_READ = 1L << 5;
    public static final long CAP_MASKED_XBIO_WRITE = 1L << 7;
    public static final long CAP_TRX_SYNC_TRIG = 1L << 9;
    public static final long CAP_FPGA_TUNING = 1L << 11;
    public static final long CAP_FPGA_PACKET_META = 1L << 12;
    public static final long CAP_FPGA_8BIT_SAMPLES = 1L << 39;

    public static final long CAP_FW_LOOPBACK = 1L << 32;
    public static final long CAP_QUERY_DEVICE_READY = 1L << 33;
    public static final long CAP_READ_FW_LOG_ENTRY = 1L << 34;
    public static final long CAP_FW_SUPPORTS_BLADERF2 = 1L << 35;
    public static final long CAP_FW_FLASH_ID = 1L << 36;
    public static final long CAP_FW_FPGA_SOURCE = 1L << 37;
    public static final long CAP_FW_SHORT_PACKET = 1L << 38;

    public static long fromFirmware(BladeRFVersion version)
    {
        if(version == null)
        {
            return 0;
        }

        long caps = 0;

        if(version.atLeast(1, 7, 1))
        {
            caps |= CAP_FW_LOOPBACK;
        }
        if(version.atLeast(1, 8, 0))
        {
            caps |= CAP_QUERY_DEVICE_READY;
        }
        if(version.atLeast(1, 9, 0))
        {
            caps |= CAP_READ_FW_LOG_ENTRY;
        }
        if(version.atLeast(2, 1, 0))
        {
            caps |= CAP_FW_SUPPORTS_BLADERF2;
        }
        if(version.atLeast(2, 3, 0))
        {
            caps |= CAP_FW_FLASH_ID;
        }
        if(version.atLeast(2, 3, 1))
        {
            caps |= CAP_FW_FPGA_SOURCE;
        }
        if(version.atLeast(2, 4, 0))
        {
            caps |= CAP_FW_SHORT_PACKET;
        }

        return caps;
    }

    public static long fromFpga(BladeRFVersion version)
    {
        if(version == null)
        {
            return 0;
        }

        long caps = 0;

        if(version.atLeast(0, 1, 0))
        {
            caps |= CAP_TIMESTAMPS;
        }
        if(version.atLeast(0, 3, 0))
        {
            caps |= CAP_PKT_HANDLER_FMT;
        }
        if(version.atLeast(0, 3, 2))
        {
            caps |= CAP_VCTCXO_TRIMDAC_READ;
        }
        if(version.atLeast(0, 4, 1))
        {
            caps |= CAP_MASKED_XBIO_WRITE;
        }
        if(version.atLeast(0, 6, 0))
        {
            caps |= CAP_TRX_SYNC_TRIG;
        }
        if(version.atLeast(0, 10, 0))
        {
            caps |= CAP_SCHEDULED_RETUNE;
        }
        if(version.atLeast(0, 10, 1))
        {
            caps |= CAP_FPGA_TUNING;
        }
        if(version.atLeast(0, 12, 0))
        {
            caps |= CAP_FPGA_PACKET_META;
        }
        if(version.atLeast(0, 15, 0))
        {
            caps |= CAP_FPGA_8BIT_SAMPLES;
        }

        return caps;
    }

    public static long combined(BladeRFVersion firmware, BladeRFVersion fpga)
    {
        return fromFirmware(firmware) | fromFpga(fpga);
    }
}
