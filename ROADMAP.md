# ROADMAP

> FileExplorer v1.2.0 | Updated 2026-05-19 | Research-driven, 50+ sources
>
> Core feature set is dense (local/root/archive/network/cloud/editor/apps/security).
> This roadmap closes the gaps versus Solid Explorer, MiXplorer Silver, Material Files,
> and power-user workflows. Every item is traceable to a source in the Appendix.

---

## Tier Definitions

| Tier | Meaning |
|------|---------|
| **Now** | High impact, achievable in the current release cycle. Prerequisite for credibility. |
| **Next** | Lands after Now tier. High value but depends on Now items or needs more design. |
| **Later** | Validated need, lower urgency or high effort. Revisit after Next ships. |
| **Under Consideration** | Interesting but unvalidated, niche, or needs prototype first. |
| **Rejected** | Evaluated and declined with reasoning. |

---

## Now (v1.3.0 -- v1.4.0)

### N-01: Dependency upgrades and security hardening
Upgrade all behind-version dependencies. Several have known CVEs.

| Library | Current | Target | Why |
|---------|---------|--------|-----|
| BouncyCastle | 1.78.1 | 1.84 | 5 CVEs (LDAP injection, timing attack, AEAD, GOST) |
| sshj | 0.38.0 | 0.40.0 | Removes vulnerable EdDSA dep (CVE-2020-36843), fixes heartbeat bug |
| Commons Compress | 1.27.1 | 1.28.0 | Zstandard-in-ZIP, gzip fixes |
| Commons Net | 3.11.1 | 3.13.0 | Timeout/parsing fixes |
| zip4j | 2.11.5 | 2.11.6 | Bugfixes |
| sardine-android | 0.8 | 0.9 | WebDAV improvements |
| Hilt | 2.53.1 | 2.57.1 | Kotlin 2.x compat |
| Compose BOM | 2024.12 | 2025.x+ | 17 months of Compose fixes + new components |
| Room | 2.6.1 | 2.8.x | Auto-migration improvements, multiprocess |
| AGP | 8.7.3 | 8.9.x or 9.x | R8 improvements, build speed |
| Coil | 2.7.0 | 3.x | Compose-first API, video/SVG/GIF decoders |

- **Impact:** 5/5 (security). **Effort:** 2/5 (version bumps + migration testing).
- **Source:** NVD, GitHub Advisories, Maven Central changelogs.

### N-02: Encrypt stored passwords with Android Keystore
`ConnectionEntity.password` is plaintext (`// TODO: encrypt via Android Keystore` at `Entities.kt:41`).
Wrap with `EncryptedSharedPreferences` or raw `KeyStore` + AES-256-GCM. Cloud tokens (currently
in-memory only) should also get opt-in Keystore-backed persistence for "stay signed in."

- **Impact:** 5/5 (security table-stakes). **Effort:** 2/5.
- **Source:** Existing TODO in code; Android Keystore best practices; Solid Explorer and MiXplorer both encrypt credentials at rest.

### N-03: SFTP known_hosts verification
Replace `PromiscuousVerifier()` (`SftpFileRepository.kt:37`) with proper known_hosts file support.
First connect prompts user to accept the host key; subsequent connects verify against stored fingerprint.

- **Impact:** 4/5 (security). **Effort:** 2/5.
- **Source:** Existing TODO in code; sshj supports `OpenSSHKnownHosts` natively.

### N-04: Trash bin with configurable TTL
Deletes go to `.FileExplorer-Trash/` with a 30-day auto-purge (configurable in Settings).
Long-press delete bypasses trash for permanent deletion. Trash screen accessible from drawer.
Only Amaze offers this among OSS file managers; Solid Explorer and MiXplorer both have it.
Community calls this a top safety concern.

