/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.bladerf;

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;

/**
 * bladeRF tuner wrapper.
 */
public class BladeRFTuner extends Tuner
{
    public BladeRFTuner(BladeRFTunerController controller, ITunerErrorListener tunerErrorListener,
                        ChannelizerType channelizerType)
    {
        super(controller, tunerErrorListener, channelizerType);
    }

    @Override
    public String getPreferredName()
    {
        String serial = getController().getSerialNumber();
        String board = getController().getBoardName();

        if(serial != null && !serial.isEmpty())
        {
            return board + " " + serial;
        }

        return board;
    }

    public BladeRFTunerController getController()
    {
        return (BladeRFTunerController)getTunerController();
    }

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.BLADE_RF;
    }

    @Override
    public double getSampleSize()
    {
        return 16.0;
    }

    @Override
    public String getUniqueID()
    {
        String serial = getController().getSerialNumber();
        if(serial != null && !serial.isEmpty())
        {
            return getTunerClass() + " " + serial;
        }

        return getTunerClass().toString();
    }

    @Override
    public int getMaximumUSBBitsPerSecond()
    {
        return 30_720_000 * 32;
    }
}
