# Changelog

All notable changes to FileExplorer will be documented in this file.

## [v1.5.0] - 2026-08-03

### Security
- Added explicit Google/Maven Central/JitPack dependency provenance policy, fixed-version checks, release-graph reporting, and an OSV advisory scan task.
- Added Android 15/16 upgrade gates for compile/target SDK, exported components, predictive back, backup rules, storage fallbacks, package visibility, and foreground-service timeouts.
- Hardened ZIP, 7z, TAR, and RAR extraction with shared path containment, depth and resource limits, symlink/hard-link rejection, cancellation checks, and staged commits.
- Replaced SFTP shell-based copy with SSHJ-native staged transfers and verified rename/move operations, including remote-path safety, timeout, cancellation, and partial-output handling.
- Made permanent deletion capability-aware: local overwrite is explicitly best effort, unsupported providers cannot claim secure deletion, and the browser reports partial outcomes with an irreversible-action confirmation.
- Made cloud readiness truthful: all built-in providers register with explicit status states, OAuth launch/callback results are represented, signed-out accounts are retained on revoke failure, and unconfigured connect actions stay disabled.
- Replaced the vault scaffold with opaque encrypted payloads, an authenticated encrypted index, Keystore-backed sessions, biometric/device-credential gating, atomic add/restore/delete operations, and backup-excluded app-private storage.
- Added shared repository capabilities and typed provider errors so unsupported operations and failed reads remain distinguishable from empty results, with bounded and redacted diagnostic context.
- Hardened the share server with Keystore-encrypted passwords, loopback-by-default binding, explicit plaintext LAN acknowledgement, bound passive sockets, connection/rate/upload/temp-storage limits, and stale-upload cleanup.
- Added a certificate-bound plugin trust boundary with visible Settings consent and revocation, declared-capability enforcement, fail-closed protocol negotiation, bounded isolated IPC, binder-death recovery, and path-free audit events.

### Added
- Added bounded, versioned, transactional portable backup import/export for bookmarks and non-secret settings, with deterministic duplicate handling and preview/summary confirmation.
- Added executable localization and accessibility gates for resource placeholders, inline UI strings, Compose semantics, RTL, large text, and minimum touch targets.
- Added migration, archive-format, disconnected-provider, and DocumentsProvider contract coverage for debug/API 37 verification.
- Added an independent dual-pane browser layout with per-pane navigation, refresh, sorting, hidden-file filtering, and selection.
- Added long-press drag-and-drop transfers between panes with self/descendant path protection and explicit Copy or Move confirmation.
- Added per-pane tabs with independent selection, close, long-press reorder, and swipe-to-close behavior.
- Added a Storage Analyzer screen with recursive treemap drill-down, duplicate-content groups, progress/cancel scanning, and largest-file list.
- Added batch rename with counter, date, parent, and regex capture-group tokens, collision validation, and live preview before a two-phase rename.
- Added a queued transfer manager with pause/resume, reordering, bandwidth limits, conflict resolution, and text diff previews; browser paste and dual-pane transfers now enqueue work.
- Added Room-backed transfer recovery with idempotency keys, URI/path checkpoints, durable cancellation and retry causes, startup rehydration, retry controls, and an explicit foreground queue monitor.
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
- Added a dedicated Quick Share action that sends local and SAF-backed files through the Android Sharesheet with complete URI grant metadata.
- Added Chromecast media casting for selected local and SAF-backed photos, videos, and audio using a temporary range-capable LAN stream.
- Added a root-only encrypted-volume workflow for mounting and unmounting existing gocryptfs or EncFS volumes with protected passphrase handling.
- Added shared Android localization resources with English plus Spanish, Brazilian Portuguese, German, French, Japanese, Korean, Simplified Chinese, Russian, and Arabic locale variants, and migrated core navigation, action, settings, security, network, transfer, and browser surfaces to resource-backed copy.
- Added bounded Coil thumbnail caching with configurable size, storage location, and purge controls in Settings.
- Added opt-in recursive directory sizes in list view with coroutine-backed calculation and a bounded per-directory LRU cache.
- Added named saved searches with regex/path recall from the navigation drawer.
- Added automatic recent-directory tracking with a separate drawer section and Room migration.
- Added SMB3 server-side copy through SMBJ with streamed fallback for NAS devices that reject the copy ioctl.
- Added a bounded, text-first EPUB reader that follows package spine order without a third-party renderer.

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

## Roadmap archive — 2026-08-10 — ROADMAP.md

<details>
<summary>Original roadmap snapshot</summary>

