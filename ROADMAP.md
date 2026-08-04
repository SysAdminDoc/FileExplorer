# ROADMAP

> FileExplorer v1.3.3 | Updated 2026-06-30 | Research-driven, 50+ sources
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

## Now (v1.3.3 -- v1.4.0)

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

---

## Later (v2.0+)

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

## Research-Driven Additions

### P0

- [ ] P0 - Move release signing secrets out of tracked Gradle config
  Why: `app/build.gradle.kts` hardcodes the release keystore path and passwords, which makes the release key reusable by anyone with the repo or artifact context.
  Evidence: `app/build.gradle.kts:23-27`; Android APK signing memory; GitHub repo inspection.
  Touches: `app/build.gradle.kts`, `gradle.properties`, ignored local signing properties, README release notes.
  Acceptance: Release signing reads from ignored local properties or environment variables; missing credentials fail with a clear Gradle error; no password literals remain in tracked files.
  Complexity: S

- [ ] P0 - Replace destructive Room migrations with explicit migrations
  Why: Saved bookmarks, recents, search history, and network connections are user data, but `fallbackToDestructiveMigration()` can wipe them during schema upgrades.
  Evidence: `core/database/src/main/java/com/explorer/fileexplorer/core/database/AppDatabase.kt:21-42`; Android Room migration documentation.
  Touches: `core:database`, migration tests, schema export configuration.
  Acceptance: `exportSchema` is enabled, v1->v2 and future migrations are explicit, and a migration test preserves all current entity data.
  Complexity: M

- [ ] P0 - Make Secure Delete govern destructive browser deletes
  Why: The Security screen exposes a secure-delete toggle, but the main browser delete path bypasses `SecureDelete.secureDelete()` and calls repository delete directly.
  Evidence: `feature/security/src/main/java/com/explorer/fileexplorer/feature/security/SecurityScreen.kt:119-190`, `feature/browser/src/main/java/com/explorer/fileexplorer/feature/browser/BrowserViewModel.kt:260-266`.
  Touches: `feature:browser`, `feature:security`, `core:data`, delete UI state.
  Acceptance: With Secure Delete enabled, local file deletes overwrite before removal; unsupported providers show a clear non-secure-delete notice; tests cover enabled/disabled local delete behavior.
  Complexity: M

- [ ] P0 - Make Vault encryption real or rename the feature
  Why: The UI and README describe an encrypted vault, but `VaultManager` only moves files into app-private storage with owner-only permissions.
  Evidence: `feature/security/src/main/java/com/explorer/fileexplorer/feature/security/SecurityScreen.kt:204-255`; Android Keystore documentation; Solid Explorer and MiXplorer encryption feature pages.
  Touches: `feature:security`, `core:storage`, Android Keystore integration, README.
  Acceptance: Vault files are encrypted at rest with Keystore-backed keys and biometric/device credential gating, or all user-facing text is changed to accurately describe app-private storage.
  Complexity: L

- [ ] P0 - Escape or eliminate shell-interpolated root operations
  Why: Root copy/move/delete/list commands interpolate paths into shell strings, so paths containing quotes or shell metacharacters can execute unintended commands.
  Evidence: `core/data/src/main/java/com/explorer/fileexplorer/core/data/RootFileRepository.kt:28`, `:67`, `:91`, `:108`; Material Files rationale against parsing/interpolating `ls`.
  Touches: `core:data`, `core:storage`, root operation tests.
  Acceptance: Root operations use libsu/NIO APIs or a single audited shell-escape helper; tests cover filenames with quotes, spaces, semicolons, newlines, and glob characters.
  Complexity: M

- [ ] P0 - Finish cloud OAuth and provider registration before claiming cloud support
  Why: Google Drive, Dropbox, and OneDrive providers implement API calls but their auth entry points return null or `NotImplementedError`, and account storage is in-memory.
  Evidence: `core/cloud/src/main/java/com/explorer/fileexplorer/core/cloud/drive/GoogleDriveProvider.kt:44-54`, `core/cloud/.../dropbox/DropboxProvider.kt:43-50`, `core/cloud/.../onedrive/OneDriveProvider.kt:41-43`; Android Credential Manager documentation.
  Touches: `core:cloud`, `feature:cloud`, Hilt modules, Keystore-backed token storage, README feature table.
  Acceptance: Each listed provider can sign in, persist refresh state securely, survive process restart, list files, sign out, and has a mock-provider test path; README statuses match verified behavior.
  Complexity: L

### P1

