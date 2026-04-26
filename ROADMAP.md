# ROADMAP

Backlog for FileExplorer (Android). Core feature set is already dense; this focuses on the gaps
versus Solid Explorer, MiXplorer, Material Files, and power-user workflows.

## Planned Features

### File operations
- **Split-pane / dual-pane view** — drag-copy between two directories in one screen. The single
  biggest missing feature versus Solid Explorer and Total Commander.
- **Tabbed browsing** — multiple tabs per pane, long-press to reorder, swipe to close.
- **Queued transfer manager UI** — pausable, reorderable, per-task bandwidth limit; currently
  transfers are fire-and-forget through the foreground service.
- **Conflict resolution dialog upgrade** — per-file Skip/Replace/Rename/Keep Both with "apply to
  all" stickiness and a diff preview for text files.
- **Zstandard + Brotli archive support** in addition to the existing set.
- **Tar.zst on-device creation** with progress and configurable compression level.

### Navigation / discovery
- **Storage analyzer** — recursive size treemap with drill-down, duplicate finder, big-file list.
  DiskUsage-style. Expected in any modern Android file manager.
- **Real-time directory size** in list view (opt-in, background computed).
- **Saved searches** — named regex + path filters, pinned to the drawer.
- **Recent locations** — not just recent files, but recent directories visited.

### Network / cloud
- **pCloud + Box + Nextcloud** adapters (Sardine already covers generic WebDAV; add provider
  tokens and typed endpoints).
- **Rclone-config import** — read an existing `rclone.conf` and offer its remotes as mounts.
- **S3 / Backblaze B2** via AWS SDK for Kotlin.
- **SSHFS-style mount** exposing SFTP as a virtual folder for apps (Android Storage Access
  Framework document provider).
- **Server-side copy** for WebDAV/SMB where protocol supports it — already done for WebDAV;
  extend to SMB3 `FSCTL_SRV_COPYCHUNK`.

### Root / advanced
- **Magisk module browser + manager** — install/remove zips, list modules, toggle state.
- **KernelSU + APatch** compatibility for the root shell layer.
- **SELinux context editor** with policy suggestions (beyond read-only display).
- **Binary hex editor** for small files; fall back to read-only for > 64 MB.
- **Integrated APK analyzer** — manifest, permissions, signatures, shared UID, size breakdown.

### UI / UX
- **Material You dynamic color** option alongside the forced OLED black.
- **Gesture customization** — assign swipe-left / swipe-right on list items to any action.
- **Per-directory view memory** — grid vs list, sort order, column set remembered per path.
- **Thumbnail cache control** — size cap, location, purge button in Settings.
- **Quick Share (Nearby Share)** integration via intent.

### Safety
- **Trash bin with TTL** — deletes go to a `.Trash` folder with a 30-day auto-purge setting
  instead of immediate delete unless shift/long-press override.
- **Cloud-token re-encryption at rest** — README already flags tokens are in-memory only; add
  Keystore-backed persistence for opt-in "stay signed in".

### Distribution
- **F-Droid publication** including the reproducible-build metadata.
- **Play Store AAB** with Play Asset Delivery for optional modules (root module as a conditional
  install only when user enables root mode).

## Competitive Research

- **Solid Explorer** — dual-pane, vault, cloud & network coverage. Sets the UX bar.
- **MiXplorer Silver** — unmatched extensibility (add-ons, symlink support, themes). Inspiration
  for a plugin SDK, post-1.x.
- **Material Files (Hai Zhang)** — open-source Material You reference implementation; worth
  borrowing navigation patterns.
- **Total Commander for Android** — dual-pane + plugin system; free with no ads.
- **FX File Explorer** — web-access feature (manage phone files from desktop browser) is a
  standout worth cloning.
- **Amaze File Manager** — open-source baseline, showcases root + SMB done cleanly.

## Nice-to-Haves

- **Tasker / Automate intents** — expose copy/move/zip/upload as intent actions.
- **Android Auto file picker** for exporting attachments to car displays.
- **Tablet layout** — true 3-column (tree + list + preview) on large screens.
- **Chromebook / DeX large-window** polish — keyboard shortcuts, right-click menus.
- **PDF and Office preview** via embedded Pdfium + local render of DOCX/XLSX via Apache POI.
- **Scriptable batch rename** with regex + tokens + live preview.
- **File integrity database** — track SHA-256 of selected paths and alert on drift (ransomware
  tripwire for user-selected sensitive folders).

## Open-Source Research (Round 2)

### Related OSS Projects
- **Material Files** — https://github.com/zhanghai/MaterialFiles — Mature Android FM with full root support via `libsu`, archive (`libarchive`), FTP/SFTP/SMB/WebDAV, Linux-aware (symlinks, perms, SELinux).
- **Prism File Explorer** — https://github.com/Raival-e/Prism-File-Explorer — Pure Jetpack Compose + Material 3 FM; closest peer in architecture.
- **Amaze File Manager** — https://github.com/TeamAmaze/AmazeFileManager — Longest-running OSS FM; root explorer, AES encrypt/decrypt, multi-tab, app manager.
- **AnExplorer** — https://github.com/1hakr/AnExplorer — All-device target (phone/tablet/TV/Wear/Chromebook); DocumentsProvider integration.
- **FileManagerSphere** — https://github.com/Ruan625Br/FileManagerSphere — Lightweight Compose FM; good minimal baseline.
- **msfilemanager** — https://github.com/MrShieh-X/msfilemanager — Alt OSS FM worth reading for archive handling.

### Features to Borrow
- `libsu` root IPC model from `Material Files` — sticky root shell with `Shell.cmd()` + `RootFile` abstraction; safer than re-spawning `su` per op.
- `libarchive`-based archive handling (`Material Files`) — supports 7z/rar/tar.xz read+write; JDK `ZipFile` is not enough.
- FTP/SFTP/SMB/WebDAV remote mounts (`Material Files`) — uses `apache-mina-sftp` + `jcifs-ng`; drop-in replacements for DocumentsProvider gaps.
- AES encrypt/decrypt per-file (`Amaze`) — `javax.crypto` AES-256-GCM + Android Keystore keying.
- SELinux context display + `chcon` (`Material Files`) — advanced root users expect this.
- DocumentsProvider export (`AnExplorer`) — other apps can pick files *from* your FM via SAF.
- Per-mime default-app override dialog with persistence (all four) — a must-have; stock Android 14+ removed this.

### Patterns & Architectures Worth Studying
- **Per-operation worker queue with Foreground Service** (`Material Files`, `Amaze`): copy/move/zip dispatched to a `ForegroundService` with a notification; survives process death and doze.
- **Path abstraction (`FileProvider` vs `DocumentFile` vs `java.io.File` vs `RootFile`)** (`Material Files`): single `Path` sealed interface that dispatches to the right backend. Removes conditional spaghetti from call sites.
- **Coil thumbnailer with video/PDF/APK plugins** (`Prism`): register custom `Fetcher`/`Decoder` for APK icons (via `PackageManager.getPackageArchiveInfo`), PDF pages (via `PdfRenderer`), video frames (via `MediaMetadataRetriever`).
- **Compose LazyGrid + `remember { mutableStateMapOf() }` selection model** (`Prism`, `FileManagerSphere`): avoids `MultiSelectionAdapter` boilerplate and scales to 10k+ items.
