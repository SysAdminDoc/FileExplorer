# Changelog

All notable changes to FileExplorer will be documented in this file.

## [v1.2.0] - 2026-05-18

### Added
- **Theme Selector** ([#1](https://github.com/SysAdminDoc/FileExplorer/issues/1)) — Settings → Theme now offers five modes: System default, Light, Dark, OLED / True Black (pure-black background for AMOLED power savings), and Material You (wallpaper-derived colors on Android 12+, gracefully falls back to System on older devices). The previous forced-dark posture stated in the README has been retired. Per-mode color scheme implemented in [`core/designsystem/Theme.kt`](core/designsystem/src/main/java/com/explorer/fileexplorer/core/designsystem/Theme.kt) with matching `LightColorScheme`, `DarkColorScheme`, `OledColorScheme`, and Material You dynamic schemes; persistence via the existing DataStore in [`SettingsRepository`](feature/settings/src/main/java/com/explorer/fileexplorer/feature/settings/SettingsScreen.kt) under `THEME_MODE`. `MainActivity` collects the preference at the activity composition root so theme changes apply instantly across the entire navigation stack.

## [v1.1.0] - %Y->- (HEAD -> main, origin/main, origin/HEAD)

- Enable shrinkResources for release build
- ci: add Android build and release workflow
- Removed: remove test
- test github dir
- Removed: remove test file
- test write
- Changed: Update README.md
- upd
