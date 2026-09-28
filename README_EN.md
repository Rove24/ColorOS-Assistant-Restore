# ColorOS AssistRestore

[![简体中文](https://img.shields.io/badge/README-简体中文-dc8a3c?style=for-the-badge)](README.md)
[![English](https://img.shields.io/badge/README-English-5b6b8c?style=for-the-badge)](README_EN.md)

[![libxposed API](https://img.shields.io/badge/libxposed-API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/ColorOS-16%20CN-1a73e8)](docs/technical-notes.zh.md)
[![Root](https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk-orange)](#requirements)

Restores AOSP digital-assistant behaviour on China-region ColorOS builds.

On these builds the three ways to summon the assistant are rewritten to always reach the OEM assistant, and each path bypasses the AOSP assistant dispatch: holding the power button starts the OEM assistant, holding the gesture handle triggers the OEM screen recognition, and swiping in from a bottom corner does nothing at all. This module does not replace the system assistant stack. It only reconnects those rewritten dispatch points so the request reaches the assistant app configured in the system (`Settings.Secure.assistant` / `RoleManager.ROLE_ASSISTANT`), matching AOSP behaviour.

## Features

The three entry points share the same code across regions; only two region gates and a few hard-coded component names differ, so each path can be restored on its own:

| Entry | Stock behaviour on China builds | Behaviour after this module |
| --- | --- | --- |
| Hold power button | Explicitly starts `com.heytap.speechassist` | Follows the AOSP branch through `launchAssistAction` and wakes the default assistant, with the long-press haptic kept |
| Hold gesture handle | Sends `start_type=91` to the OEM screen recognition | Wakes the default assistant; can optionally use Circle to Search or stay on the OEM screen recognition |
| Corner swipe | Assistant availability is hard-coded to `false`, so the gesture never fires | Assistant availability is answered with AOSP semantics, including on pages such as Settings |

Each entry has its own wake target and can be turned off individually. Beyond the three entries:

- **Circle to Search**: the module fills in the system CTS service chain that China builds lack, and can spoof a supported device model inside the Google app process.
- **Page-level gesture**: the launcher blocks the corner gesture on pages such as Settings by default; the module clears those two page-level flags.
- **No useless wake-up**: holding the gesture handle no longer pre-binds the OEM screen recognition service.
- **Holding the handle only summons the assistant**: the bar's own strip (about 480x88 px) belongs to the system navigation bar window, so the page no longer fires its own long press at the same time; this also holds with the gesture bar hidden, and the strip goes back to the keyboard while it is up.
- **Process retention**: a frozen Google app is the usual reason a dispatch succeeds with nothing on screen, so the module exempts that app at the OEM freeze decision point.

## Requirements

- A China-region ColorOS build. Verified on ColorOS 16 / `V16.1.0` / PJZ110 / `regionmark=CN`.
- Root via KernelSU, Magisk, or similar.
- A framework that supports libxposed API 102, such as LSPosed.

## Installation

1. Install `app-debug.apk`.
2. Enable the module in LSPosed and select all four scopes:

   ```
   system
   com.android.systemui
   com.android.launcher
   com.google.android.googlequicksearchbox
   ```

3. Reboot.
4. Open the app and confirm on the entry page that the module is active, then adjust the wake targets.

The four scopes cover power-key dispatch, SystemUI gestures and assistant dispatch, launcher corner gesture, and Circle to Search with process retention. Missing one leaves the matching feature inactive.

## Usage

The app has two bottom pages; detail pages are reached from the entry page.

**Entry page**: two cards on top show module status and the current system default assistant (tapping opens the system assistant settings), followed by the three entries and a master switch.

**Wake target page**: tabs for the power button, the gesture handle, and the corner swipe. Each entry is configured independently.

| Target | Meaning |
| --- | --- |
| Follow system default assistant | Wakes the app in `Settings.Secure.assistant`, i.e. AOSP semantics |
| Circle to Search | Uses the system CTS service; meaningful when the default assistant is the Google app |
| OEM screen recognition | No interception: ColorOS keeps handling the gesture-handle long press (gesture handle only) |
| OEM assistant | Launches the OEM assistant app directly (power button and corner swipe only) |
| Other app... | Opens the custom target page for a package name or service component |
| All off | The entry wakes nothing and the OEM call is suppressed too |

**Custom target page**: paste the Intent JSON from an app info screen to fill the fields automatically, or enter the package name, service component, invocation method, and Intent parameters by hand.

**Advanced page**:

| Option | Default | Meaning |
| --- | --- | --- |
| Skip screen-recognition pre-bind | On | Holding the gesture handle no longer pre-binds the OEM screen recognition service |
| Unblock page-level gestures | On | Clears the page-level flags requested by apps so Settings and similar pages accept the corner swipe |
| Spoof Google app device model | On | Reports SM-S928B inside the Google app process to unlock Circle to Search |
| Keep the handle press with a hidden bar | On | ColorOS stops feeding the gesture handle as soon as the bar is hidden; with this on, the long press at the same bottom position still wakes the entry's configured target. The bar itself stays hidden |
| Hide the launcher icon | Off | Removes the home-screen icon (the launcher entry is an activity alias). The entry activity keeps a MAIN + INFO filter, so the app still opens from LSPosed and from the system app-info page |

The power-key haptic feedback is always on and has no setting.

## Known limitations

The platform allows only one voice-interaction service to be active at a time, so:

- The “Other app...” target only works when that app declares its own assistant activity; some apps (for example the ChatGPT `ACTION_ASSIST` proxy activity) do nothing without a live session.
- A custom target starts an explicit component with explicit parameters, so whether a UI appears depends on the target app.
- Only the two app-requestable page flags are cleared. Lock screen, notification shade, expanded quick settings, hidden navigation bar, and screen pinning stay blocked.
- Do not enable another module that intercepts the same gesture (for example Oplus-Assistant-Hook) at the same time; whichever intercepts first returns early.

## Troubleshooting

The module logs under the `AssistRestore` tag; filter the LSPosed log by process. Common entries:

| Entry | Meaning |
| --- | --- |
| `power_key_target mode=... package=...` | Power key matched, target resolved from configuration |
| `assist_dispatch component=... invocationType=...` | Dispatched to the assistant (1 corner, 5 gesture handle, 6 power key) |
| `target_started entry=... method=...` | A custom target was started |
| `power_key_skip reason=disabled` / `assist_skip reason=...` | Deliberately skipped by switch or page state |
| `gesture_handle_ocr_preload_skipped` | Normal: this long press is handled by the module, the OEM pre-bind was skipped |
| `circle_to_search_triggered` | Circle to Search was triggered |
| `google_hans_scene_exempt` | The Google app was exempted at the system freeze decision point |

If `assist_dispatch` appears but nothing shows on screen, dispatch is healthy and the assistant process was frozen or reclaimed. Allow the assistant app to run in the background, disable automatic permission revocation, and try again.

Reverse-engineering evidence, method signatures, and on-device logs are in the [technical notes](docs/technical-notes.zh.md) (Chinese).

## Build

JDK 21 and an Android SDK with `compileSdk 37` are required. The project uses the Gradle 8.13 wrapper with AGP 8.13.2.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Add `--offline` when the dependency cache is already populated.

## Version

**1.0.0** - all three entries restored, with per-entry wake targets, Circle to Search, page-level gesture unblocking, and Google app process retention.

## Disclaimer

This project is for study and research only. Changing how the system dispatches assistant requests carries risk; evaluate it yourself and keep a backup.