- [ ] P1 - Add migration and file-operation regression tests
  Why: The repo has no `src/test` or `src/androidTest` tree, yet the app performs high-risk recursive file, archive, root, network, and database operations.
  Evidence: Repo tree scan; `LocalFileRepository.kt`, `ArchiveHelper.kt`, `AppDatabase.kt`; Android testing and Room migration guidance.
  Touches: `core:data`, `core:database`, `core:network`, `feature:browser`, Gradle test dependencies.
  Acceptance: Local JVM/instrumented tests cover copy/move/delete conflict handling, archive zip-slip defense, Room migrations, search streaming, and repository routing.
  Complexity: M

- [ ] P1 - Add Play-policy permission rationale and backup exclusions
  Why: `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES` are defensible for this app, but Play policy requires narrow declarations and `allowBackup=true` risks backing up connection metadata.
  Evidence: `app/src/main/AndroidManifest.xml:10`, `:28`, `:33`; Google Play all-files access policy; Android package visibility documentation.
  Touches: `app/src/main/AndroidManifest.xml`, backup rules XML, README privacy/permissions section, app manager package queries.
  Acceptance: Manifest uses least-needed package visibility, backup rules exclude secrets/databases that contain credentials, and README documents why each sensitive permission is requested.
  Complexity: S

- [ ] P1 - Stream cloud uploads instead of reading whole files into memory
  Why: Dropbox and OneDrive uploads call `file.readBytes()`, which can exhaust memory on large media or archive uploads.
  Evidence: `core/cloud/src/main/java/com/explorer/fileexplorer/core/cloud/dropbox/DropboxProvider.kt:150`, `core/cloud/.../onedrive/OneDriveProvider.kt:124`; Solid Explorer/MiXplorer large cloud transfer expectations.
  Touches: `core:cloud`, transfer progress callbacks, cloud upload tests.
  Acceptance: Cloud uploads use streaming request bodies with progress, cancellation, and large-file tests; memory use does not scale with full file size.
  Complexity: M

- [ ] P1 - Add settings/bookmarks/connections export and restore
  Why: Power users expect file-manager setup to be portable; Solid Explorer reviews and MiXplorer features both surface backup/restore of bookmarks, connections, and preferences as retention-critical.
  Evidence: Solid Explorer Play review requesting settings backup; MiXplorer import/export preferences/bookmarks feature list; Room/DataStore usage in FileExplorer.
  Touches: `core:database`, DataStore repositories, `feature:settings`, `feature:network`, Keystore/token export policy.
  Acceptance: User can export a password-protected backup of bookmarks, non-secret settings, and optionally encrypted connection secrets; restore validates version and shows a summary before import.
  Complexity: M

- [ ] P1 - Add diagnostics and exportable failure logs for transfers/providers
  Why: Network/cloud/root failures are currently mostly toasts or empty lists, leaving users unable to diagnose authentication, permission, timeout, or protocol errors.
  Evidence: `SftpFileRepository.kt`, `FtpFileRepository.kt`, `WebDavFileRepository.kt`, and cloud providers catch exceptions into empty results; global error-handling rules require visible status and logs.
  Touches: `core:network`, `core:cloud`, `feature:transfer`, `feature:settings`, app-private log storage.
  Acceptance: Failed operations write structured, redacted diagnostic entries; Settings exposes copy/share log export; UI errors include actionable provider/status context without exposing secrets.
  Complexity: M

### P2

- [ ] P2 - Add compact density mode for power-user file lists
  Why: Community feedback on Material-style Android file managers repeatedly cites excessive spacing; FileExplorer's target users benefit from denser scan-and-select layouts.
  Evidence: Hacker News Material Files discussion; `core/ui/FileListItem.kt`; Navigation drawer/list Compose surfaces.
  Touches: `core:ui`, `feature:browser`, `feature:settings`, accessibility touch-target checks.
  Acceptance: Settings offers comfortable and compact density; compact mode shows more rows without clipping names, icons, badges, or controls; accessibility checks keep touch targets acceptable.
  Complexity: S

- [ ] P2 - Add an implementation-status feature matrix
  Why: README currently marks cloud and security features complete even where code has placeholder auth or non-encrypted vault behavior, which misleads users and future implementers.
  Evidence: `README.md` feature table; cloud provider placeholders; Vault implementation; existing research assignment hygiene rules.
  Touches: `README.md`, `RESEARCH.md`, release checklist.
  Acceptance: README separates Verified, Partial, Planned, and Requires configuration states; every Complete claim maps to exercised behavior or a local test.
  Complexity: S

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

FileExplorer v1.3.3 is the only Android file manager (OSS or commercial) that ships ALL of:
- Jetpack Compose + Material 3 UI (modern stack)
- Full root access via libsu
- Archives (ZIP/7z/TAR/GZ/BZ2/XZ/Zstandard) with virtual folder browsing
- 4 network protocols (SMB/SFTP/FTP/WebDAV)
- 3 cloud providers (Google Drive/Dropbox/OneDrive)
- Trash bin with configurable TTL and restore/permanent-delete flows
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
