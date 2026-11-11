# LogLens 1.0.0

A simple, standalone local logcat viewer for LeviLauncher on Android ARM64. Author: Zxwmwmwm. Targets Minecraft Bedrock 1.26.62 and 1.26.62.x. Android device testing is pending.

## Install

Download `LogLens.levipack` from the [latest release](https://github.com/Zxwmwmwm/LogLens/releases/latest), import it using LeviLauncher's mod manager, enable it, then start Minecraft. Tap the draggable **LOG** button. LogLens is separate from LumaUI and has no gameplay features.

## Interface and filters

- The compact logcat-style screen has a severity menu, keyword filter, comma-separated blacklist, pause/resume, clear, and one **Save** menu. Tap **⋮** for tag filtering, regex, case sensitivity and auto-scroll.
- Severity colors: verbose is gray, debug cyan, info green, warning yellow, error red, and fatal pink. Colors indicate Android log priorities rather than verified feature status.
- Severity choices with `+` are minimum levels: `W+`, for example, includes warnings, errors and fatal logs.
- The viewer reads `logcat -v threadtime --pid <current process>`, subject to Android's permissions. The displayed logs are scoped to the current process.
- Pausing freezes the displayed buffer to make text selection easier. Incoming lines wait in a bounded queue; if it fills, the status displays a dropped count. Resume processes the backlog. Clear affects LogLens's in-memory buffer only.
- The viewer retains at most 2,000 lines in memory and truncates lines longer than 4,096 characters. Export logs to save them persistently.

## Save a text file

Open the **Save** menu:

- **Specific words:** pause, long-press a log line, and adjust Android's selection handles. Choose **Save selected text**. To collect snippets from multiple lines, choose **Add selected text to collection** after each selection, then **Save selected text**.
- **Whole lines:** tap each line to highlight it (tap again to deselect); choose **Save selected lines**. The Save menu also offers **Select all filtered lines** and **Clear selections**.
- **Everything displayed:** choose **Save filtered logs**. While paused, this uses the frozen display, not queued incoming lines.

Files are saved as UTF-8 `.txt` in the public **Downloads** folder when Android permits it. Android 10+ uses MediaStore; Android 9 requires the launcher to have shared-storage write permission. LogLens reports export errors. Logs may contain account data, identifiers, tokens and other sensitive information; review them before sharing.

## Build

Linux, Android NDK r28c, JDK, Python 3, Android platform `android.jar`, and an R8 jar containing D8:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r28c \
ANDROID_JAR=/path/to/android.jar \
R8_JAR=/path/to/r8.jar ./build.sh
```

The build produces `artifacts/libLogLens.so` and `artifacts/LogLens.levipack`. The loader uses `InMemoryDexClassLoader` and the launcher's internal `InbuiltOverlayManager`, so compatibility depends on the launcher build.

## License

LogLens is available under the [MIT License](LICENSE).