- **Impact:** 5/5 (data safety, #6 community request). **Effort:** 3/5.
- **Source:** Reddit r/androidapps, r/fossdroid complaints about permanent delete; Amaze v3.11 implementation; Solid Explorer trash feature.

### N-05: Wire FileRepositoryFactory for network/cloud URI routing
`FileRepositoryFactory.getRepository()` only routes to local/root repos. The commented-out
`smb://`, `sftp://`, `gdrive://` URI scheme routing must be implemented so the browser can
seamlessly navigate into network shares and cloud folders from the main file list.

- **Impact:** 5/5 (currently network/cloud screens are isolated islands). **Effort:** 3/5.
- **Source:** Material Files' `Path` sealed interface pattern; existing commented code in `FileRepositoryFactory.kt:24-26`.

### N-06: Fix LocalFileRepository.search() streaming bug
`search()` uses `walkFileTree` visitor which can't emit to a Flow. The working
`searchStreaming()` method exists but isn't wired as the default. Replace `search()` with
the streaming implementation.

- **Impact:** 3/5 (search is broken for non-streaming callers). **Effort:** 1/5.
- **Source:** Code inspection — `LocalFileRepository.kt:229-251` has a dead code path.

### N-07: Predictive back gesture support
Android 14+ enforces predictive back. The app uses `BackHandler` in Compose which works but
doesn't show the predictive animation. Adopt `OnBackPressedCallback` with `handleOnBackProgressed`
for cross-fade/shared-element preview during the back gesture.

- **Impact:** 3/5 (platform compliance). **Effort:** 2/5.
- **Source:** Android 14 behavior changes; developer.android.com predictive back gesture guide.

### N-08: Foreground service timeout handling
`TransferService` uses `dataSync` FGS type which has a 6-hour timeout on Android 15+.
Implement `Service.onTimeout(int, int)` callback to gracefully save progress and notify user.
For operations exceeding 6 hours, consider chunking or using WorkManager.

- **Impact:** 3/5 (crashes on long transfers). **Effort:** 2/5.
- **Source:** Android 15 FGS type restrictions; developer.android.com/develop/background-work/services/fgs/service-types.

---

## Next (v1.5.0 -- v1.7.0)

### X-01: Dual-pane / split-pane view
Drag-and-drop between two directory panels in one screen. The #1 most-requested power-user
feature across every community. Solid Explorer, X-plore, MiXplorer, Total Commander, and
Ghost Commander all have it. No mainstream FOSS file manager on F-Droid offers it.

- **Impact:** 5/5. **Effort:** 5/5 (new navigation paradigm, gesture handling, state management).
- **Dependency:** None, but benefits from X-02 (tabs).
- **Source:** XDA vote thread (MiXplorer vs Solid Explorer); Reddit r/Android; Computerworld review.

### X-02: Tabbed browsing
Multiple tabs per pane, long-press to reorder, swipe to close. MiXplorer offers unlimited tabs.
Amaze has multi-tab. Material Files does not. Users repeatedly cite tabs as essential for
productivity alongside dual-pane.

- **Impact:** 4/5. **Effort:** 3/5.
- **Source:** Reddit r/androidapps; MiXplorer feature list; Amaze multi-tab.

### X-03: Storage analyzer with duplicate finder
Recursive size treemap (DiskUsage-style) with drill-down, duplicate finder, and big-file list.
Expected in any modern Android file manager. Solid Explorer, X-plore, and Files by Google all
have storage analysis. No OSS FM does it well.

- **Impact:** 5/5 (table-stakes). **Effort:** 4/5.
- **Source:** DiskUsage (github.com/IvanVolosyuk/diskusage); Solid Explorer storage analyzer; X-plore disk map.

### X-04: Batch rename with regex + tokens + live preview
Variable-based rename (counter, date, parent folder name, regex capture groups). Live preview
of all renames before committing. Only Solid Explorer and MiXplorer offer this among Android FMs.
Frequently requested by photographers and content creators.

- **Impact:** 4/5. **Effort:** 3/5.
- **Source:** Reddit community requests; Computerworld review; Solid Explorer batch rename; MiXplorer regex rename.

### X-05: Queued transfer manager UI
Pausable, reorderable transfer queue with per-task bandwidth limit, per-file conflict resolution
(Skip/Replace/Rename/Keep Both with "apply to all"), and a diff preview for text file conflicts.
Currently transfers are fire-and-forget through the foreground service.

- **Impact:** 4/5. **Effort:** 4/5.
- **Source:** Material Files per-operation worker queue pattern; Solid Explorer transfer manager.

### X-06: DocumentsProvider (SAF export)
Implement `DocumentsProvider` so other apps can pick files *from* FileExplorer via the system
file picker. Also enables SSHFS-style virtual mounts where remote shares appear as local storage
to other apps. AnExplorer, Material Files, and Ghost Commander all implement this.

- **Impact:** 4/5 (ecosystem integration). **Effort:** 3/5.
- **Source:** AnExplorer DocumentsProvider; Material Files SAF integration; Android SAF documentation.

### X-07: Built-in FTP/HTTP server
Turn the phone into an FTP or HTTP server for desktop file access. Quick Settings tile to
start/stop. Material Files has FTP server with QS tile. Solid Explorer has both FTP and web
server. FX File Explorer's web access is a standout feature.

- **Impact:** 4/5 (unique vs most OSS FMs). **Effort:** 3/5.
- **Source:** Material Files FTP server; Solid Explorer web sharing; FX File Explorer web access.

### X-08: Per-file AES-256 encryption/decryption
Encrypt individual files with AES-256-GCM keyed via Android Keystore. Decrypt with biometric
prompt. Amaze has this (javax.crypto). Distinct from vault (which hides files in app-private
storage) — this encrypts files in-place so they can be stored on cloud/NAS.

- **Impact:** 3/5. **Effort:** 3/5.
- **Source:** Amaze AES encrypt/decrypt; MiXplorer Aescrypt format support.

### X-09: Shizuku support for Android/data access
Shizuku enables privileged operations without root by delegating to a debug shell service.
Allows reading `/Android/data/` and `/Android/obb/` on Android 11+ without
`MANAGE_EXTERNAL_STORAGE`. Solid Explorer v3.4.10 added Shizuku support. Growing community
demand since scoped storage enforcement.

- **Impact:** 4/5. **Effort:** 3/5.
- **Source:** github.com/RikkaApps/Shizuku; Solid Explorer v3.4.10 changelog; Reddit r/Android discussions.

### X-10: Large screen / foldable / DeX layout
3-column layout on tablets and foldables (tree + list + preview). Keyboard shortcuts,
right-click context menus, trackpad scrolling. Android 14/15 large screen guidance.
Solid Explorer and AnExplorer both support large screens well.

- **Impact:** 3/5 (growing form factor). **Effort:** 4/5.
- **Source:** Android large screen guidance; Chromebook/DeX community requests; AnExplorer all-device targeting.

### X-11: Per-directory view memory
Remember grid vs list, sort order, and column visibility per directory path. Persisted in Room.
MiXplorer does this; most other FMs reset view preferences on every navigation.

- **Impact:** 3/5. **Effort:** 2/5.
- **Source:** MiXplorer per-folder settings; existing ROADMAP item.

### X-12: RAR archive read support
Add `libarchive` JNI or `junrar` for RAR decompression (read-only — RAR creation requires
proprietary license). RAR is still widely used. Material Files uses `libarchive` for this.
MiXplorer's MiX Archive add-on handles RAR.

- **Impact:** 3/5 (common format). **Effort:** 2/5.
- **Source:** Material Files libarchive; MiX Archive add-on; user complaints about RAR gaps.

---

## Later (v2.0+)

### L-01: Plugin / add-on architecture
ContentProvider or AIDL-based plugin API allowing third-party extensions (cloud providers,
archive formats, tools). MiXplorer and Total Commander both use this pattern. Ghost Commander
uses separate APK plugins for FTP/SFTP/SMB/cloud. Enables community contribution without
bloating the core app.

- **Impact:** 4/5 (ecosystem). **Effort:** 5/5 (API design, security sandboxing, lifecycle).
- **Source:** MiXplorer add-on system; Total Commander plugin architecture; Ghost Commander plugins.

### L-02: Collections / smart categories
Auto-grouped views: Photos, Videos, Music, Documents, Downloads, APKs. Queries MediaStore
for indexed content. Solid Explorer's Collections is a key differentiator.

- **Impact:** 3/5. **Effort:** 3/5.
- **Source:** Solid Explorer Collections feature.

### L-03: Additional cloud providers
pCloud, Box, Nextcloud (typed WebDAV), Mega, S3/Backblaze B2 (AWS SDK for Kotlin),
MediaFire, Yandex Disk. MiXplorer supports 19 providers; Solid Explorer supports 10+.
Current implementation has Google Drive, Dropbox, OneDrive with placeholder auth.

- **Impact:** 3/5. **Effort:** 4/5 (per-provider OAuth + API integration).
- **Source:** MiXplorer 19 providers; Solid Explorer cloud list; existing ROADMAP item.

### L-04: Rclone config import
Read an existing `rclone.conf` and offer its remotes as browsable mounts. Power users
already have rclone configured; importing saves re-entering credentials.

- **Impact:** 2/5 (niche but sticky). **Effort:** 2/5.
- **Source:** Existing ROADMAP item; rclone community.

### L-05: Binary hex editor
Hex view for files up to 64 MB; read-only for larger files. Useful for forensics and
embedded development. X-plore has a hex viewer.

- **Impact:** 2/5 (niche). **Effort:** 3/5.
- **Source:** X-plore hex viewer; existing ROADMAP item.

### L-06: Integrated APK analyzer
Manifest viewer, permissions list, signature info, shared UID, size breakdown per directory,
DEX method count. Goes beyond the current App Manager's basic info display.

- **Impact:** 2/5 (developer/power-user). **Effort:** 3/5.
- **Source:** X-plore app analyzer; existing ROADMAP item.

### L-07: PDF and document preview
Embedded PDF viewer via PdfRenderer (Android native) and lightweight DOCX/XLSX preview.
MiXplorer's MiX PDF add-on and X-plore's PDF viewer are reference implementations.

- **Impact:** 3/5. **Effort:** 3/5.
- **Source:** MiXplorer MiX PDF; X-plore; existing ROADMAP item.

### L-08: Gesture customization
Assign swipe-left / swipe-right on list items to configurable actions (delete, share, compress,
move to folder, etc.). Solid Explorer offers this.

- **Impact:** 2/5. **Effort:** 3/5.
- **Source:** Solid Explorer gesture actions; existing ROADMAP item.

### L-09: Magisk / KernelSU / APatch module browser
List installed modules, toggle state, install from ZIP. KernelSU-Next uses libsu internally.
APatch bypasses SELinux via KernelPatch (libsu optional).

- **Impact:** 2/5 (root niche). **Effort:** 3/5.
- **Source:** Existing ROADMAP item; libsu v6 docs; KernelSU-Next source.

### L-10: Tasker / Automate intent actions
Expose copy, move, zip, upload as intent actions with extras for source/destination/format.
Enables automation workflows.

- **Impact:** 2/5 (power-user). **Effort:** 2/5.
- **Source:** Existing ROADMAP item; Tasker plugin documentation.

### L-11: File integrity database (ransomware tripwire)
Track SHA-256 of user-selected paths. Alert on drift (hash mismatch = possible tampering).
Periodically recompute via WorkManager. No competitor offers this.

- **Impact:** 3/5 (novel, high-value for security-conscious users). **Effort:** 3/5.
- **Source:** Existing ROADMAP item; novel feature (leapfrog).

### L-12: Tag-based file organization
User-defined tags applied to files, stored in Room DB, searchable via tag combinations.
TagSpaces is the only Android app offering this. Emerging community request.

- **Impact:** 2/5 (emerging). **Effort:** 3/5.
- **Source:** TagSpaces app; Reddit community discussions.

### L-13: EncFS / encrypted volume support
Mount EncFS or gocryptfs volumes in-place. MiXplorer supports EncFS across all storage
backends (local, cloud, network). Enables accessing encrypted cloud storage.

- **Impact:** 2/5 (privacy niche). **Effort:** 4/5.
- **Source:** MiXplorer EncFS; gocryptfs Android.

### L-14: USB OTG support
Read/write USB drives connected via OTG adapter. Uses Android UsbManager + DocumentFile
for scoped-storage-safe access. Amaze has USB OTG support.

- **Impact:** 3/5. **Effort:** 3/5.
- **Source:** Amaze USB OTG; Android UsbManager documentation.

### L-15: Wi-Fi Direct / Quick Share integration
Send files to nearby devices via Wi-Fi Direct or Quick Share (Nearby Share successor).
Uses `android.nearby` APIs.

- **Impact:** 2/5. **Effort:** 3/5.
- **Source:** Android Nearby Share API; existing ROADMAP item.

### L-16: Media casting (Chromecast)
Cast local media (photo/video/audio) to Chromecast or DLNA receivers directly from the file
browser. Solid Explorer offers this as a paid plugin.

- **Impact:** 2/5. **Effort:** 3/5.
- **Source:** Solid Explorer Chromecast plugin.

### L-17: i18n / multi-language support
Externalize all user-facing strings to `strings.xml` with initial translations for the top 10
Android locales (en, es, pt-BR, de, fr, ja, ko, zh-CN, ru, ar). Compose already supports RTL
via `supportsRtl="true"` in the manifest. No competitor OSS FM supports more than 5 languages
well; Solid Explorer supports ~40.

- **Impact:** 3/5 (distribution reach). **Effort:** 3/5.
- **Source:** Solid Explorer localization; Google Play global audience data.

### L-18: Thumbnail cache control
Size cap, storage location, and purge button in Settings. Coil 3.x provides disk cache
configuration. Prevents thumbnail cache from growing unbounded on devices with limited storage.

- **Impact:** 2/5. **Effort:** 1/5.
- **Source:** Existing ROADMAP item; Coil disk cache API.

### L-19: Real-time directory size
Opt-in setting to show recursive directory sizes in list view. Background-computed via coroutines
with a per-directory LRU cache. DiskUsage and X-plore both show this.

- **Impact:** 3/5. **Effort:** 2/5.
- **Source:** Existing ROADMAP item; X-plore directory size display; community requests.

### L-20: Saved searches
Named regex + path filter combinations pinned to the navigation drawer. Quick recall for
frequently used search patterns.

- **Impact:** 2/5. **Effort:** 2/5.
- **Source:** Existing ROADMAP item.

### L-21: Recent locations
Track recently visited directories (not just recent files). Accessible from drawer.
Separate from bookmarks — automatic, ordered by recency.

- **Impact:** 2/5. **Effort:** 1/5.
- **Source:** Existing ROADMAP item.

### L-22: F-Droid publication
Reproducible build metadata, no proprietary dependencies in the F-Droid flavor, removal of
Google Play Services auth (already optional). F-Droid is the primary distribution channel for
privacy-focused users.

- **Impact:** 3/5 (distribution reach). **Effort:** 3/5.
- **Source:** Existing ROADMAP item; F-Droid submission guidelines.

### L-23: Play Store AAB with conditional install
Root module as a conditional install (only downloaded when user enables root mode). Reduces
base APK size for non-root users.

- **Impact:** 2/5. **Effort:** 3/5.
- **Source:** Existing ROADMAP item; Play Asset Delivery documentation.

---

## Under Consideration

### U-01: Kotlin Multiplatform for shared logic
Share file operation logic (model, repository interfaces, archive handling) across Android and
potential desktop (JVM) builds. Feasibility depends on how much code is Android-specific.
kotlinx-io and Okio provide multiplatform I/O primitives.

- **Source:** awesome-kotlin KMP resources; kotlinx-io; Okio.

### U-02: Built-in terminal emulator
Inline terminal with file-path awareness (cd to current directory). Adjacent to hex editor and
root features. Termux integration via intent may be simpler.

- **Source:** Community requests; Termux; MiXplorer shell feature.

### U-03: EPUB / e-book reader
Lightweight EPUB renderer. MiXplorer's Codecs add-on handles this. Very niche for a file
manager.

- **Source:** MiXplorer Codecs; community request signal: low.

### U-04: SELinux context editor with policy suggestions
Beyond the current read-only display. Would require root + deep SELinux knowledge.
Risky to expose to casual users.

- **Source:** Existing ROADMAP item; Material Files chcon support.

### U-05: NFS client
No Android file manager currently offers NFS. Requested on Hacker News. Would need a
native NFS library (none exist for Android Kotlin).

- **Source:** HN comment requesting NFS; no existing implementation to reference.

### U-06: Server-side copy for SMB3
Extend server-side copy (already done for WebDAV) to SMB3 `FSCTL_SRV_COPYCHUNK`.
Significant performance gain for large file copies on the same NAS.

- **Source:** Existing ROADMAP item; SMB3 protocol specification.

### U-07: Syncthing integration
Detect Syncthing-synced folders and show sync status badges. Would require Syncthing's
REST API or reading its config.

- **Source:** github.com/syncthing/syncthing-android; adjacent tool research.

---

## Rejected

### R-01: Android Auto file picker
**Rejected.** Android Auto restricts apps to navigation, messaging, and media. File manager
apps cannot display UI on the car screen. Google's policy explicitly disallows this.

### R-02: Chromecast as a first-party feature
**Rejected for Now/Next.** Moved to Later (L-16) as a stretch goal. The Google Cast SDK
adds significant APK size and proprietary dependency that conflicts with F-Droid goals (L-19).

### R-03: Full Brotli archive support
**Rejected.** Brotli is a compression algorithm, not an archive format. It has no container
for multiple files. `.tar.br` is theoretically possible but has near-zero real-world usage.
Zstandard (already supported via Commons Compress) covers the modern-compression-in-archives
use case.

### R-04: Custom icon packs
**Rejected.** High effort, low impact. Solid Explorer sells icon packs as paid add-ons, but
the Material 3 icon set is sufficient and consistent. Would dilute the design language.

---

## Competitive Landscape (Round 3 -- 2026-05-19)

### OSS Competitors

| App | Stars | License | Key Differentiators | Gaps vs FileExplorer |
|-----|-------|---------|--------------------|--------------------|
| [Material Files](https://github.com/zhanghai/MaterialFiles) | 8.3K | GPL-3.0 | NIO2+native syscalls, libsu root, libarchive, FTP server, DocumentsProvider | No cloud, no dual-pane, no editor, no biometric lock |
| [Amaze](https://github.com/TeamAmaze/AmazeFileManager) | 6.2K | GPL-3.0 | Rust FFI file ops, HybridFile abstraction, AES encrypt, USB OTG, trash bin, multi-tab | Cloud behind paid plugin, no syntax editor |
| [Fossify FM](https://github.com/FossifyOrg/File-Manager) | ~2K | GPL-3.0 | Fork of Simple FM, clean Kotlin, active maintenance | No network, no cloud, no root, no archives |
| [Prism](https://github.com/Raival-e/Prism-File-Explorer) | ~200 | MIT | Pure Compose+M3, Coil thumbnailer for APK/PDF/video | Minimal features, no network/cloud/root |
| [AnExplorer](https://github.com/1hakr/AnExplorer) | ~1K | Apache-2.0 | All-device (TV/Wear/Chromebook), DocumentsProvider | Limited features, no root, no archives |
| [Ghost Commander](https://sourceforge.net/p/ghostcommander/) | N/A | GPL-2.0 | Dual-panel, plugin system (FTP/SFTP/SMB/WebDAV/cloud) | Java, dated UI, plugin installation friction |

### Commercial Competitors

| App | Price | Key Advantages Over FileExplorer |
|-----|-------|-------------------------------|
| [Solid Explorer](https://play.google.com/store/apps/details?id=pl.solidexplorer2) | $2.99 | Dual-pane, Collections, Storage Analyzer, batch rename, FTP/HTTP server, Chromecast, Shizuku, plugin marketplace, 10+ cloud providers |
| [MiXplorer Silver](https://play.google.com/store/apps/details?id=com.mixplorer.silver) | $5.99 | Unlimited tabs, 19 cloud providers, EncFS, custom themes/fonts, EPUB reader, code editor, PDF viewer, regex batch rename |
| [FX File Explorer](https://play.google.com/store/apps/details?id=nextapp.fx) | Free+IAP | Web access (phone-as-server), media player, WiFi file transfer |
| [X-plore](https://play.google.com/store/apps/details?id=com.lonelycatgames.Xplore) | Free+IAP | Forced dual-pane, disk map, PDF viewer, SSH shell, hex viewer, app analyzer |

### What FileExplorer Already Beats Them On

FileExplorer v1.2.0 is the only Android file manager (OSS or commercial) that ships ALL of:
- Jetpack Compose + Material 3 UI (modern stack)
- Full root access via libsu
- Archives (ZIP/7z/TAR/GZ/BZ2/XZ/Zstandard) with virtual folder browsing
- 4 network protocols (SMB/SFTP/FTP/WebDAV)
- 3 cloud providers (Google Drive/Dropbox/OneDrive)
- Built-in syntax-highlighting text editor
- Biometric lock + encrypted vault + secure delete
- App manager with APK sharing
- 5-mode theme system (System/Light/Dark/OLED/Material You)
- MIT license (most permissive)
- Zero configuration required

Material Files lacks cloud/editor/security. Amaze lacks editor/modern UI. Solid Explorer is
closed-source and paid. MiXplorer is not on F-Droid/Play Store (free version).

---

## Appendix: Research Sources

### OSS Projects
1. Material Files -- https://github.com/zhanghai/MaterialFiles (8.3K stars, GPL-3.0)
2. Amaze File Manager -- https://github.com/TeamAmaze/AmazeFileManager (6.2K stars, GPL-3.0)
3. Fossify File Manager -- https://github.com/FossifyOrg/File-Manager (GPL-3.0)
4. Prism File Explorer -- https://github.com/Raival-e/Prism-File-Explorer (MIT)
5. AnExplorer -- https://github.com/1hakr/AnExplorer (Apache-2.0)
6. Ghost Commander -- https://sourceforge.net/p/ghostcommander/code/ (GPL-2.0)
7. FileManagerSphere -- https://github.com/Ruan625Br/FileManagerSphere
8. DiskUsage -- https://github.com/IvanVolosyuk/diskusage
9. XFiles -- https://github.com/pgp/XFiles
10. Little File Explorer -- https://github.com/martinmimigames/little-file-explorer
11. lrkFM -- https://github.com/lfuelling/lrkFM

### Commercial Apps
12. Solid Explorer -- https://play.google.com/store/apps/details?id=pl.solidexplorer2
13. MiXplorer Silver -- https://play.google.com/store/apps/details?id=com.mixplorer.silver
14. FX File Explorer -- https://play.google.com/store/apps/details?id=nextapp.fx
15. X-plore -- https://play.google.com/store/apps/details?id=com.lonelycatgames.Xplore
16. Cx File Explorer -- https://play.google.com/store/apps/details?id=com.cxinventor.file.explorer
17. Total Commander -- https://play.google.com/store/apps/details?id=com.ghisler.android.TotalCommander

### Community
18. XDA: Vote for your Favorite File Manager -- https://xdaforums.com/t/vote-for-your-favorite-file-manager-mixplorer-vs-solid-explorer-vs-anything-else.4751063/
19. XDA: Google's file manager still lacks essential functionality -- https://xdaforums.com/t/googles-file-manager-still-lacks-essential-functionality-in-2025.4713588/
20. HN: Material Files discussion -- https://news.ycombinator.com/item?id=38992689
21. Android Police: 8 best Android file managers 2025 -- https://www.androidpolice.com/best-file-managers/
22. Computerworld: The only Android file manager you need -- https://www.computerworld.com/article/1718187/android-file-manager-apps.html
23. Reddit r/androidapps -- file manager threads (multiple, 2024-2026)
24. Reddit r/fossdroid -- file manager recommendations (multiple, 2024-2026)

### Platform & Standards
25. Android Scoped Storage -- https://source.android.com/docs/core/storage/scoped
26. Android 16 Behavior Changes -- https://developer.android.com/about/versions/16/behavior-changes-16
27. Android Storage 2025 -- https://medium.com/@mahesh31.ambekar/android-storage-in-2025-everything-developers-must-know-photo-picker-to-all-files-access-efeacd152b1e
28. MANAGE_EXTERNAL_STORAGE Policy -- https://support.google.com/googleplay/android-developer/answer/10467955
29. Android FGS Types -- https://developer.android.com/develop/background-work/services/fgs/service-types
30. Android Predictive Back -- https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
31. Android Large Screen Guidance -- https://developer.android.com/guide/topics/large-screens
32. Shizuku -- https://github.com/RikkaApps/Shizuku
33. Android Keystore -- https://developer.android.com/privacy-and-security/keystore

### Dependency Changelogs
34. sshj releases -- https://github.com/hierynomus/sshj/releases
35. BouncyCastle releases -- https://www.bouncycastle.org/latest_releases.html
36. Apache Commons Compress -- https://commons.apache.org/proper/commons-compress/changes-report.html
37. Coil 3.x migration -- https://coil-kt.github.io/coil/upgrading_to_coil3/
38. Jetpack Compose BOM -- https://developer.android.com/develop/ui/compose/bom/bom-mapping

### Security Advisories
39. CVE-2020-36843 (EdDSA) -- sshj transitive dependency
40. CVE-2026-0636 (BouncyCastle LDAP injection)
41. CVE-2026-5598 (BouncyCastle timing attack)
42. CVE-2026-5588 (BouncyCastle crypto algo)
43. CVE-2026-3505 (BouncyCastle PGP AEAD)
44. CVE-2025-14813 (BouncyCastle GOSTCTR)
45. CVE-2024-26308 / CVE-2024-25710 (Commons Compress, fixed in 1.26+)

### Adjacent Tools & Patterns
46. awesome-FOSS-apps -- https://github.com/mvgorcum/awesome-FOSS-apps
47. medevel.com 19 FOSS file managers -- https://medevel.com/19-android-file-manager/
48. TagSpaces -- https://www.tagspaces.org/
49. Syncthing Android -- https://github.com/syncthing/syncthing-android
50. F-Droid file manager category -- https://f-droid.org/en/categories/system/
51. kotlinx-io -- https://github.com/Kotlin/kotlinx-io
52. Okio -- https://github.com/square/okio
