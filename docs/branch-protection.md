# Branch protection

The rule for `main` is enforced by GitHub's branch protection API. It is in force whenever the repository is public, and it binds the administrator as well as everyone else — the rule's `enforce_admins` is on.

The required checks below are the four `ci.yml` job display names. `scripts/ci-window.sh`'s `PROTECTION_CONTEXTS` array carries the same list; `scripts/check-doc-figures.sh` compares the two and fails on any difference, because a required context that no workflow reports is a check that can never pass, and `main` would stop being *mergeable* rather than stop being *guarded*.

`ci.yml` and `e2e.yml` both run on every pull request and every push to `main`. Their check names are the job `name:` values, which are deliberately not the job keys — add these *names*, not the keys.

| Workflow | Check name (job key) | Covers |
| --- | --- | --- |
| `CI` | `Documentation figures` (`docs`) | `scripts/check-doc-figures.sh` — the figures the documents state against the files that pin them, the repository paths they name, and the check names in this table |
| `CI` | `Lint` (`lint`) | `./gradlew spotlessCheck lintRelease` — Spotless ktlint check + the Android lint variant's full report |
| `CI` | `Unit tests` (`unit`) | `./gradlew testDebugUnitTest --continue` — Robolectric tests against the JVM at SDK 35, no emulator |
| `CI` | `Assemble (debug smoke)` (`assemble`) | `./gradlew assembleDebug -x :freerdp:externalNativeBuild -x :linux:externalNativeBuild` — the smoke build that catches a Compose/Hilt/KSP regression without paying for the four-ABI native build |

The four `CI` rows are the set `ci.yml` itself names in its job-layout comment. The `E2E` matrix is **not** in the list: it is the most expensive check in the repository (a 30-min matrix of four booted AVDs) and is gated by the `e2e` label on PRs, so a PR without the label has no `E2E / Instrumented tests (API …)` check to report. A check that reports `skipped` never reports success, so requiring it would make `main` permanently unmergeable. The `Release` workflow is **not** a required check either, because it is gated by the `vX.Y.Z` tag push and should not block a merge.

Settings are at `github.com/SecretArrow/EclipseSSH/settings/branches`.

## Rule for `main`

| Setting | Value | Why |
| --- | --- | --- |
| Required status checks | the four `CI` rows above | So a red `CI` job blocks the merge. |
| Require branches up to date before merging | off | The autofix bot commits back to the PR branch; "up to date" would re-trigger CI on every autofix commit, which doubles the runner minutes for no signal. |
| Require linear history | on | The autofix commit is a fast-forward; merge commits are not allowed. |
| Require a pull request before merging | on | So every change to `main` lands through a PR, including those by the administrator. |
| Required approving reviews | 0 | The repo is currently single-maintainer; the gate is the CI, not the review. Bump to 1 when a second maintainer joins. |
| Enforce for administrators | on | So the rule binds the administrator as well; the audit's "even against the administrator" claim stays true. |
| Allow force pushes | off | So a `git push --force` to `main` is refused. |
| Allow deletions | off | So a `git push origin --delete main` is refused. |

## Applying the rule

`scripts/ci-window.sh open` is a `PUT` to `/repos/SecretArrow/EclipseSSH/branches/main/protection` with the settings above, made before any workflow that needs the rule to bind runs. `scripts/ci-window.sh protect` re-applies it on its own; `scripts/ci-window.sh status` reports whether it is in force. A token with administrative access to the repository is required; the token is pulled from the environment (`GH_TOKEN`), never written anywhere, and never echoed.
