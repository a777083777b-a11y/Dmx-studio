# DMX Studio

Android app that controls DMX lighting over Art-Net or sACN (E1.31) through Wi-Fi.
UI languages: Uzbek, Russian, English.

## Tabs

| Tab | What it does | Color Kinetics analogue |
|---|---|---|
| Pult / Console | Master, blackout, palette, per-fixture sliders | — |
| Effektlar / Effects | Effect presets: fixed, color cycle, chase, wave, rainbow, sparkle, strobe, breathe, random fade, scrolling image. Colors, speed, size, direction, level, target fixtures. Live tuning while playing | ColorPlay |
| Xarita / Map | Facade map with live colors; auto layout from drawing names or by universe; drag to move; identify | Video System Manager (VSM) |
| Shou / Shows | Sequence of effects, scenes and "off" steps with durations, loop | ColorPlay show |
| Jadval / Schedule | Events by clock time, sunset or sunrise (+/- minutes) and weekdays: play show, effect, scene or turn off. Runs in background, restarts after reboot | Light System Manager (LSM) |
| Patch | Fixtures (universe, address, channels), edit, identify, re-address a universe in order | QuickPlay (addressing) |
| Sahnalar / Scenes, Chase | Snapshots and scene chases | — |
| Test | Blink, run, fill, color cycle for checking lines | — |
| Sozlama / Settings | Language, protocol, IP, universe offset, Wi-Fi only, background mode, network scan, built-in Marshala Zhukova 11 project, export/import | — |

## Notes

- The app cannot write a DMX address into a fixture (no RDM). Fixture addresses are set by the controller (PROG line).
- "Always send over Wi-Fi" binds the Art-Net socket to the Wi-Fi network, so packets do not go out over mobile data when the Wi-Fi has no internet.
- Background mode and the schedule use a foreground service with a notification; keep the phone on charge at the site.

Build: GitHub Actions builds a debug APK on every push and publishes it as a release.
