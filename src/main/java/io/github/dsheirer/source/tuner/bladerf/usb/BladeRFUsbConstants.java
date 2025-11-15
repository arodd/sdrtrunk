package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * Constants extracted from the bladeRF firmware interface. These mirror the definitions in
 * firmware_common/bladeRF.h so that the Java implementation can interact with the FX3 firmware directly.
 */
public final class BladeRFUsbConstants
{
    private BladeRFUsbConstants()
    {
    }

    // USB endpoint assignments
    public static final byte SAMPLE_ENDPOINT_IN = (byte)0x81;
    public static final byte SAMPLE_ENDPOINT_OUT = 0x01;
    public static final byte PERIPHERAL_ENDPOINT_IN = (byte)0x82;
    public static final byte PERIPHERAL_ENDPOINT_OUT = 0x02;

    public static final int CONTROL_TIMEOUT_MS = 1_000;
    public static final int PERIPHERAL_TIMEOUT_MS = 250;
    public static final short DEFAULT_LANG_ID = (short)0x0409;
    public static final byte STRING_INDEX_FIRMWARE_VERSION = 4;

    // Vendor command identifiers
    public static final byte CMD_QUERY_VERSION = 0;
    public static final byte CMD_QUERY_FPGA_STATUS = 1;
    public static final byte CMD_BEGIN_PROGRAM = 2;
    public static final byte CMD_END_PROGRAM = 3;
    public static final byte CMD_RF_RX = 4;
    public static final byte CMD_RF_TX = 5;
    public static final byte CMD_QUERY_DEVICE_READY = 6;
    public static final byte CMD_QUERY_FLASH_ID = 7;
    public static final byte CMD_QUERY_FPGA_SOURCE = 8;
    public static final byte CMD_FLASH_READ = 100;
    public static final byte CMD_FLASH_WRITE = 101;
    public static final byte CMD_FLASH_ERASE = 102;
    public static final byte CMD_READ_OTP = 103;
    public static final byte CMD_WRITE_OTP = 104;
    public static final byte CMD_RESET = 105;
    public static final byte CMD_JUMP_TO_BOOTLOADER = 106;
    public static final byte CMD_READ_PAGE_BUFFER = 107;
    public static final byte CMD_WRITE_PAGE_BUFFER = 108;
    public static final byte CMD_LOCK_OTP = 109;
    public static final byte CMD_READ_CAL_CACHE = 110;
    public static final byte CMD_INVALIDATE_CAL_CACHE = 111;
    public static final byte CMD_REFRESH_CAL_CACHE = 112;
    public static final byte CMD_SET_LOOPBACK = 113;
    public static final byte CMD_GET_LOOPBACK = 114;
    public static final byte CMD_READ_LOG_ENTRY = 115;
}
