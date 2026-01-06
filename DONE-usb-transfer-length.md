Title: Respect actual USB transfer length when dispatching buffers

- Problem  
  - `USBTunerController.Dispatcher.dispatchTransfer()` ignores `Transfer.actualLength()` (src/main/java/io/github/dsheirer/source/tuner/usb/USBTunerController.java:760-799). Short/cancelled reads are still converted using the full buffer size, leaking stale bytes into channelizers and inflating timestamps.
- Why it matters  
  - On shutdown/error recovery buffers may be partially filled; processing garbage samples can cause loud audio artifacts and decoder corruption.
- Proposed fix  
  - Clamp the ByteBuffer limit to `actualLength` before handing it to the native buffer factory (or pass the length explicitly).
  - Ensure factory honors the length and does not read beyond it.
- Verification  
  - Add a unit test simulating a transfer with a shorter `actualLength` and assert only those bytes are converted.
  - Manual: start/stop a USB tuner rapidly; confirm no extra samples arrive after stop (log/metrics).