```markdown
# ROADMAP

> FileExplorer v1.5.0 | Updated 2026-06-30 | Research-driven, 50+ sources
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

## Next (v1.5.0 -- v1.7.0)

---

## Later (v2.0+)

---

## Under Consideration

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

FileExplorer v1.5.0 is the only Android file manager (OSS or commercial) that ships ALL of:
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

## Research-Driven Additions

The items below are net-new findings from the 2026-08-08 research refresh. Existing completed work and items already represented in `Roadmap_Blocked.md` are intentionally excluded.

### P1 — Next

- [ ] P1 — Make portable settings import bounded and transactional
  - Why: The existing export/import feature omits secrets, but import reads an unbounded stream, accepts unknown future versions, inserts without rollback, and has no duplicate policy.
  - Evidence: `core/data/.../BackupManager.kt`; Room/DataStore usage; Android backup/Keystore guidance; `RESEARCH.md` credential and backup analysis.
  - Touches: backup schema/versioning, stream parser and size limits, Room transaction, DataStore writes, preview/summary UI, backup tests and exclusions.
  - Acceptance: Import enforces byte/record/string limits, rejects unsupported versions, validates the whole payload before mutation, commits atomically or rolls back, defines duplicate behavior, preserves non-secret semantics, excludes credential/vault/share stores, and reports a deterministic summary.
  - Complexity: M

- [ ] P1 — Establish accessibility and localization quality gates
  - Why: Nine locale directories exist, but inline user strings and inconsistent `contentDescription`/Compose semantics remain. Missing labels, traversal order, RTL, and font-scaling checks make the broad UI unreliable for assistive-technology users.
  - Evidence: Compose accessibility and semantics guidance; scan of `feature/*` screens; `RESEARCH.md` accessibility section.
  - Touches: resource files, Compose screens/components, semantics helpers, locale key tooling, UI tests, large-screen/density settings.
  - Acceptance: User-facing strings are resource-backed; locale key parity is checked; actionable icons and custom actions have semantics while decorative icons remain hidden; tests cover TalkBack-relevant labels, progress/list roles, RTL, font scaling, compact/comfortable density, keyboard focus where supported, and minimum touch targets.
  - Complexity: M

- [ ] P1 — Add migration, adapter, archive, and DocumentsProvider regression coverage
  - Why: Room has explicit migrations and several security tests exist, but there is no complete migration matrix or cross-adapter contract suite; the current test baseline masks gaps.
  - Evidence: `core/database` schema 2–7 and `AppDatabase.kt`; existing 32 test files; `FileDocumentsProvider`; `ArchiveEntryPathPolicy`; Android DocumentsProvider contract; `RESEARCH.md` testability assessment.
  - Touches: `core:database`, `core:data`, `core:storage`, `core:network`, `core:cloud`, `app`/provider tests, CI reporting.
  - Acceptance: Migrations preserve representative rows through every version; repository routing/capability/error contracts are tested; archive policies cover all supported formats; DocumentsProvider covers grants, dynamic roots, search/recent, copy/move/rename and failures; tests run in CI with clear debug/release results.
  - Complexity: M

- [ ] P1 — Add dependency provenance and Android upgrade gates
  - Why: The app spans security/protocol/archive libraries and includes JitPack; Android 15/16 storage, FGS, package-visibility, and permission rules continue to change. A current version string alone is not a safety policy.
  - Evidence: `settings.gradle.kts`; dependency declarations; Commons Compress/SSHJ/Room/WorkManager/Kotlin release notes; OSV/NVD advisories; Android 15/16 guidance; `RESEARCH.md` distribution analysis.
  - Touches: version catalogs or centralized dependency definitions, repository allowlist, dependency locking/scanning, release scripts, API-level test matrix, upgrade documentation.
  - Acceptance: Production repositories and versions are explicit and reviewable; resolved dependencies are scanned against OSV/NVD; SSH/archive/crypto updates have release notes; Android 15/16 checks cover FGS timeout, all-files/package visibility, exported components, predictive back, and storage fallbacks; advisories are triaged against the actual graph.
  - Complexity: M

- [ ] P1 — Publish one verified/partial/blocked feature-status contract
  - Why: README and UI currently present several partial surfaces—especially cloud and vault—as if they were complete, while blocked publication/provider work is mixed with implementable code work.
  - Evidence: current `README.md`; `CloudScreen.kt`; `CloudProvider.kt`; `VaultManager`; `Roadmap_Blocked.md`; `RESEARCH.md` Product Map.
  - Touches: README feature table, settings/about/status UI, provider registry, release checklist, roadmap references.
  - Acceptance: Every major surface is labeled `Verified`, `Partial`, `Requires configuration`, `Unsupported`, or `Blocked by environment`; claims map to an exercised path/test; OAuth, F-Droid, Play, KMP, terminal, NFS, rclone, and Syncthing remain explicitly blocked where applicable; status is generated or checked from one source of truth.
  - Complexity: S

- [ ] P1 — Add an Android permission and distribution readiness matrix
  - Why: `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, media/storage, notifications, and exported integration components require a feature-level rationale and fallback plan even when publication itself is operator-gated.
  - Evidence: `app/src/main/AndroidManifest.xml`; Android all-files, package-visibility, scoped-storage, sharing, large-screen, and Play policy guidance; `RESEARCH.md` permissions analysis.
  - Touches: manifest, backup rules, permission rationale UI, app-manager fallback, DocumentsProvider/exported components, README/release checks.
  - Acceptance: A checked-in matrix maps each permission/component to purpose, API range, fallback, user explanation, backup implication, and distribution status; least-privilege package queries are used where possible; the app handles denied permissions without crashes; publication submission remains outside the item until owner access/decisions unblock it.
  - Complexity: M

### P2 — Later

- [ ] P2 — Complete high-value network capability semantics
  - Why: Remote create, recursive size, search, and checksum currently have provider-specific gaps or empty defaults, which makes the same browser operation behave differently without explanation.
  - Evidence: `NetworkRepoAdapter`; SMB/SFTP/FTP/WebDAV adapters; RFC HTTP/WebDAV/FTP semantics; competitor patterns in Material Files, MiXplorer, and X-plore; `RESEARCH.md` Product Map.
  - Touches: `core:network`, `core:data` capabilities, remote progress/cancellation, browser/search/analyzer UI, contract tests.
  - Acceptance: Each protocol advertises supported operations and consistency/cost; implemented operations report progress and errors; unsupported operations are explicit; recursive size/search/checksum have cancellation and limits; no adapter returns a success-shaped zero/empty placeholder.
  - Complexity: L

- [ ] P2 — Add crash-consistent staging for multi-file operations
  - Why: Recursive copy/move/delete and archive extraction can leave ambiguous partial state after process death, cancellation, low storage, or a filesystem error.
  - Evidence: transfer/repository implementations; Android storage guidance; fsync/data-loss research; `RESEARCH.md` architecture root causes.
  - Touches: `core:data`, `core:storage`, transfer queue, temp/staging metadata, recovery UI, low-storage/failure-injection tests.
  - Acceptance: Multi-file jobs record intended and committed entries, use atomic rename where supported, preserve or clean staging data deterministically, expose partial completion, and recover or roll back according to the selected operation policy.
  - Complexity: L

- [ ] P2 — Add conflict preview and idempotent operation decisions
  - Why: Power-user file managers compete on copy/move workflows, but durable recovery is unsafe without explicit overwrite/skip/rename/keep-both decisions and a way to reapply them.
  - Evidence: Solid Explorer, MiXplorer, X-plore, and Total Commander workflow patterns; transfer service construction; `RESEARCH.md` Competitive Landscape and reliability analysis.
  - Touches: transfer/domain model, browser selection flow, conflict UI, persisted queue schema, automation intents, tests.
  - Acceptance: Conflicts show source/destination metadata before mutation; policies are persisted per job; repeated delivery is idempotent; keep-both names are deterministic; automation can choose a policy; cancellation leaves a clear result.
  - Complexity: M

- [ ] P2 — Expand DocumentsProvider and persisted-URI conformance
  - Why: The provider already validates canonical roots and symlinks, but dynamic roots, persisted grants, missing removable volumes, flags, and failure behavior are integration-critical surfaces.
  - Evidence: `app/.../FileDocumentsProvider.kt`, `DocumentPathPolicy`; Android DocumentsProvider and SAF guidance; `RESEARCH.md` Android integration assessment.
  - Touches: provider queries/flags, URI grant lifecycle, USB/removable-root handling, external picker tests, diagnostics.
  - Acceptance: Provider tests cover root removal/reappearance, persisted grant revocation, read/write/create/delete/rename/move/copy, search/recent, MIME/size/last-modified metadata, symlink exclusion, and correct provider flags; failures return contract-appropriate errors.
  - Complexity: M

- [ ] P2 — Improve bounded duplicate and storage analysis
  - Why: Storage analysis is a recurring competitor and adjacent-project pattern, and duplicate/similar detection can deliver value without adding another remote provider if it is cancellable and privacy-preserving.
  - Evidence: `feature/apps` analyzer surfaces; CleanSweep; Solid Explorer/X-plore patterns; `RESEARCH.md` Product Map and Under Consideration analysis.
  - Touches: analyzer/index model, background scheduling, checksum sampling/full-hash policy, browser selection actions, storage/privacy tests.
  - Acceptance: Analysis is opt-in, bounded, cancellable, resumable, excludes inaccessible roots cleanly, avoids retaining file contents, explains hash/collision tradeoffs, and offers safe review-before-delete actions.
  - Complexity: M

- [ ] P2 — Add release smoke coverage for exported surfaces and large screens
  - Why: FileExplorer has many exported or system-facing paths—DocumentsProvider, tile, FileProvider, share intents, package queries, notifications, Cast—and a broad Compose layout surface.
  - Evidence: `AndroidManifest.xml`; Android large-screen/sharing/package-visibility guidance; recent large-screen, Quick Share, DocumentsProvider, and intent commits; `RESEARCH.md` testability assessment.
  - Touches: build/release scripts, manifest smoke tests, emulator/device matrix, Compose UI tests, artifact checks.
  - Acceptance: A headless/invisible smoke lane installs the debug artifact, resolves exported components with intended permissions, exercises share/picker/intent entry points, checks notification/FGS behavior, and runs representative phone/tablet/RTL/font-scale layouts without user-session interference.
  - Complexity: M

- [ ] P2 — Add a provider and feature capability matrix to the product surface
  - Why: Users need to know whether search, checksums, secure delete, cloud sign-in, archive extraction, and write operations are verified, expensive, unavailable, or configuration-gated for the current location.
  - Evidence: `FileRepositoryFactory`, `NetworkRepoAdapter`, cloud status flow, secure-delete scope, `RESEARCH.md` Product Map and Architecture Assessment; competitor breadth patterns.
  - Touches: `core:model`, repository adapters, browser/search/analyzer UI, settings/about, diagnostics and tests.
  - Acceptance: A single capability model drives action enablement, explanatory text, automation validation, and diagnostics; statuses are provider/location-specific; no UI or README surface claims a capability that the model marks unavailable.
  - Complexity: M

### P3 — Discovery / Under Consideration

- [ ] P3 — Prototype an offline browsing and operation-journal contract
  - Why: Offline-first browsing and queued mutation are high-value but would change indexing, invalidation, battery, privacy, and conflict semantics. A bounded prototype is safer than committing to a sync architecture.
  - Evidence: Fossify offline posture; Syncthing lifecycle caution; Simba/CleanOS engineering patterns; `RESEARCH.md` Under Consideration and Open Questions.
  - Touches: research/prototype module or test fixture, cache invalidation model, URI/provider state, transfer conflict model, battery/privacy measurements.
  - Acceptance: A disposable prototype documents supported cache scope, staleness, mutation queue, conflict, encryption, eviction, and battery limits; it does not expose a production toggle or claim sync support until those measurements pass review.
  - Complexity: M

- [ ] P3 — Define multi-user and shared-workspace semantics
  - Why: Android device users/work profiles do not automatically provide app-level shared workspaces. Adding collaboration without identity, ownership, encryption, and conflict rules would be misleading.
  - Evidence: `RESEARCH.md` Product Map/Open Questions; Android scoped-storage and Keystore guidance; cloud/sync adjacent projects.
  - Touches: architecture decision record within the allowed research/roadmap docs, threat model, account/ownership model, storage and conflict prototypes.
  - Acceptance: The decision specifies whether the product supports only device profiles, local app users, or shared remote workspaces; defines identity, key ownership, revocation, conflict, audit, backup, and offline behavior; implementation remains out of scope until the model is accepted.
  - Complexity: M

- [ ] P3 — Evaluate secure local discovery and cross-device transfer
  - Why: LocalSend and LAN-share patterns suggest a useful privacy-preserving workflow, but discovery, trust-on-first-use, transport encryption, firewall behavior, pairing, and abuse limits need a bounded evaluation.
  - Evidence: LocalSend; mDNS and TLS RFCs; current LAN share server; `RESEARCH.md` Competitive Landscape, share-server exposure, and Open Questions.
  - Touches: threat model, protocol comparison, isolated prototype, pairing/expiry UX, network resource limits, test matrix.
  - Acceptance: A prototype/report compares mDNS/manual pairing, transport/authentication, replay/mitm risks, expiry, firewall behavior, and large-file resume; no production discovery toggle is added until the trust model and resource limits are accepted.
  - Complexity: M

- [ ] P3 — Measure a constrained plugin catalog boundary
  - Why: A local plugin trust model is required before a catalog can be useful, and a catalog would add update, compatibility, privacy, and supply-chain responsibilities.
  - Evidence: Ghost Commander plugin pattern; current `PluginManager`; Android service binding; `RESEARCH.md` plugin trust analysis and Under Consideration tier.
  - Touches: compatibility matrix, catalog metadata prototype, consent/revocation model, dependency/provenance checks, failure isolation tests.
  - Acceptance: The evaluation defines whether catalogs are project-controlled, user-supplied, or absent; documents update/rollback/compatibility and provenance rules; demonstrates that an untrusted or incompatible plugin cannot bind into a privileged operation path; no catalog is shipped by this item.
  - Complexity: M
```

</details>
