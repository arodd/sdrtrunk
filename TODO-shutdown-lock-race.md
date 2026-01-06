Title: Avoid cfg replay while tuner is shutting down

- Problem  
  - `Tuner.stop()` now forces `setLockedSampleRate(false)` (src/main/java/io/github/dsheirer/source/tuner/Tuner.java:125-131). If the controller has deferred sample-rate/bandwidth updates (bladeRF), unlocking can trigger `applyDeferredConfigurationAfterUnlock()` while shutdown is tearing down streaming, causing racey stop/start and USB errors.
- Why it matters  
  - Shutdown can intermittently throw transfer errors or hang because streaming restarts briefly during teardown.
- Proposed fix  
  - On stop: clear pending deferred configs or suppress their application when the tuner is no longer running.
  - Add a controller flag “shuttingDown” to skip deferred apply and streaming restarts once stop begins.
- Verification  
  - Regression test: lock sample rate, queue a pending change, call stop(); assert no calls to startStreaming()/applyDeferredConfiguration occur and no USB errors logged.
