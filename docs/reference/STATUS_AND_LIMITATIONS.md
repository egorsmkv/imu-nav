# Status and limitations

> Detailed reference. For easy steps, see the [guide](../STATUS_AND_LIMITATIONS.md) or the [glossary](../GLOSSARY.md).

- Walking mode is new and tuned only on simulations; record a few walks (with GPS) and check them
  with the replay tool before relying on it.

- Offline routing packs carry no traffic-signal data yet, so the stop-at-signal snap and signal
  speed plan stay inactive. The OSRM fallback uses the public demo server — self-host it for real use.
- The app accepts valid positions worldwide. Spoofing checks still reject mock, implausible and
  inconsistent fixes. An installed regional map pack switches to the online map outside its bounds;
  routing and tower positioning still need regional offline data or their configured online sources.
- Free-drive (no route) dead reckoning and speed cameras are not implemented yet;
  `Tuning` already carries the camera parameters.
- The engine differs from the analysed app in a few places: the gyro bias estimate is applied to
  turn integration, and projections compute exact arc-length.
- Not road-tested. Treat as a research prototype, never as a safety system.
