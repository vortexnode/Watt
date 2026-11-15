# Watt

Watt is a focused Android charging-power monitor. While external power is connected, it measures charging power and displays the current value in an ongoing notification, including Samsung Now Bar on supported devices. Monitoring ends when external power is disconnected.

## Features

- Live charging-power measurement, refreshed approximately once per second.
- Samsung Now Bar / Android Live Update promotion on supported devices.
- Shizuku-backed kernel power-supply readings are preferred when available and valid.
- Android BatteryManager is used automatically when Shizuku or sysfs readings are unavailable.
- No root access required. Shizuku is optional.
- Automatic charging-session start and stop, with a persisted JobScheduler fallback after reboot.
- Samsung Modes and Routines shortcuts for starting and stopping monitoring.
- No ads, analytics, account sign-in, or Internet permission.

## Measurement notes

The displayed value estimates power at the battery-management system using battery current and voltage. It is not the charger's USB input power: phone consumption and conversion losses can make an external USB meter show a higher value. Available readings and their units depend on device firmware and kernel drivers.

When Shizuku is connected, Watt reads `current_now` and `voltage_now` from `/sys/class/power_supply` and uses those values as the preferred source. If either value is missing or invalid, Watt falls back to Android BatteryManager.

## Requirements

- Android 9 or newer.
- Notification permission.
- Android 16+ and a compatible System UI for promoted Live Update presentation.
- Shizuku is optional and only used for supported kernel power-supply readings.

OEM interfaces may apply additional rules to background execution and Live Update promotion.

## Build

```sh
./gradlew :app:assembleDebug
```

Release builds are signed through the repository's configured CI signing secret. The application ID and signing identity are intentionally kept stable so existing installations can update in place.

## Download

[Latest Watt release](https://github.com/vortexnode/Watt/releases/latest)

## Acknowledgements

Watt is based on [Beam](https://github.com/montafra/beam), an open-source Android battery monitor by Francesco Montalegni. Beam's original README also acknowledges [Wattz](https://github.com/dubrowgn/wattz), created by Dustin Brown, as an inspiration.

Watt has been adapted and substantially modified into a focused charging-power monitor. It is maintained independently in this repository. The original MIT license and copyright notices are retained in [LICENSE](LICENSE).

## License

MIT. See [LICENSE](LICENSE) for the full license text.
