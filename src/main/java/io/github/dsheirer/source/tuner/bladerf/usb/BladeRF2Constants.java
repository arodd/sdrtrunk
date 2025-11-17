package io.github.dsheirer.source.tuner.bladerf.usb;

/**
 * bladeRF 2.0 specific constants extracted from bladerf2_common.h.
 */
public final class BladeRF2Constants
{
    private BladeRF2Constants()
    {
    }

    // NIOS target identifiers
    public static final byte NIOS_TARGET_VERSION = 0x00;
    public static final byte NIOS_TARGET_CONTROL = 0x01;
    public static final byte NIOS_TARGET_RFFE_CONTROL = 0x03;
    public static final byte NIOS_TARGET_FASTLOCK = 0x05;

    // NIOS 8x8 and 8x16 target identifiers
    public static final byte NIOS_PKT_8X8_TARGET_SI5338 = 0x01;
    public static final byte NIOS_PKT_8X16_TARGET_AD56X1_DAC = 0x03;
    public static final byte NIOS_PKT_8X16_TARGET_INA219 = 0x04;
    public static final byte NIOS_PKT_8X32_TARGET_ADF400X = 0x04;

    // Config GPIO bits relevant for streaming
    public static final int GPIO_TIMESTAMP = 1 << 16;
    public static final int GPIO_PACKET = 1 << 19;
    public static final int GPIO_8BIT_MODE = 1 << 20;
    public static final int GPIO_HIGHLY_PACKED_MODE = 1 << 21;
    public static final int GPIO_FEATURE_SMALL_DMA_XFER = 1 << 7;
    public static final int CONFIG_GPIO_PLL_ENABLE = 1 << 11;

    // RFFE control bit positions
    public static final int RFFE_CONTROL_RX_BIAS_EN = 5;
    public static final int RFFE_CONTROL_TX_BIAS_EN = 10;
    public static final int RFFE_CONTROL_RX_SPDT_1 = 6;
    public static final int RFFE_CONTROL_RX_SPDT_2 = 8;
    public static final int RFFE_CONTROL_TX_SPDT_1 = 11;
    public static final int RFFE_CONTROL_TX_SPDT_2 = 13;
    public static final int RFFE_CONTROL_SPDT_MASK = 0x3;
    public static final int RFFE_CONTROL_SPDT_SHUTDOWN = 0x0;
    public static final int RFFE_CONTROL_SPDT_LOWBAND = 0x2;
    public static final int RFFE_CONTROL_SPDT_HIGHBAND = 0x1;

    // Fastlock profile counts (matches bladerf2_common.h)
    public static final int NIOS_FASTLOCK_PROFILE_COUNT = 256;
    public static final int RFFE_FASTLOCK_PROFILE_COUNT = 8;

    // Retune (retune2) command constants
    public static final byte RETUNE_MAGIC = (byte)'U';
    public static final long RETUNE_CLEAR_QUEUE = -1L;
    public static final long RETUNE_NOW = 0L;
    public static final int RETUNE_FLAG_TIMESTAMP_VALID = 0x01;
    public static final int RETUNE_FLAG_SUCCESS = 0x02;
    public static final int RETUNE_PORT_IS_RX_MASK = 0x80;

    // RFIC initialization states (matches BLADERF_RFIC_INIT_STATE_*)
    public static final int RFIC_INIT_STATE_OFF = 0;
    public static final int RFIC_INIT_STATE_ON = 1;
    public static final int RFIC_INIT_STATE_STANDBY = 2;

    // RFIC status bit fields
    public static final int RFIC_STATUS_INIT_SHIFT = 0;
    public static final int RFIC_STATUS_INIT_MASK = 0x1;
    public static final int RFIC_STATUS_WQ_SUCCESS_SHIFT = 1;
    public static final int RFIC_STATUS_WQ_SUCCESS_MASK = 0x1;
    public static final int RFIC_STATUS_WQ_LENGTH_SHIFT = 8;
    public static final int RFIC_STATUS_WQ_LENGTH_MASK = 0xFF;

    // RFIC RSSI result bit fields
    public static final int RFIC_RSSI_MULT_SHIFT = 32;
    public static final int RFIC_RSSI_MULT_MASK = 0xFFFF;
    public static final int RFIC_RSSI_PRE_SHIFT = 16;
    public static final int RFIC_RSSI_PRE_MASK = 0xFFFF;
    public static final int RFIC_RSSI_SYM_SHIFT = 0;
    public static final int RFIC_RSSI_SYM_MASK = 0xFFFF;

    // AD936x port identifiers used when selecting quick tune bands
    public static final int AD936X_A_BALANCED = 0;
    public static final int AD936X_B_BALANCED = 1;
    public static final int AD936X_C_BALANCED = 2;
    public static final int AD936X_TXA = 0;
    public static final int AD936X_TXB = 1;

    // Clock tree constants
    public static final long VCTCXO_FREQUENCY = 38_400_000L;
    public static final long DEFAULT_REFCLK_FREQUENCY = 10_000_000L;
    public static final long PLL_REFCLK_MIN = 5_000_000L;
    public static final long PLL_REFCLK_MAX = 300_000_000L;
    public static final long PLL_CLOCK_MIN = 5_000_000L;
    public static final long PLL_CLOCK_MAX = 400_000_000L;
    public static final long SMB_FREQUENCY_MIN = 139_682L;
    public static final long SMB_FREQUENCY_MAX = 200_000_000L;
    public static final int TRIMDAC_MASK = 0x3FFC;
    public static final int TRIMDAC_EN_SHIFT = 14;
    public static final int TRIMDAC_EN_MASK = 0x3;
    public static final int TRIMDAC_EN_ACTIVE = 0x0;
    public static final int TRIMDAC_EN_HIGHZ = 0x3;
}
