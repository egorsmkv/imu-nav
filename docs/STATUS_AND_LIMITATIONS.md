# Status and limitations

[Documentation index](../README.md)

IMU Nav is a research prototype, not a safety system. It has not been validated as a replacement for normal navigation or driver attention.

## Before relying on a feature

1. Test it on a route you know while you can still see where you are.
2. Record trips with usable GPS, then [replay](TRIPS.md) them to compare the estimate with the recorded track.
3. Treat an uncertain position or a rejected GPS fix as a reason to check your surroundings yourself.

Walking mode has mainly been tuned in simulations. Offline packs do not yet carry traffic-signal data, and free-drive navigation without a planned route is not implemented. The default service area is a coarse outline of Ukraine; fixes outside it are rejected. The public online routing fallback is a demo service.

See the [glossary](GLOSSARY.md) and the [detailed limitations reference](reference/STATUS_AND_LIMITATIONS.md) for the full list.
