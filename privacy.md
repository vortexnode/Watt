# Privacy Policy

Watt runs entirely on your device. It makes no network requests, has no
analytics or crash reporting, no ads, and no third-party SDKs. The app
does not request the `INTERNET` permission, so it cannot send data
anywhere.

A few preferences (for example whether the charging notification is
enabled) are saved locally with Android `SharedPreferences`. Nothing is
uploaded.

## What Watt reads

While a charger is connected, Watt reads battery current, voltage,
charge level and plug state from Android `BatteryManager`.

If you install Shizuku and grant Watt permission, it can also read the
battery `current_now` and `voltage_now` values from
`/sys/class/power_supply`. This is optional, read-only, and only happens
while a charger is connected.

When the charger is disconnected, Watt stops reading sensors and the
notification is removed.

## Permissions

- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` - keep the
  charging notification alive while a charger is connected
- `POST_NOTIFICATIONS` - show the charging notification (Android 13+)
- `POST_PROMOTED_NOTIFICATIONS` - ask Android 16+ to show the
  notification as a Live Update
- `WAKE_LOCK` - keep the CPU awake while a charger is connected so the
  notification can refresh every second; released on disconnect
- `RECEIVE_BOOT_COMPLETED` - re-register the charging monitor after a
  reboot or an app update

Watt also declares the Shizuku content provider so it can talk to
Shizuku when you choose to use it. No data leaves your device.

## Contact

- <https://github.com/vortexnode/Watt/issues>
