# bladeRF1 Native Support Plan

Goal: reintroduce bladeRF 1 devices using the same libusb-based control layer that now powers bladeRF 2. Each step below can land independently; later steps build on earlier ones but do not require finished end-to-end streaming until the final milestone.

## 1. Capture Hardware + Firmware Requirements
- Document supported bladeRF 1 models, FX3 firmware revisions, and FPGA images.
- Note USB VID/PID pairs, EEPROM requirements, and known quirks such as single-channel receive.
- Output: design notes checked into `docs/` plus acceptance criteria for minimum firmware.

## 2. Extend USB Metadata Discovery
- Update `BladeRFUsbDevice` (or a sibling) to parse bladeRF 1 descriptors and expose board name, serial, versions, and capability bits without libbladeRF.
- Verify discovery works on Windows/Linux/macOS by dumping metadata via a diagnostic CLI.
- Output: automated unit/integration test that enumerates fake descriptors to confirm parsing.

## 3. Define bladeRF1 Device Abstractions
- Add `BladeRF1Device`, `BladeRF1UsbProtocol`, and a minimal NIOS/FX3 helper mirroring the bladeRF2 scaffolding.
- Encapsulate common USB operations (control transfers, bulk endpoints, reset sequences) so the controller does not issue raw libusb calls.
- Output: constructor smoke tests proving the abstractions initialize when provided with the metadata from step 2.

## 4. Port FX3 Streaming Enable/Disable Logic
- Reverse engineer the legacy `bladerf_enable_module`+`bladerf_sync_*` flow and implement the equivalent FX3 vendor commands within `BladeRF1UsbProtocol`.
- Include retry/error handling consistent with the bladeRF2 path, logging FX3 states for diagnostics.
- Output: helper invoked by a new integration test that toggles streaming without touching the RF front end.

## 5. Implement Sample Rate + Bandwidth Configuration
- Map the LMS6002D PLL/divider programming used in bladeRF1 to Java helpers (mirroring libbladeRF’s `tuning/lms.c`).
- Provide range metadata so `BladeRFTunerController` can populate supported sample rates and bandwidths when a bladeRF1 is detected.
- Output: simulated calculations that match libbladeRF outputs for a set of known-good configurations.

## 6. Implement Frequency + Gain Control Plane
- Port tuning/gain sequences for LMS6002D (frequency hops, DC offset loops, LNA/VGA steps) through the new protocol layer.
- Update `BladeRFTunerController` to route `setTunedFrequency`, `setGainMode`, and `setOverallGain` through the bladeRF1 abstractions when present.
- Output: unit tests validating register writes and runtime logs that mirror libbladeRF traces.

## 7. Add Bias-T and GPIO Feature Support
- Detect optional accessories (XB-200, bias tee) via USB metadata.
- Map bias-tee enable/disable requests to the proper GPIO writes on bladeRF1 hardware.
- Output: manual verification steps and automated regression ensuring unsupported boards gracefully reject the request.

## 8. Implement Streaming Data Path
- Create a bladeRF1-specific streamer that submits asynchronous bulk transfers, reusing `BladeRFNativeBufferFactory`.
- Handle overruns/underruns, provide RSSI metadata if available, and emit meaningful error messages for USB timeouts.
- Output: integration harness capturing IQ samples from hardware and comparing them against libbladeRF output for the same scenario.

## 9. Integrate Device Selection + Lifecycle
- Update discovery (`DiscoveredUSBTuner`, `TunerFactory`, etc.) so bladeRF1 devices instantiate the controller with the proper device abstraction and capability set.
- Ensure start/stop paths call into the correct streaming + RF enable primitives and that UI/editor panels expose bladeRF1-specific options.
- Output: end-to-end smoke test that starts/stops both bladeRF1 and bladeRF2 devices within the same runtime.

## 10. Add Regression Coverage + Documentation
- Create automated regression scenarios (unit tests, scripted hardware tests) covering tuning, streaming, gain changes, and failure handling on bladeRF1 hardware.
- Document setup instructions, supported firmware versions, and troubleshooting tips in `docs/bladerf1.md`.
- Output: CI job (or manual checklist) that keeps the bladeRF1 path healthy alongside bladeRF2.

