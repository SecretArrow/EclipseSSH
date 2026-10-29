# Continuous integration

Five workflows, by purpose. The maragung fork ran twelve; the SecretArrow move consolidated them because the audit it carried is closed and the eight job flavours it pinned are no longer all needed on every push. The full reasoning for the consolidation is in [`migration-to-secretarrow.md`](migration-to-secretarrow.md).

| Workflow | Trigger | What it does |
| --- | --- | --- |
| [`ci.yml`](../.github/workflows/ci.yml) | Push to `main`, every pull request, `workflow_dispatch` | The gate. **Four jobs**, so a failure lands on the thing that is actually broken: the documentation figure check, Spotless + Android lint, the Robolectric unit suite, and a debug-APK smoke build. The expensive paths the old ci.yml carried — the four-ABI FreeRDP native build, the emulator crash-on-open, and the 90-minute idle stress matrix — moved to `e2e.yml` and `release.yml`. |
| [`e2e.yml`](../.github/workflows/e2e.yml) | Push to `main`, PRs labelled `e2e`, `workflow_dispatch` | `connectedDebugAndroidTest` on a matrix of booted AVDs (API 28, 30, 33, 35), each under a hard 30-min timeout. PRs without the `e2e` label get only the cheap unit-test gate from `ci.yml`. |
| [`release.yml`](../.github/workflows/release.yml) | A `vX.Y.Z` (or `vX.Y.Z-…`) tag, `workflow_dispatch` | The native build of both `:freerdp` and `:linux`, the release AAB and per-ABI release APKs, both signed with the keystore restored from the CI secret, and a GitHub Release with every artifact attached. |
| [`autofix.yml`](../.github/workflows/autofix.yml) | Pull request open, synchronize, reopen | Runs `./gradlew spotlessApply` and commits the diff back to the PR branch under the `github-actions[bot]` identity. The commit is a fast-forward, so branch protection's "require linear history" stays satisfied. |
| [`codeql.yml`](../.github/workflows/codeql.yml) | Push to `main`, every pull request, weekly cron | CodeQL security analysis for Java/Kotlin, plus the Copilot Autofix hook that opens a patch PR for any alert with an autofix available. |

## The four `ci.yml` jobs

Pinned to commit SHAs for every `uses:` line so a future tag rewrite cannot silently change what the workflow does (the tj-actions and action-gh-release force-push incidents have been real supply-chain vectors). Floating `@vN` references are gone. The toolchain is pinned too: Ubuntu 24.04, JDK 17.0.13 (Temurin), Android SDK 37, build-tools 36.0.0, Gradle 9.x (per `gradle-wrapper.properties`).

Branch protection must require the four check names below (`docs`, `lint`, `unit`, `assemble`); the old combined "Lint, tests, APKs and AAB" check is gone. The four run in parallel; wall clock is `max(docs, lint, unit, assemble)` rather than the serial sum, so the cheap jobs do not wait on the expensive ones.

1. **`docs`** — `scripts/check-doc-figures.sh`, `scripts/terminal-symbols.py`, `scripts/terminal-width.py`. No JDK, no SDK, no Gradle, no cache to restore. The cheapest gate, listed first because a 30-second answer is better than waiting for a Gradle run that fails for the same reason five minutes later.
2. **`lint`** — `./gradlew spotlessCheck lintRelease`. Spotless runs ktlint under the hood; lintRelease is the Android variant's full lint. Lint reports are uploaded as artifacts regardless of pass/fail.
3. **`unit`** — `./gradlew testDebugUnitTest --continue`. Robolectric tests against the JVM at SDK 35, no emulator. The same configuration cache pin and Robolectric temp-dir workaround the old ci.yml used; the test JVM is sized at 1536m because Compose under Robolectric at SDK 35 needs more than Gradle's default 512m.
4. **`assemble`** — `./gradlew assembleDebug -x :freerdp:externalNativeBuild -x :linux:externalNativeBuild`. The smoke build. The native modules are skipped here because the four-ABI FreeRDP build is the slowest single step in the pipeline and only the release APK needs it; a debug APK without native libs is enough to catch a Compose/Hilt/KSP regression. The native build runs in `release.yml` on tag.

## What was lost in the consolidation

Three things the old ci.yml carried that the new one does not, and where each landed:

- **The four-ABI FreeRDP native build** moved to `release.yml`. A PR that touches `freerdp/build.gradle.kts` should add the `e2e` label so the `e2e.yml` matrix catches a regression before merge; the native build itself only runs on tag.
- **The crash-on-open smoke** moved into `e2e.yml`'s matrix. The booted AVD is the right place to assert the debug APK does not crash on `MainActivity` launch; running it on the JVM (the old `smoke` job's x86_64 AVD on the runner) is the same idea, just at a different layer.
- **The idle stress matrix** was removed. It was a 90-minute job against a real OpenSSH server, meant to be watched rather than to block a merge. The same coverage is available on demand via `workflow_dispatch` on `e2e.yml`, run manually with the `e2e` label.

## Auto-fix

Two layers, so a PR author does not have to wait for `spotlessApply` to land a formatting fix:

1. **`autofix.yml`** commits Spotless's diff back to the PR branch under `github-actions[bot]`. The commit is a fast-forward; branch protection's "require linear history" stays satisfied. The bot only touches files Spotless rewrites — it never makes a code change, so a review is not blocked on a re-read.
2. **`codeql.yml`'s Copilot Autofix hook** opens a patch PR for any CodeQL alert with an autofix available. The patch PR is opened by `github-actions[bot]` and uses the same GITHUB_TOKEN as `autofix.yml`, so it is scoped to this repo. Public repos get Copilot Autofix for free; private repos are billed per alert and the workflow's `if:` skips the step on private repos.

## Secrets

Three workflows read a secret:

| Workflow | Secret | Purpose |
| --- | --- | --- |
| `release.yml` | `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | Restore the release keystore to the runner's `$HOME/keystore/release.keystore` and write `keystore.properties` for the build to read. The keystore is shredded at the end of the job; no artifact carries it. |
| `release.yml` | `GH_PAT` | Cross-repo operations — the GitHub Release publish. GITHUB_TOKEN cannot publish to a release that the workflow's own repo owns in some edge cases (e.g. when the tag was pushed by another identity); the PAT avoids the failure. |
| `codeql.yml` | (none) | CodeQL runs under GITHUB_TOKEN; no PAT needed. |
| `autofix.yml` | (none) | Uses GITHUB_TOKEN, scoped to the repo. |

The PAT itself is never committed; the workflow reads it from the repo's Actions secret store. See [`migration-to-secretarrow.md`](migration-to-secretarrow.md) §Credentials.
