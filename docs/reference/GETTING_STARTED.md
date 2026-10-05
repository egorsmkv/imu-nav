# Getting started

> Detailed reference. For easy steps, see the [guide](../GETTING_STARTED.md) or the [glossary](../GLOSSARY.md).

## First launch

The setup checklist appears once on new and existing installations after the onboarding update.
It offers precise location, notifications (Android 13+), physical activity (Android 10+), nearby
devices for Bluetooth OBD-II (Android 12+), and a separate battery optimization exemption. Grants
enable access; they do not turn on walking mode or connect an OBD adapter. The checklist also checks
the phone's Location switch and opens system settings if it is off. Disabling Location is supported
on Android 8–10 as well as newer phones; it does not stop the app or its inertial navigation. Declined permissions can
be enabled later, including through Android Settings when the permission dialog is no longer offered.

Choose the UI and voice language directly in onboarding: phone default, Ukrainian, English or Russian.
The selection is saved and the checklist stays open when the language changes.
The **Telegram group** link opens <https://t.me/imu_nav> from onboarding as well as Settings.

The bundled routing/address-search archive is **optional**: it is not unpacked until you tap **Install
built-in routing pack**, and the work then continues in the background. The built-in cell tower database
is optional too and is not unpacked until you tap **Install built-in towers**. Skip it to learn towers from trusted GPS and use your own sharing server; the checklist
links to **Settings → Cell towers → Sharing server**. Skipping towers does not block setup completion.
Existing tower data is preserved, and database resets only reinstall the archive after explicit opt-in.
Play builds can include the routing archive; F-Droid builds
require a routing pack imported or downloaded in Settings. Missing archives or denied permissions
do not block **Continue with limited functionality**, and preparation continues in the background.
Reopen the checklist from **Settings → Set up IMU Nav**. Previously removed routing packs stay removed
until explicitly reinstalled. IMU Nav is a research prototype, not a safety system.

## Network proxy

**Settings → Network proxy** supports the phone/system default (initial setting), explicit direct
connections, an HTTP proxy with optional Basic username/password, or an unauthenticated SOCKS proxy.
Enter a hostname/IP (not a URL) and port, then tap **Apply proxy settings**. Settings persist across
restarts and take effect for new online map/style/tile requests, route/search requests, archive and
tower downloads, and cell sync. Active downloads keep their existing connection/configuration.
A custom proxy failure never silently falls back to a direct connection. This is not a VPN: Android
location providers and other apps are unaffected. Offline features do not need a proxy.
HTTP proxy credentials are stored in app-private preferences, not encrypted by the app, and never
logged or included in exports. HTTP proxy authentication is not encrypted on the proxy connection;
use only trusted proxies. SOCKS authentication is not supported.
