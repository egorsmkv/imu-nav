# Getting started

[Documentation index](../README.md)

IMU Nav can guide a trip when GPS becomes unreliable. You can finish setup with limited features and return to it later.

## Set up the app

1. Open the app and choose the language for screens and voice directions.
2. Tap **Enable permissions**. Allow precise location for navigation. Allow notifications if you want trip updates. Walking uses the Physical activity permission; a Bluetooth car-speed adapter uses Nearby devices on newer Android versions.
3. If the app says the phone's Location switch is off, open the offered Android settings and turn it on. A permission grant alone does not switch Location on.
4. Choose **Install built-in routing pack** or **Install built-in towers** if those options are available and you want the offline data. Both are optional. F-Droid users can import or download a routing pack later in Settings.
5. Tap **Continue with limited functionality** when you are ready. Any chosen data installation can finish in the background.

To return to the checklist, open **Settings → Set up IMU Nav**. Skipping a pack does not erase tower data already on the phone.

## Set a network proxy, if needed

1. Open **Settings → Network proxy**.
2. Choose the phone's default connection, a direct connection, an HTTP proxy, or a SOCKS proxy.
3. For a proxy, enter its host name or IP address and port. Enter a username and password only for an HTTP proxy that you trust.
4. Tap **Apply proxy settings**. New online requests use the setting; a download already in progress keeps its current connection.

Offline navigation does not need a proxy. This setting does not act as a VPN for the rest of the phone. HTTP proxy credentials are stored in app-private settings but are not encrypted by the app.

## Optional air-raid alerts

Sign in to a sharing server that has configured UkraineAlarm, then enable **Air-raid alerts for Ukraine** in Settings or on your server account page. The app shows current oblast-level air-raid status and sends Android notifications for starts and all-clears received while the app is open. Allow Android notifications for the app. The alert switch is off by default, uses no phone location, and does not provide a safety guarantee; consult official warning channels.

See the [glossary](glossary.md) for unfamiliar terms and the [detailed setup reference](reference/getting_started.md) for permission and proxy behaviour.
