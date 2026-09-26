# ROADMAP

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Issue Intake (2026-09-26)

Open GitHub issues checked against this list on 2026-09-26. Neither was on the list before.

### P1

- [ ] P1: Android/data never finishes loading through Shizuku (issue #3)
  Reported: Hot12345, 2026-09-14, v1.6.2 on Android 16, bug, no log.
  Why: with Shizuku running and the app authorized, opening Android/data shows the loading state forever. On Android 16 the shell user may need the directory listed through a different path (the `/storage/emulated/0/Android/data` walk fails under the new restrictions, or the binder call never returns and there is no timeout).
  Next: reproduce on the S22 with Shizuku; add a timeout with an error state to the Shizuku listing path so it can never spin forever; then fix the listing itself.
  Evidence: https://github.com/SysAdminDoc/FileExplorer/issues/3
  Complexity: M

### P3

- [ ] P3: Android TV support, the remote does not work (issue #2)
  Reported: alabotski, 2026-08-30, enhancement.
  Why: the app installs on Android TV but nothing is focusable from the remote, so it cannot be used there.
  Next: decide whether TV is a target. If yes: D-pad focus order on the file list and toolbar, a leanback launcher intent, and a check on a TV emulator image. If no: say so on the issue and leave the closing to Matt.
  Evidence: https://github.com/SysAdminDoc/FileExplorer/issues/2
  Complexity: M
