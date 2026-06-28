# Research - FileExplorer

## Executive Summary
FileExplorer v1.3.0 is a Kotlin/Jetpack Compose Android file manager that aims for maximum feature density: local/root browsing, archives, network protocols, cloud providers, editor, app manager, and security tools. Verified code shows a strong modular skeleton and real local/root/archive/network foundations, but the highest-value direction is trust hardening before feature expansion. Top opportunities, in priority order: move release signing secrets out of tracked Gradle config; replace destructive Room migrations; make Secure Delete and Vault behavior match the UI/README claims; eliminate root/SFTP command injection and SFTP host-key bypasses; finish OAuth/provider registration before claiming cloud support; route network/cloud/archive backends through the main repository layer; add regression tests around file operations; tighten Play-sensitive permissions and backup behavior; stream large cloud uploads; and add diagnostics/exportable logs for failed transfers.

## Product Map
- Core workflows: browse local/root paths; select/copy/move/delete/rename/share files; browse/extract/create archives; configure network connections; search files; edit text files; inspect installed apps; toggle security settings.
- User personas: Android power users; rooted-device users; privacy-focused F-Droid users; NAS/SFTP/WebDAV users; developers who need editor/app/APK utilities; users replacing ad-heavy closed-source file managers.
- Platforms and distribution: Android API 26+; Gradle/AGP Android app; signed APK target; README mentions Android Studio Ladybug and API 35; existing roadmap includes F-Droid and Play Store AAB paths.
- Key integrations and data flows: `FileRepositoryFactory` routes local/root only today; Room stores bookmarks/recents/search/network connections; libsu executes root operations; sshj/smbj/Commons Net/sardine handle network protocols; OkHttp/Gson cloud providers exist but OAuth entry points return placeholders.

## Competitive Landscape
- Material Files: does local/root/archive/NAS with careful Linux-aware file semantics and a clean F-Droid/Play distribution story. Learn from its "robust backend first" philosophy and SAF/DocumentsUI integration; avoid over-claiming features before provider flows are wired.
- Amaze File Manager: offers multi-tab workflows, encryption, USB OTG, and trash-style safety features. Learn from safety-first destructive operations; avoid plugin/paywall fragmentation for core file safety.
- Solid Explorer: sets commercial expectations for dual-pane, indexed search, batch rename, collections, cloud/network transfer, web sharing, storage analyzer, AES encryption, Shizuku, and tablet/Chromebook input. Learn parity expectations; avoid its recent user complaints around license friction and settings loss.
- MiXplorer Silver: strongest power-user benchmark: unlimited tabs, dual panel, tasks, per-folder views, broad archive/cloud support, EncFS, AEScrypt, server modes, import/export preferences. Learn configurability and backup/restore; avoid making every control customizable before the core is verified.
- Prism File Explorer: Compose/Material 3 peer with tabs, media/PDF viewers, and a polished lightweight posture, but the repo states it is no longer actively maintained. Learn from its visual/media feature set; avoid depending on inactive patterns.
- Ghost Commander/Total Commander: mature dual-pane and plugin ecosystems for FTP/SFTP/SMB/WebDAV/cloud. Learn separate-provider/plugin boundaries; avoid dated UI and external plugin friction as the default install path.
- DiskUsage/X-plore: storage visualization remains a standout workflow for Android file managers. Learn drill-down space analysis; avoid adding heavy analyzers before deletes/trash/recovery are safe.

