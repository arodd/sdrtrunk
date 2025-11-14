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

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.bladerf.BladeRFTunerController.GainModeInfo;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import java.util.List;
import java.util.Locale;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor for bladeRF tuners.
 */
public class BladeRFTunerEditor extends TunerEditor<BladeRFTuner, BladeRFTunerConfiguration>
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(BladeRFTunerEditor.class);
    private JComboBox<SampleRateItem> mSampleRateCombo;
    private JComboBox<BandwidthItem> mBandwidthCombo;
    private JComboBox<GainModeItem> mGainModeCombo;
    private JSpinner mGainSpinner;
    private JComboBox<ChannelItem> mChannelCombo;
    private JLabel mChannelLabel;
    private JCheckBox mBiasTCheckbox;
    private boolean mOptionsInitialized = false;
    private boolean mUpdatingControls = false;

    public BladeRFTunerEditor(UserPreferences userPreferences, TunerManager tunerManager, DiscoveredTuner discoveredTuner)
    {
        super(userPreferences, tunerManager, discoveredTuner);
        init();
        tunerStatusUpdated();
    }

    private void init()
    {
        setLayout(new MigLayout("fill,wrap 3", "[right][grow,fill][fill]", "[][][][][][][][grow]"));

        add(new JLabel("Tuner:"));
        add(getTunerIdLabel());
        add(new JLabel());

        add(new JLabel("Status:"));
        add(getTunerStatusLabel(), "wrap");

        add(getButtonPanel(), "span,align left");

        add(new JSeparator(), "span,growx,push");

        add(new JLabel("Frequency (MHz):"));
        add(getFrequencyPanel(), "wrap");

        add(new JLabel("Sample Rate:"));
        add(getSampleRateCombo(), "wrap");

        add(getChannelLabel());
        add(getChannelCombo(), "wrap");

        add(new JLabel("Bandwidth:"));
        add(getBandwidthCombo(), "wrap");

        add(new JLabel("Gain Mode:"));
        add(getGainModeCombo(), "wrap");

        add(new JLabel("Bias-T:"));
        add(getBiasTCheckbox(), "wrap");

        add(new JLabel("Overall Gain (dB):"));
        add(getGainSpinner(), "wrap");
    }

    @Override
    public long getMinimumTunableFrequency()
    {
        return BladeRFTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
    }

    @Override
    public long getMaximumTunableFrequency()
    {
        return BladeRFTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
    }

    @Override
    protected void tunerStatusUpdated()
    {
        setLoading(true);

        if(hasTuner())
        {
            getTunerIdLabel().setText(getTuner().getPreferredName());
        }
        else
        {
            getTunerIdLabel().setText(getDiscoveredTuner().getId());
        }

        String status = getDiscoveredTuner().getTunerStatus().toString();
        if(getDiscoveredTuner().hasErrorMessage())
        {
            status += " - " + getDiscoveredTuner().getErrorMessage();
        }

        getTunerStatusLabel().setText(status);
        getButtonPanel().updateControls();
        getFrequencyPanel().updateControls();

        boolean available = hasTuner();
        getSampleRateCombo().setEnabled(available && !getTuner().getTunerController().isLockedSampleRate());
        getBandwidthCombo().setEnabled(available);
        getGainModeCombo().setEnabled(available);
        getGainSpinner().setEnabled(available && getTuner().getController().isManualGainModeSelected());
        getBiasTCheckbox().setEnabled(available && getTuner().getController().isBiasTSupported());

        if(available)
        {
            if(!mOptionsInitialized)
            {
                initializeOptions();
            }
            else
            {
                updateSelections();
            }
        }
        else
        {
            mOptionsInitialized = false;
        }

        setLoading(false);
    }

    private void initializeOptions()
    {
        BladeRFTunerController controller = getTuner().getController();

        mUpdatingControls = true;
        try
        {
        getChannelCombo().setModel(new DefaultComboBoxModel<>());
        if(controller.getChannelCount() > 1)
        {
            populateChannels(controller.getChannelCount(), controller.getConfiguredChannelId());
            getChannelLabel().setVisible(true);
            getChannelCombo().setVisible(true);
        }
        else
        {
            getChannelLabel().setVisible(false);
            getChannelCombo().setVisible(false);
        }
            populateSampleRates(controller.getSupportedSampleRates(), controller.getConfiguredSampleRate());
            populateBandwidths(controller.getSupportedBandwidths(), controller.getConfiguredBandwidth());
            populateGainModes(controller.getSupportedGainModes(), controller.getConfiguredGainModeName());
            getBiasTCheckbox().setSelected(controller.isBiasTEnabled());

            int min = controller.getMinimumGain();
            int max = controller.getMaximumGain();
            int current = controller.getConfiguredOverallGain();
            SpinnerNumberModel model = (SpinnerNumberModel)getGainSpinner().getModel();
            model.setMinimum(min);
            model.setMaximum(max);
            model.setValue(Math.max(min, Math.min(max, current)));
            getGainSpinner().setEnabled(controller.isManualGainModeSelected());
            boolean multiple = controller.getChannelCount() > 1;
            getChannelLabel().setVisible(multiple);
            getChannelCombo().setVisible(multiple);
            mOptionsInitialized = true;
        }
        finally
        {
            mUpdatingControls = false;
        }
    }

    private void updateSelections()
    {
        BladeRFTunerController controller = getTuner().getController();
        boolean multiple = controller.getChannelCount() > 1;
        getChannelLabel().setVisible(multiple);
        getChannelCombo().setVisible(multiple);
        getBiasTCheckbox().setSelected(controller.isBiasTEnabled());

        if(multiple)
        {
            selectChannel(controller.getConfiguredChannelId());
        }
        setSampleRateSelection(controller.getConfiguredSampleRate());
        setBandwidthSelection(controller.getConfiguredBandwidth());
        setGainModeSelection(controller.getConfiguredGainModeName());

        SpinnerNumberModel model = (SpinnerNumberModel)getGainSpinner().getModel();
        int min = controller.getMinimumGain();
        int max = controller.getMaximumGain();
        model.setMinimum(min);
        model.setMaximum(max);
        model.setValue(Math.max(min, Math.min(max, controller.getConfiguredOverallGain())));
        getGainSpinner().setEnabled(controller.isManualGainModeSelected());
    }

    private void populateSampleRates(List<Integer> rates, int selectedRate)
    {
        SampleRateItem[] items = rates.stream()
                .map(rate -> new SampleRateItem(rate, formatRateLabel(rate)))
                .toArray(SampleRateItem[]::new);
        SampleRateItem selection = findSampleRate(items, selectedRate);
        getSampleRateCombo().setModel(new DefaultComboBoxModel<>(items));
        if(selection != null)
        {
            getSampleRateCombo().setSelectedItem(selection);
        }
    }

    private void populateBandwidths(List<Long> bandwidths, long selectedBandwidth)
    {
        BandwidthItem[] items = bandwidths.stream()
                .map(bandwidth -> new BandwidthItem(bandwidth,
                        bandwidth == BladeRFTunerController.BANDWIDTH_AUTO ? "Auto" : formatRateLabel(bandwidth)))
                .toArray(BandwidthItem[]::new);
        DefaultComboBoxModel<BandwidthItem> model = new DefaultComboBoxModel<>(items);
        BandwidthItem selection = findBandwidth(items, selectedBandwidth);
        getBandwidthCombo().setModel(model);
        getBandwidthCombo().setSelectedItem(selection);
    }

    private void populateGainModes(List<GainModeInfo> modes, String selectedName)
    {
        GainModeItem[] items = modes.stream().map(GainModeItem::new).toArray(GainModeItem[]::new);
        DefaultComboBoxModel<GainModeItem> model = new DefaultComboBoxModel<>(items);
        GainModeItem selection = findGainMode(items, selectedName);
        getGainModeCombo().setModel(model);
        getGainModeCombo().setSelectedItem(selection);
    }

    private void populateChannels(int channelCount, int selectedChannel)
    {
        DefaultComboBoxModel<ChannelItem> model = new DefaultComboBoxModel<>();
        ChannelItem selection = null;

        for(int i = 0; i < channelCount; i++)
        {
            ChannelItem item = new ChannelItem(i);
            model.addElement(item);

            if(i == selectedChannel)
            {
                selection = item;
            }
        }

        getChannelCombo().setModel(model);

        if(selection != null)
        {
            getChannelCombo().setSelectedItem(selection);
        }
        else if(model.getSize() > 0)
        {
            getChannelCombo().setSelectedIndex(0);
        }
    }

    private void setSampleRateSelection(int rate)
    {
        JComboBox<SampleRateItem> combo = getSampleRateCombo();
        DefaultComboBoxModel<SampleRateItem> model = (DefaultComboBoxModel<SampleRateItem>)combo.getModel();

        for(int i = 0; i < model.getSize(); i++)
        {
            SampleRateItem item = model.getElementAt(i);
            if(Math.abs(item.rate() - rate) < 1_000)
            {
                combo.setSelectedItem(item);
                return;
            }
        }

        if(model.getSize() > 0)
        {
            combo.setSelectedIndex(0);
        }
    }

    private void setBandwidthSelection(long bandwidth)
    {
        JComboBox<BandwidthItem> combo = getBandwidthCombo();
        DefaultComboBoxModel<BandwidthItem> model = (DefaultComboBoxModel<BandwidthItem>)combo.getModel();

        for(int i = 0; i < model.getSize(); i++)
        {
            BandwidthItem item = model.getElementAt(i);
            if(item.bandwidth() == bandwidth)
            {
                combo.setSelectedItem(item);
                return;
            }
        }

        if(model.getSize() > 0)
        {
            combo.setSelectedIndex(0);
        }
    }

    private void setGainModeSelection(String name)
    {
        JComboBox<GainModeItem> combo = getGainModeCombo();
        DefaultComboBoxModel<GainModeItem> model = (DefaultComboBoxModel<GainModeItem>)combo.getModel();

        if(name != null)
        {
            for(int i = 0; i < model.getSize(); i++)
            {
                GainModeItem item = model.getElementAt(i);
                if(item.name().equalsIgnoreCase(name))
                {
                    combo.setSelectedItem(item);
                    return;
                }
            }
        }

        if(model.getSize() > 0)
        {
            combo.setSelectedIndex(0);
        }
    }

    private SampleRateItem findSampleRate(SampleRateItem[] items, int target)
    {
        for(SampleRateItem item: items)
        {
            if(Math.abs(item.rate() - target) < 1_000)
            {
                return item;
            }
        }

        return items.length > 0 ? items[0] : null;
    }

    private BandwidthItem findBandwidth(BandwidthItem[] items, long target)
    {
        for(BandwidthItem item: items)
        {
            if(item.bandwidth() == target)
            {
                return item;
            }
        }

        for(BandwidthItem item: items)
        {
            if(item.bandwidth() == BladeRFTunerController.BANDWIDTH_AUTO)
            {
                return item;
            }
        }

        return items.length > 0 ? items[0] : null;
    }

    private GainModeItem findGainMode(GainModeItem[] items, String name)
    {
        if(name != null)
        {
            for(GainModeItem item: items)
            {
                if(item.name().equalsIgnoreCase(name))
                {
                    return item;
                }
            }
        }

        return items.length > 0 ? items[0] : null;
    }

    private void selectChannel(int channelId)
    {
        JComboBox<ChannelItem> combo = getChannelCombo();
        DefaultComboBoxModel<ChannelItem> model = (DefaultComboBoxModel<ChannelItem>)combo.getModel();

        for(int i = 0; i < model.getSize(); i++)
        {
            ChannelItem item = model.getElementAt(i);
            if(item.index() == channelId)
            {
                combo.setSelectedItem(item);
                return;
            }
        }

        if(model.getSize() > 0)
        {
            combo.setSelectedIndex(0);
        }
    }

    private JComboBox<SampleRateItem> getSampleRateCombo()
    {
        if(mSampleRateCombo == null)
        {
            mSampleRateCombo = new JComboBox<>();
            mSampleRateCombo.addActionListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner() && mSampleRateCombo.getSelectedItem() instanceof SampleRateItem item)
                {
                    try
                    {
                        getTuner().getController().setSampleRate(item.rate());
                        getConfiguration().setSampleRate(item.rate());
                        save();
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to set sample rate", se);
                    }
                }
            });
        }

        return mSampleRateCombo;
    }

    private JComboBox<BandwidthItem> getBandwidthCombo()
    {
        if(mBandwidthCombo == null)
        {
            mBandwidthCombo = new JComboBox<>();
            mBandwidthCombo.addActionListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner() && mBandwidthCombo.getSelectedItem() instanceof BandwidthItem item)
                {
                    try
                    {
                        getTuner().getController().setBandwidth(item.bandwidth());
                        getConfiguration().setBandwidth(item.bandwidth());
                        save();
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to set bandwidth", se);
                    }
                }
            });
        }

        return mBandwidthCombo;
    }

    private JComboBox<GainModeItem> getGainModeCombo()
    {
        if(mGainModeCombo == null)
        {
            mGainModeCombo = new JComboBox<>();
            mGainModeCombo.addActionListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner() && mGainModeCombo.getSelectedItem() instanceof GainModeItem item)
                {
                    try
                    {
                        getTuner().getController().setGainMode(item.name(), getConfiguration().getOverallGain());
                        getConfiguration().setGainModeName(item.name());
                        save();
                        getGainSpinner().setEnabled(item.isManual());
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to set gain mode", se);
                    }
                }
            });
        }

        return mGainModeCombo;
    }

    private JSpinner getGainSpinner()
    {
        if(mGainSpinner == null)
        {
            SpinnerNumberModel model = new SpinnerNumberModel(0, 0, 60, 1);
            mGainSpinner = new JSpinner(model);
            mGainSpinner.addChangeListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner())
                {
                    int gain = (Integer)mGainSpinner.getValue();
                    try
                    {
                        getTuner().getController().setOverallGain(gain);
                        getConfiguration().setOverallGain(gain);
                        save();
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to set overall gain", se);
                    }
                }
            });
        }

        return mGainSpinner;
    }

    private JComboBox<ChannelItem> getChannelCombo()
    {
        if(mChannelCombo == null)
        {
            mChannelCombo = new JComboBox<>();
            mChannelCombo.addActionListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner() && mChannelCombo.getSelectedItem() instanceof ChannelItem item)
                {
                    try
                    {
                        getTuner().getController().setChannel(item.index());
                        if(hasConfiguration())
                        {
                            getConfiguration().setChannelId(item.index());
                            save();
                        }
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to select channel", se);
                    }
                }
            });
        }

        return mChannelCombo;
    }

    private JLabel getChannelLabel()
    {
        if(mChannelLabel == null)
        {
            mChannelLabel = new JLabel("RX Channel:");
        }

        return mChannelLabel;
    }

    private void showError(String message, Exception exception)
    {
        JOptionPane.showMessageDialog(this, message + " - " + exception.getLocalizedMessage());
        mLog.error(message, exception);
    }

    @Override
    public void setTunerLockState(boolean locked)
    {
        getSampleRateCombo().setEnabled(!locked);
    }

    @Override
    protected void save()
    {
        if(hasConfiguration())
        {
            saveConfiguration();
        }
    }

    private record SampleRateItem(int rate, String label)
    {
        @Override
        public String toString()
        {
            return label;
        }
    }

    private record BandwidthItem(long bandwidth, String label)
    {
        @Override
        public String toString()
        {
            return label;
        }
    }

    private record GainModeItem(String name, boolean manual)
    {
        GainModeItem(GainModeInfo info)
        {
            this(info.getName(), info.isManual());
        }

        @Override
        public String toString()
        {
            return name;
        }

        public boolean isManual()
        {
            return manual;
        }
    }

    private record ChannelItem(int index)
    {
        @Override
        public String toString()
        {
            return "RX " + (index + 1);
        }
    }

    private static String formatRateLabel(long rate)
    {
        if(rate >= 1_000_000)
        {
            return trimDecimals(rate / 1_000_000.0) + " MHz";
        }
        else if(rate >= 1_000)
        {
            return trimDecimals(rate / 1_000.0) + " kHz";
        }

        return rate + " Hz";
    }

    private static String trimDecimals(double value)
    {
        long whole = (long)value;
        double fractional = Math.abs(value - whole);

        if(fractional < 0.001)
        {
            return Long.toString(whole);
        }

        return String.format(Locale.US, "%.3f", value).replaceAll("\\.?0+$", "");
    }

    private JCheckBox getBiasTCheckbox()
    {
        if(mBiasTCheckbox == null)
        {
            mBiasTCheckbox = new JCheckBox("Enable Bias-T (LNA power)");
            mBiasTCheckbox.addActionListener(e ->
            {
                if(!isLoading() && !mUpdatingControls && hasTuner())
                {
                    boolean enabled = mBiasTCheckbox.isSelected();
                    try
                    {
                        getTuner().getController().setBiasTEnabled(enabled);
                        getConfiguration().setBiasTEnabled(enabled);
                        save();
                    }
                    catch(SourceException se)
                    {
                        showError("Unable to set bias-tee", se);
                        mBiasTCheckbox.setSelected(!enabled);
                    }
                }
            });
        }

        return mBiasTCheckbox;
    }
}
