Title: Keep bladeRF PLL enabled after programming

- Problem  
  - `BladeRF2ClockManager.configureReferenceClock()` re-disables the PLL when it was previously off (lines ~95-107 in src/main/java/io/github/dsheirer/source/tuner/bladerf/usb/BladeRF2ClockManager.java). On cold boots this leaves the reference clock stopped, so the Si5338 sample clock never locks and RF initialization intermittently fails.
- Why it matters  
  - Users see sporadic “RFIC failed to reach initialized state”/sample clock errors on first start; downstream tuning never begins.
- Proposed fix  
  - Keep the PLL enabled after writing the latches (match libbladeRF behavior) and optionally poll for lock before returning.
  - Add a guard so we only toggle if we truly changed state; avoid redundant writes.
- Verification  
  - Unit/integration test that configures reference clock on a mocked NIOS, asserting PLL stays enabled and Si5338 programming succeeds on first attempt.
  - Manual: power-cycle bladeRF 2.0 Micro, start app twice; ensure no initialization failures are logged.
