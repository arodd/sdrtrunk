# Tuner Frequency Lock UI Behavior

**Issue summary:** The `TunerEditor` frequency controls sometimes stay disabled after all polyphase channels stop, and can remain enabled while new channels are active, even though the controller’s `isLockedSampleRate()` flag toggles correctly.

**Backend state:**
- `PolyphaseChannelSourceManager` listens for `NOTIFICATION_CHANNEL_COUNT_CHANGE` from `PolyphaseChannelManager` and calls `mTunerController.setLockedSampleRate(getTunerChannelCount() > 0)` (`src/main/java/io/github/dsheirer/source/tuner/manager/PolyphaseChannelSourceManager.java`).
- When the count drops to zero, the controller broadcasts `NOTIFICATION_FREQUENCY_AND_SAMPLE_RATE_UNLOCKED`; when channels appear, it broadcasts the *_LOCKED* event. The tuner list uses these events to show `0 (UNLOCKED)` or `N (LOCKED)` correctly.

**UI state:**
- The frequency inputs live inside `TunerEditor.FrequencyPanel.updateControls()` (`src/main/java/io/github/dsheirer/source/tuner/ui/TunerEditor.java:882-930`), which enables/disables the widgets based on `getTuner().getTunerController().isLockedSampleRate()`.
- `updateControls()` is invoked from `tunerStatusUpdated()` (ENABLED/DISABLED changes), but not when the lock state changes while the tuner remains enabled. Therefore the frequency controls only refresh when the user toggles the tuner, not when the lock flips.
- `TunerViewPanel` listens for `UPDATE_LOCK_STATE` and calls `DiscoveredTunerEditor.setTunerLockState(locked)` (`src/main/java/io/github/dsheirer/source/tuner/ui/TunerViewPanel.java:150-195`), but the concrete editors (e.g. `BladeRFTunerEditor.setTunerLockState` in `src/main/java/io/github/dsheirer/source/tuner/bladerf/BladeRFTunerEditor.java:586-589`) only toggle the sample-rate combo box there. The frequency controls are untouched, so they remain in their previous enabled/disabled state.

**Consequence:**
- After the user stops all channels, the backend is unlocked but the frequency panel stays greyed out until some other action (disabling/re-enabling the tuner) forces a full `updateControls()` call.
- After they retune while the panel is enabled and then start channels, the backend re-locks but the frequency controls remain enabled because no refresh occurs. The user can now retune while channels are active.

**Fix approach:**
1. Ensure lock/unlock events trigger `FrequencyPanel.updateControls()`. For example, have `DiscoveredTunerEditor.setTunerLockState` call back into the active `TunerEditor` so the entire panel refreshes.
2. Alternatively (or additionally), make every `TunerEditor.setTunerLockState` implementation enable/disable the frequency inputs (frequency control, min/max text fields, reset button) alongside the sample-rate combo.
3. Verify that `UPDATE_LOCK_STATE` continues to fire from `TunerController.setLockedSampleRate` so the UI receives lock transitions even without enable/disable events.

Documenting this saves re-investigation: the backend lock logic already works, the UI just needs to respond to the lock events.
