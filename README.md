# E90 OS

Android launcher for the BMW E90 head unit. Kotlin, minSdk 29, HOME activity.

## Develop in the emulator

The `E90_HeadUnit` AVD is configured for a 1280 × 480 landscape screen. Its data lives on `D:\E90OS-EmulatorData` because the system drive has limited free space. The installed system image is Android API 37.2; behavior specific to the real Android 10 head unit still needs device testing.

From PowerShell in this folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-emulator.ps1
```

The script starts the emulator if necessary, waits for boot, builds the debug APK, installs it on `emulator-5554`, and opens the launcher. It never installs on the Wi-Fi connected head unit. Use `-SkipBuild` to relaunch the last built APK.

Development branch: `feature/emulator-development`.

## COCKPIT home screen (draft)

The new home screen uses amber E90 styling, a live MediaSession player and four persistent customizable app shortcuts. Tap an empty shortcut to pick any launcher app; long-press an occupied shortcut (or tap the pencil) to replace/remove it.

Vehicle data is deliberately shown as unavailable until a verified CAN/MCU integration exists. The former Android device battery indicator was **not** an accurate measure of the car's 12 V supply.

This source revision has not yet been built or installed on the physical head unit. Test on the emulator first, then use the existing Windows ADB setup to install the debug APK after reviewing it. The launcher has a HOME intent filter, so Android may offer it as a home-screen choice; keep KSW as the default until the new UI has been checked.
