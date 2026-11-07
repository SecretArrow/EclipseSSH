# Migration: maragung/EclipseSSH → SecretArrow/EclipseSSH

This document records what changed in the move from the `maragung` fork to the
`SecretArrow` home, and why. It is the single source of truth for "what's
different in the new repo" — the same questions do not need to be re-asked in a
PR review.

## What moved, and how

The full git history is preserved: `git log` on this repo reaches the original
`maragung` commits unchanged. The migration was a remote swap, not a re-import —
the SHA chain is intact, the release tags (`v1.0.0` through `v1.23.0`) are the
same, and every commit message from the audit report still resolves to a real
commit on this repo.

```
git remote rename origin maragung     # keep the upstream for cherry-picks
git remote add origin https://github.com/SecretArrow/EclipseSSH.git
git push origin main --tags
```

## What changed in this initial migration commit

### Terminal engine: Termux `terminal-view` (Apache-2.0)

The legacy custom Compose renderer (`dev.eclipse.ssh.ui.terminal.TerminalView`,
`dev.eclipse.ssh.terminal.AnsiTerminalBuffer`, `dev.eclipse.ssh.ui.terminal.GlyphRuns` /
`GlyphFaces`) is replaced by the Termux [`terminal-view`](https://github.com/termux/termux-app/tree/master/terminal-view)
library. The full audit that prompted the swap is in `terminal-text.txt`; the
short version is that the legacy renderer's text-wrapping, column alignment
and realtime-streaming paths all had drift between the parser and the renderer,
and the right fix was to use an emulator whose parser and renderer are owned
by the same library.

The new code:

- `app/src/main/java/dev/eclipse/ssh/terminal/TermuxEmulatorBridge.kt`
  Bridge between the SSH channel byte stream and the Termux `TerminalEmulator`.
  Single-threaded emulator; one coroutine drains `channel.output` into the
  emulator; user input is forwarded to the SSH channel through the emulator's
  `TerminalOutput` callback.
- `app/src/main/java/dev/eclipse/ssh/ui/terminal/TermuxTerminalView.kt`
  Compose wrapper around the Termux `TerminalView`, via `AndroidView`. Owns the
  View's lifecycle; the bridge (and the emulator it owns) survives recomposition.
- `app/src/main/java/dev/eclipse/ssh/ui/terminal/TermuxViewClient.kt`
  The `TerminalViewClient` glue: forwards UI events (touch, IME, clipboard)
  to the bridge.

The legacy renderer files are left in place for one release; they are not
referenced by any screen after this commit, and the next release removes them
and their tests. Keeping them around for one cycle lets a regression that
surfaces against the Termux renderer fall back to the legacy path without a
revert.

### CI/CD: 12 workflows → 5

The original `maragung` repo shipped 12 workflow files (~250KB total) covering
every release path the app had walked through. That surface was correct for
the audit it carried but is too heavy for a fast PR gate. The new repo has:

| File | Purpose | Wall clock |
| --- | --- | --- |
| `ci.yml` | Lint + unit + assemble (parallel) — the PR gate. | ~5 min |
| `e2e.yml` | Instrumented tests on an Android API 28–35 matrix. | ~15 min |
| `release.yml` | Tag-triggered release: APK per ABI + universal AAB. | ~25 min |
| `autofix.yml` | Spotless + ktlint `apply` on PR open/synchronize; commits the diff back. | ~2 min |
| `codeql.yml` | Security analysis + Copilot Autofix hook. | ~5 min |

The old workflows (`android-release-test.yml`, `android-ubuntu-e2e.yml`,
`ci.yml` (the 63KB version), `close-ci-window.yml`,
`export-signing-material.yml`, `fast-test.yml`, `focused-test.yml`,
`instrumentation.yml`, `release.yml` (the 10KB version), `schema-dump.yml`,
`signing-key-check.yml`, `tagged-release.yml`, `universal-apk-test.yml`) are
deleted from this repo. Their behaviour is preserved by the new five; the
audit's CI documentation in `docs/ci.md` is updated to refer to the new names.

### Auto-fix scope

Two layers, per the user's choice:

1. **Spotless + ktlint format** (`autofix.yml`). On PR open and synchronize,
   the action runs `./gradlew spotlessApply`, and if the working tree has a
   diff, commits it back to the PR branch under the `github-actions[bot]`
   identity. The commit's author email is `41898282+github-actions[bot]@users.noreply.github.com`;
   branch protection's "require linear history" is satisfied because the
   commit is a fast-forward, not a merge.
2. **AI code fix (Copilot + CodeQL)** (`codeql.yml`). CodeQL runs on every
   push; the workflow also surfaces Copilot Autofix suggestions as PR comments
   for any alert that has an autofix available. Public-repo only — the action
   skips on private repos because Copilot Autofix is billed per private repo.

### Branch: `main`

The new repo's default branch is `main`. The original `maragung` repo's
`main` is preserved as `maragung/main` in the remotes so a cherry-pick from
the upstream is still one `git log maragung/main` away.

## What did not change

- The application ID (`dev.eclipse.ssh`), version code (57), version name
  (1.23.0) and signing config are unchanged. The migration is a repo move,
  not a release.
- The `AUDIT-REPORT.md` is preserved verbatim. The figures it cites are still
  re-derivable from the files it names; the section index (§1–§125) is
  unchanged.
- The `docs/` folder, the `scripts/` folder, the `tools/local-sshd.sh` script,
  the keystore handling, the FreeRDP and `:linux` module build wiring — none
  of this is touched. The migration is a repo move plus a renderer swap.

## Credentials

The repo's CI uses `${{ secrets.GH_PAT }}` for cross-repo operations (e.g.
the release workflow pushing the APK to a GitHub Release). The PAT itself is
never committed; the workflow reads it from the repo's Actions secret store.
The original token shared during the migration setup was revoked per the
security warning in the migration PR; the replacement is stored as
`GH_PAT` in Settings → Secrets and variables → Actions.

If a future workflow needs a new secret, add it the same way — never commit
the value to the repo, and never pass it through an `env:` line that the log
could surface.