## Security, Privacy, and Reliability
- Verified: `app/build.gradle.kts:23-27` hardcodes release keystore path and passwords. Move signing credentials to ignored local properties or environment variables before the next release.
- Verified: `core/database/AppDatabase.kt:21-42` uses `exportSchema = false` and `fallbackToDestructiveMigration()`, risking loss of saved connections/bookmarks/search history on schema bumps.
- Verified: `core/database/Entities.kt:41` persists network passwords in plaintext; existing roadmap item N-02 correctly covers Android Keystore encryption.
- Verified: `core/network/sftp/SftpFileRepository.kt:37` uses `PromiscuousVerifier()`, so SFTP is vulnerable to host impersonation until known_hosts/fingerprint pinning lands.
- Verified: `core/data/RootFileRepository.kt:28`, `:67`, `:91`, `:108` interpolate user-controlled paths into shell commands with single quotes and no escaping; quoted filenames can break command boundaries.
- Verified: `feature/security/SecurityScreen.kt:119-190` defines secure deletion and `:371-376` exposes the toggle, but `feature/browser/BrowserViewModel.kt:260-266` still calls repository delete directly.
- Verified: `feature/security/SecurityScreen.kt:204-255` "Vault" only moves files to app-private storage with permissions; it is not encrypted despite the UI/README wording.
- Verified: cloud providers (`GoogleDriveProvider.kt:44-54`, `DropboxProvider.kt:43-50`, `OneDriveProvider.kt:41-43`) return null/`NotImplementedError` for OAuth, while README marks all three complete.
- Verified: `AndroidManifest.xml:10`, `:28`, `:33` requests `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, and allows backup. These can be justified for a file manager/app manager, but they need policy docs, backup exclusions for secrets, and least-visible package queries where possible.
- Verified: no `src/test` or `src/androidTest` tree was found. File operation, archive extraction, Room migration, URI routing, and security behavior have no local regression harness.

## Architecture Assessment
- `FileRepositoryFactory.kt:21-26` documents future URI routing for `archive://`, `smb://`, `sftp://`, and cloud schemes but only injects local/root repositories. This keeps network/cloud screens isolated from the main browser and transfer service.
- `BrowserViewModel.kt` owns navigation, selection, archive entry, delete, copy/move, bookmarks, and root state in one state holder. Split provider navigation state, destructive action policy, and archive session handling before dual-pane/tabs.
- `CloudAccountManager` is in-memory only and has no Hilt provider registration visible in `core/cloud`; accounts vanish on process death and providers are not discoverable unless screens manually register them.
- `DropboxProvider.kt:150` and `OneDriveProvider.kt:124` use `file.readBytes()` for uploads, which can exhaust memory for large files; streaming request bodies should match the local/network transfer model.
- `TransferService.kt` stores progress in a static `MutableStateFlow` and returns `START_NOT_STICKY`; process death loses queued work. Existing roadmap X-05 and N-08 should be implemented before long-running cloud/NAS transfers.
- `ArchiveHelper.kt` has zip-slip protection for tar extraction but zip/7z extraction delegates to libraries without explicit destination validation in FileExplorer code. Add tests and defensive path validation around all archive extraction formats.
- Compose UI strings are inline across `feature/*`; existing roadmap L-17 covers i18n, and the first implementation step is extracting strings rather than translating ad hoc.

## Rejected Ideas
- Full desktop/KMP file manager now: valid long-term idea but it distracts from Android trust issues and existing U-01 already tracks feasibility.
- New cloud providers before OAuth is complete: MiXplorer/Solid show provider breadth matters, but FileExplorer must first make Google Drive/Dropbox/OneDrive sign-in persistent and testable.
- Custom icon packs/themes as a near-term differentiator: competitors monetize this, but it does not address verified safety, privacy, or completeness gaps.
- Android Auto file browsing: platform category restrictions make this a poor fit for a file manager UI.
- Replacing DocumentsUI: Material Files explicitly avoids replacing the system picker; FileExplorer should integrate via SAF/DocumentsProvider instead.

## Sources
### Project
- https://github.com/SysAdminDoc/FileExplorer
- Y:/repos/FileExplorer/README.md
- Y:/repos/FileExplorer/ROADMAP.md
- Y:/repos/FileExplorer/app/build.gradle.kts
- Y:/repos/FileExplorer/core/database/src/main/java/com/explorer/fileexplorer/core/database/AppDatabase.kt
- Y:/repos/FileExplorer/core/data/src/main/java/com/explorer/fileexplorer/core/data/FileRepositoryFactory.kt
- Y:/repos/FileExplorer/feature/security/src/main/java/com/explorer/fileexplorer/feature/security/SecurityScreen.kt

### OSS and Commercial Comparisons
- https://github.com/zhanghai/MaterialFiles
- https://github.com/TeamAmaze/AmazeFileManager
- https://github.com/FossifyOrg/File-Manager
- https://github.com/Raival-e/Prism-File-Explorer
- https://github.com/1hakr/AnExplorer
- https://sourceforge.net/projects/ghostcommander/
- https://github.com/IvanVolosyuk/diskusage
- https://play.google.com/store/apps/details?id=pl.solidexplorer2
- https://play.google.com/store/apps/details?id=com.mixplorer.silver
- https://play.google.com/store/apps/details?id=nextapp.fx
- https://play.google.com/store/apps/details?id=com.lonelycatgames.Xplore

### Platform, Dependencies, and Community
- https://developer.android.com/training/data-storage/manage-all-files
- https://support.google.com/googleplay/android-developer/answer/10467955
- https://developer.android.com/training/package-visibility/declaring
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/identity/credential-manager
- https://developer.android.com/training/data-storage/room/migrating-db-versions
- https://commons.apache.org/proper/commons-compress/changes.html
- https://www.bouncycastle.org/download/bouncy-castle-java/
- https://news.ycombinator.com/item?id=38992689

## Open Questions
- Which distribution path is intended to ship first: GitHub APK only, F-Droid, Play Store, or all three? This affects permission declarations, proprietary cloud dependencies, and release artifact format.
- Should the "Vault" remain app-private storage with clearer naming, or become real per-file encryption with biometric-gated keys?
