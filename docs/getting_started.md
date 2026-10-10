# Getting started

[Documentation index](../README.md)

IMU Nav can guide a trip when GPS becomes unreliable. You can finish setup with limited features and return to it later.

## Set up the app

1. Open the app and use the language dropdown to choose the language for screens and voice directions. Choose **Phone default** to follow your phone’s language.
2. Tap **Enable permissions**. Allow precise location for navigation. Allow notifications if you want trip updates. Walking uses the Physical activity permission; a Bluetooth car-speed adapter uses Nearby devices on newer Android versions.
3. If the app says the phone's Location switch is off, open the offered Android settings and turn it on. A permission grant alone does not switch Location on.
4. Choose **Install built-in routing pack** or **Install built-in towers** if those options are available and you want the offline data. Both are optional. F-Droid users can import or download a routing pack later in Settings.
5. Tap **Continue with limited functionality** when you are ready. Any chosen data installation can finish in the background.

You can also change the language in **Settings → General → Language**. Tap the current language to open the options; the change applies immediately.

To return to the checklist, open **Settings → Set up IMU Nav**. Skipping a pack does not erase tower data already on the phone.

## Share the navigation position as system GPS

1. Open **Settings → General → System GPS sharing** and read the limitations.
2. Tap **Open Developer options**, enable Android Developer options if needed, and choose
   this installation of **IMU Nav** under **Select mock location app**. Debug and benchmark
   builds are separate installations. Android grants this access explicitly; it is not a normal
   runtime permission dialog.
3. Turn on **Use IMU Nav as system GPS**, then start a route. The status changes from waiting
   to sharing when the foreground navigation service has a valid estimate. Sharing works with
   the screen off and while another app is in front; the navigation notification identifies it.
4. Turn the switch off or end navigation to remove the mock GPS provider. The switch is remembered
   on this device for later trips and is never synchronized to another device.

**This replaces the real GPS provider, including the GPS input seen by IMU Nav itself.**
The navigator continues from motion sensors, route knowledge and available network/cell/OBD inputs;
it cannot reacquire real GPS until sharing stops. Only Android's GPS provider is replaced.
Network location and proprietary fused-location APIs are not overridden, and other apps may reject
mock fixes. This is an experimental research feature, not a safety system or a guarantee that every
app will display the same location.

Export uses the navigation estimate's uncertainty, speed, bearing and sample time; it does not invent
altitude or a precise accuracy. Mock inputs are not accepted as independent navigation evidence.
Missing or invalid guidance removes the provider instead of continuing to publish an old position.
The existing navigation tick drives updates; there is no separate polling service or Google dependency.

If Android revokes mock-location access, sharing stops and settings shows the error. Android may
also deny cleanup: reselect this installation, return to settings, and switch sharing off. If the
provider remains stuck after a force-stop or device-specific system error, select **None** for the
mock-location app or restart the phone. On process restart, IMU Nav attempts to remove a provider
it previously installed before beginning another export session.

### Device verification for GPS sharing

On an Android 8 device/emulator and a current Android version, verify: disabled by default; denied
mock access gives setup guidance without stopping navigation; enabling during a trip delivers
`isMock` GPS fixes in a separate location-listener test app; accuracy matches navigation uncertainty;
backgrounding keeps updates coming; disabling and ending navigation restore the original provider;
revoking access does not crash; returning from Developer options refreshes status; process restart
cleans up an owned provider. Check English/Ukrainian/Russian and narrow screens with large text.
Use synthetic routes and locations. A device test must grant mock-location access explicitly.

Android API reference: [LocationManager test providers](https://developer.android.com/reference/android/location/LocationManager#addTestProvider(java.lang.String,boolean,boolean,boolean,boolean,boolean,boolean,boolean,int,int)).

## Check data usage

Open **Settings → Data usage** for a prominent total and separate download/upload
cards with automatically selected B, KB, MB or GB units and localized number formatting.
A two-colour bar and percentage labels show the received/sent split, with a
separate message when there is no traffic. Cards wrap on narrow screens or at
large font sizes. A total and split are shown only when both counters are available.

The figures cover all network interfaces (including Wi-Fi and mobile data). These Android counters
cover the period **since the device last restarted**, including traffic before
the app was reopened; they are not monthly totals or a mobile-only allowance.
Rebooting resets the counters. Values refresh when the section is expanded or
the app returns to the foreground; tap **Refresh** for a new snapshot during a
download. There is no background polling or additional permission request.

The counters include network-layer traffic, not just downloaded file sizes, so
they can differ from a carrier bill. **Unavailable** means Android could not
provide that direction's counter; it does not mean zero usage. Statistics remain
on the device and are not uploaded.

## Set a network proxy, if needed

1. Open **Settings → Network proxy**.
2. Choose the phone's default connection, a direct connection, an HTTP proxy, or a SOCKS proxy.
3. For a proxy, enter its host name or IP address and port. Enter a username and password only for an HTTP proxy that you trust.
4. Tap **Apply proxy settings**. New online requests use the setting; a download already in progress keeps its current connection.

Offline navigation does not need a proxy. This setting does not act as a VPN for the rest of the phone. HTTP proxy credentials are stored in app-private settings but are not encrypted by the app.

## Optional air-raid alerts

Sign in to a sharing server that has configured UkraineAlarm, then enable **Air-raid alerts for Ukraine** in Settings or on your server account page. The app shows current oblast-level air-raid status and sends Android notifications for starts and all-clears received while the app is open. Allow Android notifications for the app. The alert switch is off by default, uses no phone location, and does not provide a safety guarantee; consult official warning channels.

See the [glossary](glossary.md) for unfamiliar terms and the [detailed setup reference](reference/getting_started.md) for permission and proxy behaviour.

See [settings and bookmark synchronization](server/account-sync.md) for account restore, offline edits and deletion.

## Automatic battery saving

At 20% battery or below, IMU Nav uses **Battery saver** while unplugged, even if you selected
**Max accuracy** or **Balanced**. It reduces sensor and network update rates and map animation.
Turn detection may be less precise. Settings shows the active mode and current battery level.

Your selected mode is remembered. It takes effect again when you plug in or the battery reaches
25%. This gap prevents repeated switching near 20%. Unplugging while the battery remains low
activates Battery saver again. A manually selected Battery saver stays selected.

Battery and charging changes apply immediately, including during background navigation.
**Auto** also follows Android’s Battery Saver while unplugged. This changes only IMU Nav’s
power profile; it does not change Android’s system settings.
