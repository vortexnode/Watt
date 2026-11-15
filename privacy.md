# Watt Privacy Policy

Watt runs locally on your device. It does not request the Internet permission and has no ads, analytics, crash-reporting service, account sign-in, or telemetry. Battery measurements and app preferences are not uploaded.

## Data sources

- Android BatteryManager provides battery current, voltage, and charging state.
- If the user has installed and authorized Shizuku, Watt can read supported kernel power-supply metrics from `/sys/class/power_supply`. Shizuku is optional; BatteryManager is the automatic fallback.
- App preferences are stored locally using Android SharedPreferences.

## Permissions

- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` — keep the charging monitor and its ongoing notification active while charging.
- `POST_NOTIFICATIONS` — show the charging notification (Android 13+).
- `POST_PROMOTED_NOTIFICATIONS` — request promoted Live Update presentation where supported.
- `RECEIVE_BOOT_COMPLETED` — restore the persisted charging-monitor job after reboot or app update.
- `WAKE_LOCK` — keep the CPU available for periodic measurements during an active charging session.

Shizuku access is optional and is separately authorized by the user through the Shizuku app. Watt does not require root.

## Contact

For questions or issues, use the [Watt GitHub issue tracker](https://github.com/vortexnode/Watt/issues).
