# Changelog

All notable changes to FileExplorer will be documented in this file.

## [Unreleased]

### Added
- Added an independent dual-pane browser layout with per-pane navigation, refresh, sorting, hidden-file filtering, and selection.
- Added long-press drag-and-drop transfers between panes with self/descendant path protection and explicit Copy or Move confirmation.
- Added per-pane tabs with independent selection, close, long-press reorder, and swipe-to-close behavior.
- Added a Storage Analyzer screen with recursive treemap drill-down, duplicate-content groups, progress/cancel scanning, and largest-file list.
- Added batch rename with counter, date, parent, and regex capture-group tokens, collision validation, and live preview before a two-phase rename.
- Added a queued transfer manager with pause/resume, reordering, bandwidth limits, conflict resolution, and text diff previews; browser paste and dual-pane transfers now enqueue work.
- Added a system-gated DocumentsProvider for SAF browsing, search, recent files, open/create, rename, delete, copy, and move access to local storage.
- Added an authenticated HTTP/FTP LAN share server with directory listings, file transfers, safe mutations, a foreground notification, and a Quick Settings toggle.
- Added per-file AES-256-GCM encryption with an Android Keystore key and biometric-gated decryption from the browser.
- Added optional Shizuku/Sui UserService access for scoped Android/data and Android/obb browsing and file operations.
- Added an adaptive large-screen browser workspace with places, files, and preview panes plus keyboard and mouse input affordances.
- Added Room-backed per-directory view memory for grid/list mode, sorting, folder ordering, and visible file columns, including a 2-to-3 schema migration.
- Added read-only RAR browsing and extraction, including path-traversal protection for extracted entries.
- Added a versioned AIDL plugin/add-on API with manifest discovery, isolated binder calls, and URI-backed plugin repositories.
- Added MediaStore-backed Collections for Photos, Videos, Music, Documents, Downloads, and APKs.
- Added a paginated hex editor with byte editing through 64 MiB and read-only inspection for larger files.
- Added APK analyzer details for manifests, permissions, signatures, DEX method counts, and ZIP directory sizes.
- Added native PDF page rendering and bounded DOCX/XLSX document previews from the browser.
- Added configurable swipe-left/right actions for browser list rows, including safe delete confirmation and cut-to-move.
- Added a root-only module browser for Magisk, KernelSU, and APatch, with module status toggles and manager-backed ZIP installation.
- Added exported Tasker / Automate intent actions for copy, move, archive creation, and saved-connection network uploads, with request completion broadcasts.
- Added Room-backed integrity watches for files and directory trees, with browser selection support, periodic WorkManager scans, drift statuses, and notifications.
- Added Room-backed file tags, browser assignment and management, and AND-combination tag filters in Search.
- Added USB OTG browsing through UsbManager mass-storage detection, persisted SAF tree grants, and DocumentFile read/write operations.
- Added a root-only encrypted-volume workflow for mounting and unmounting existing gocryptfs or EncFS volumes with protected passphrase handling.

## [v1.3.3] - 2026-06-30

### Added
- Added a local trash bin backed by `.FileExplorer-Trash/` metadata and payload folders.
- Added normal browser deletes that move selected items to Trash, plus long-press and overflow permanent-delete actions.
- Added a drawer-accessible Trash screen with restore, permanent delete, empty-trash, refresh, and automatic TTL purge.
- Added configurable trash retention presets in Settings.
- Added JVM regression tests for move-to-trash, restore, permanent delete, and TTL purge behavior.

## [v1.3.2] - 2026-06-30

### Security
- Replaced permissive SFTP host verification with app-private known_hosts verification.
- Added a first-connect SFTP host-key trust prompt with SHA-256 fingerprint display and changed-key warnings.
- Added regression tests for unknown, trusted, changed, and non-default-port SFTP host keys.

## [v1.3.1] - 2026-06-30

### Security
- Encrypted saved network connection passwords with Android Keystore-backed AES-GCM before Room writes.
- Added automatic migration for existing plaintext network connection passwords when the connection list is loaded.
- Added Keystore-encrypted cloud account persistence for accounts marked Stay signed in.
- Added connection-manager regression tests for encrypted saves, plaintext migration, and decrypted connection emission.

## [v1.3.0] - 2026-06-27

### Changed
- Upgraded security-sensitive and platform dependencies: compile SDK 36, AGP 8.13.0, Gradle 8.14.4, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2026.06.00, Hilt 2.58, AndroidX Hilt 1.3.0, Room 2.8.4, Coil 3.3.0, sshj 0.40.0, BouncyCastle 1.84, Commons Compress 1.28.0, Commons Net 3.13.0, sardine-android 0.9, and zip4j 2.11.6.

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
