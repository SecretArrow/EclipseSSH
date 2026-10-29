# Release keystore setup

This document explains how the release signing key is delivered to the GitHub Actions release workflow **without** being committed to the repository. The repo is public; the keystore is not.

## What never goes in the repo

- The keystore file (`*.jks`, `*.keystore`).
- A password-protected zip containing the keystore (`key-sign-apk.zip` or any other archive).
- The zip's password.
- The keystore's internal `storePassword`, `keyAlias`, `keyPassword`.

A public repo with a keystore zip in it is a critical security violation regardless of whether the zip is password-protected. The zip password was shared in a chat log; chat logs are stored, indexed, and readable by anyone with access to the chat provider's storage. Anyone with the zip + the password has the keystore. Anyone with the keystore can sign malicious APKs as EclipseSSH and push them as updates to existing installs.

## What does go in the repo

- `testing/verify-keystore.sh` — runs at build time, asserts the restored keystore holds the canonical release key (SHA-256 `0c69794b7934452bdf1b2f1f345314e13b86ef43f0d17bec9ff260dd30a443be`). The canonical fingerprint lives in `testing/verify-release-signer.sh` so a key rotation is a one-file edit.
- `testing/verify-release-signer.sh` — the same check, run against the finished APK after signing. Catches a release that signed with a different key than the keystore (e.g. a debug key fallback).
- `.github/workflows/release.yml` — restores the keystore from the `RELEASE_KEYSTORE_BASE64` Actions secret, writes `keystore.properties`, runs `verify-keystore.sh`, then builds the per-ABI release APKs + universal AAB.

## How the keystore reaches the workflow

1. **Locally**, the maintainer runs `scripts/extract-keystore-for-secret.sh <zip> <zip-password>`. The script unzips the keystore into a temp dir outside the repo, prints the keytool listing (so the maintainer can verify the canonical SHA-256 matches), and base64-encodes the keystore to stdout.
2. **In the GitHub repo's Settings → Secrets and variables → Actions**, the maintainer adds four secrets:

   | Secret name | Value |
   | --- | --- |
   | `RELEASE_KEYSTORE_BASE64` | The base64 string the script printed. |
   | `RELEASE_STORE_PASSWORD` | The keystore's internal store password (the one `keytool -list` asked for). |
   | `RELEASE_KEY_ALIAS` | The alias the script detected from the listing. |
   | `RELEASE_KEY_PASSWORD` | The alias's key password (the one the script asked for last). |

3. **At build time**, `release.yml` restores the keystore:

   ```bash
   printf '%s' "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > $HOME/keystore/release.keystore
   ```

   writes `keystore.properties` from the other three secrets, and runs `verify-keystore.sh` before any APK is signed. The keystore lives on the runner's `$HOME` for the duration of the job; the runner's disk is discarded with the VM when the job ends. The keystore is never cached, never uploaded as an artifact, never written inside the checked-out repo.

## Why the zip password is not stored as a secret

Once the keystore is extracted, the zip is dead weight. The zip's only purpose was to protect the keystore during transit (e.g. via email or chat). Once the keystore is in GitHub Secrets, the zip has no remaining role — shred it. The workflow never sees the zip and never needs the zip password.

If a future key rotation ships a new keystore in a new zip, run `extract-keystore-for-secret.sh` again with the new zip and the new password, paste the new base64 string into the `RELEASE_KEYSTORE_BASE64` secret (overwrite the old value), and update `testing/verify-release-signer.sh`'s `EXPECTED` to the new certificate's SHA-256. The old zip's password is irrelevant from then on.

## What if the zip password was already shared in a chat?

Treat the zip as compromised. The chat log is stored; the password is in the chat log; therefore the password is public. Anyone with the zip (which was likely attached to the same chat) and the password has the keystore.

The remediation is the same as the setup path:

1. Run `extract-keystore-for-secret.sh` locally to extract the keystore.
2. Set the four GitHub Secrets as above.
3. `shred -uvz` the zip and any local copy of the keystore.
4. Run `release.yml` once via `workflow_dispatch` to confirm the workflow can restore the keystore and `verify-keystore.sh` passes.

The keystore itself is not compromised by the zip password being public — the keystore's own `storePassword` and `keyPassword` are what protect the signing operation, and those are not in any chat log. The zip password only protects the zip at rest; once the keystore is in GitHub Secrets, the zip password is irrelevant.

If you suspect the keystore's internal passwords are also compromised (e.g. they were shared in a chat too), rotate the key: generate a new keystore, sign one release with it, then use Google Play Console's **Upload key reset** feature (under Setup → App signing) so Play re-signs with the new key on its side. Existing installs update cleanly because Play's app-signing key (the one Play itself holds) is separate from your upload key.
