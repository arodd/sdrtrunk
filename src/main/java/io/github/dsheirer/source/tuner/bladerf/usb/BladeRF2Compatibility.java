package io.github.dsheirer.source.tuner.bladerf.usb;

import io.github.dsheirer.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firmware/FPGA compatibility helper mirroring libbladeRF's tables.
 * Ensures the connected bladeRF 2 uses a supported firmware/FPGA combination.
 */
public final class BladeRF2Compatibility
{
    private static final Logger LOG = LoggerFactory.getLogger(BladeRF2Compatibility.class);

    private static final CompatEntry[] FW_COMPAT = new CompatEntry[]
            {
                    new CompatEntry(version(2, 6, 0), version(0, 16, 0)),
                    new CompatEntry(version(2, 5, 0), version(0, 16, 0)),
                    new CompatEntry(version(2, 4, 0), version(0, 6, 0)),
                    new CompatEntry(version(2, 3, 2), version(0, 6, 0)),
                    new CompatEntry(version(2, 3, 1), version(0, 6, 0)),
                    new CompatEntry(version(2, 3, 0), version(0, 6, 0)),
                    new CompatEntry(version(2, 2, 0), version(0, 6, 0)),
                    new CompatEntry(version(2, 1, 1), version(0, 6, 0)),
                    new CompatEntry(version(2, 1, 0), version(0, 6, 0)),
                    new CompatEntry(version(2, 0, 0), version(0, 6, 0))
            };

    private static final CompatEntry[] FPGA_COMPAT = new CompatEntry[]
            {
                    new CompatEntry(version(0, 16, 0), version(2, 6, 0)),
                    new CompatEntry(version(0, 15, 3), version(2, 4, 0)),
                    new CompatEntry(version(0, 15, 2), version(2, 4, 0)),
                    new CompatEntry(version(0, 15, 1), version(2, 4, 0)),
                    new CompatEntry(version(0, 15, 0), version(2, 4, 0)),
                    new CompatEntry(version(0, 14, 0), version(2, 4, 0)),
                    new CompatEntry(version(0, 12, 0), version(2, 2, 0)),
                    new CompatEntry(version(0, 11, 1), version(2, 1, 0)),
                    new CompatEntry(version(0, 11, 0), version(2, 1, 0)),
                    new CompatEntry(version(0, 10, 2), version(2, 1, 0)),
                    new CompatEntry(version(0, 10, 1), version(2, 1, 0)),
                    new CompatEntry(version(0, 10, 0), version(2, 1, 0)),
                    new CompatEntry(version(0, 9, 0), version(2, 1, 0)),
                    new CompatEntry(version(0, 8, 0), version(2, 1, 0)),
                    new CompatEntry(version(0, 7, 3), version(2, 1, 0)),
                    new CompatEntry(version(0, 7, 2), version(2, 1, 0)),
                    new CompatEntry(version(0, 7, 1), version(2, 0, 0)),
                    new CompatEntry(version(0, 7, 0), version(2, 0, 0)),
                    new CompatEntry(version(0, 6, 0), version(2, 0, 0))
            };

    private BladeRF2Compatibility()
    {
    }

    /**
     * Validates the firmware/FPGA pair and throws if an update is required.
     */
    public static void validate(BladeRFVersion firmware, BladeRFVersion fpga) throws SourceException
    {
        if(firmware == null || fpga == null)
        {
            // Without both versions we cannot reason about compatibility.
            return;
        }

        CompatEntry fwEntry = findEntry(FW_COMPAT, firmware, true);
        CompatEntry fpgaEntry = findEntry(FPGA_COMPAT, fpga, false);

        if(fwEntry == null)
        {
            throw new SourceException("bladeRF - firmware v" + firmware + " is not supported by this build");
        }

        if(fpgaEntry == null)
        {
            throw new SourceException("bladeRF - FPGA v" + fpga + " is not supported by this build");
        }

        if(fpga.compareTo(fwEntry.getRequired()) < 0)
        {
            throw new SourceException("bladeRF firmware v" + firmware + " requires FPGA v"
                    + fwEntry.getRequired() + " or newer (detected v" + fpga + ")");
        }

        if(firmware.compareTo(fpgaEntry.getRequired()) < 0)
        {
            throw new SourceException("bladeRF FPGA v" + fpga + " requires firmware v"
                    + fpgaEntry.getRequired() + " or newer (detected v" + firmware + ")");
        }
    }

    private static CompatEntry findEntry(CompatEntry[] table, BladeRFVersion version, boolean firmware)
    {
        CompatEntry newest = table[0];

        if(version.compareTo(newest.getVersion()) > 0)
        {
            LOG.info("{} version {} is newer than the compatibility table; assuming requirements for {}",
                    firmware ? "Firmware" : "FPGA", version, newest.getVersion());
            return newest;
        }

        for(CompatEntry entry: table)
        {
            if(entry.getVersion().compareTo(version) == 0)
            {
                return entry;
            }
        }

        return null;
    }

    private static BladeRFVersion version(int major, int minor, int patch)
    {
        return new BladeRFVersion(major, minor, patch);
    }

    private static final class CompatEntry
    {
        private final BladeRFVersion mVersion;
        private final BladeRFVersion mRequired;

        private CompatEntry(BladeRFVersion version, BladeRFVersion required)
        {
            mVersion = version;
            mRequired = required;
        }

        public BladeRFVersion getVersion()
        {
            return mVersion;
        }

        public BladeRFVersion getRequired()
        {
            return mRequired;
        }
    }
}
