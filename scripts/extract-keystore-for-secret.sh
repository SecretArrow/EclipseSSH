#!/usr/bin/env bash
# Helper: extract a keystore from a password-protected zip and base64-encode it
# for storage as a GitHub Actions Secret.
#
# WHY THIS EXISTS
#
# The user has key-sign-apk.zip (a password-protected zip containing the
# EclipseSSH release keystore). They asked to push the zip to the public
# GitHub repo and have the workflow unzip it at build time. THAT IS A CRITICAL
# SECURITY VIOLATION:
#
#   - The repo is public, so the zip would be downloadable by anyone.
#   - The zip password was shared in chat, which is logged. Anyone who reads
#     the chat log has the password. Anyone who reads the chat log + clones
#     the repo has the keystore. Anyone with the keystore can sign malicious
#     APKs as EclipseSSH and push them as updates to existing installs.
#
# The secure path is: extract the keystore locally, base64-encode it, paste
# the base64 string into the repo's Settings -> Secrets and variables ->
# Actions -> New repository secret (name: RELEASE_KEYSTORE_BASE64). GitHub
# encrypts the secret at rest; the workflow reads it at runtime via
# ${{ secrets.RELEASE_KEYSTORE_BASE64 }}; it is never logged, never cloned
# with the repo, never visible to anyone but the maintainers who set it.
#
# This script does the local extraction. Run it on your own machine, never
# inside a CI runner, never inside a Docker container you do not own.
#
# USAGE
#
#   scripts/extract-keystore-for-secret.sh <path-to-key-sign-apk.zip> <zip-password>
#
# The script will:
#   1. Unzip key-sign-apk.zip into a temp dir under $TMPDIR (NOT under the
#      repo, so the keystore never lands in git's working tree).
#   2. Find the .jks / .keystore file inside.
#   3. Print its size, SHA-256, and the keytool listing (so you can verify
#      the alias and the canonical fingerprint match what
#      testing/verify-release-signer.sh expects: 0c69794b...43be).
#   4. Base64-encode the keystore to stdout.
#   5. Print the four secrets you need to set in the GitHub repo, with the
#      base64 string already truncated for display.
#   6. Shred the temp dir.
#
# After running this, paste each secret into GitHub:
#   - Settings -> Secrets and variables -> Actions -> New repository secret
#   - Names: RELEASE_KEYSTORE_BASE64, RELEASE_STORE_PASSWORD,
#     RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
#
# The zip password itself is NOT stored as a GitHub secret. Once the keystore
# is extracted, the zip is dead weight — shred it. If you ever receive a new
# keystore via a password-protected zip, run this script again and update
# the RELEASE_KEYSTORE_BASE64 secret.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  cat >&2 <<EOF
usage: $0 <path-to-key-sign-apk.zip> <zip-password>

The script extracts the keystore from the zip, base64-encodes it, and prints
the four GitHub Actions secrets you need to set in the repo. The zip and the
password are NEVER pushed to the repo; the script runs entirely locally.

The zip password is read from argv (not from stdin, not from an env var) so
it is never written to a file or echoed by the shell's history expansion.
EOF
  exit 2
fi

ZIP="$1"
ZIP_PASSWORD="$2"

[ -f "$ZIP" ] || { echo "no such file: $ZIP" >&2; exit 2; }
command -v unzip >/dev/null 2>&1 || { echo "unzip is not installed" >&2; exit 2; }
command -v keytool >/dev/null 2>&1 || { echo "keytool is not installed (apt install openjdk-17-jre-headless)" >&2; exit 2; }
command -v base64 >/dev/null 2>&1 || { echo "base64 is not installed" >&2; exit 2; }
command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 || { echo "sha256sum/shasum is not installed" >&2; exit 2; }

# Use a temp dir OUTSIDE the repo so the keystore never lands in git's
# working tree even by accident. $TMPDIR is the right place on macOS and
# Linux; /tmp is the fallback.
TMPDIR="${TMPDIR:-/tmp}"
WORK_DIR="$(mktemp -d "$TMPDIR/eclipsessh-keystore-XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "Extracting $ZIP into $WORK_DIR ..."
# -P passes the password; -q quiets unzip's per-file banner; the password is
# never echoed because we are not in a TTY that records it.
if ! unzip -q -P "$ZIP_PASSWORD" "$ZIP" -d "$WORK_DIR" 2>/dev/null; then
  echo "FAIL: unzip reported an error. Either the password is wrong, the zip is" >&2
  echo "      corrupt, or the zip was created with a format unzip does not support" >&2
  echo "      (e.g. 7z, RAR). For 7z, install p7zip-full and rerun with: 7z x -p\"...\" \"$ZIP\" -o\"$WORK_DIR\"" >&2
  exit 1
fi

# Find the keystore inside the extracted tree. The convention is .jks or
# .keystore; if both are present, the larger one is the keystore (the
# smaller is usually a debug-key stub shipped by mistake).
KEYSTORE="$(find "$WORK_DIR" -type f \( -name '*.jks' -o -name '*.keystore' \) -printf '%s %p\n' 2>/dev/null \
  | sort -rn | head -1 | awk '{print $2}')"

if [ -z "$KEYSTORE" ]; then
  echo "FAIL: no .jks or .keystore file inside $ZIP. Files extracted:" >&2
  find "$WORK_DIR" -type f -printf '  %p\n' >&2
  exit 1
fi

echo "Found keystore: $KEYSTORE"
SIZE=$(stat -c '%s' "$KEYSTORE" 2>/dev/null || stat -f '%z' "$KEYSTORE")
echo "Size: $SIZE bytes"
if command -v sha256sum >/dev/null 2>&1; then
  SHA=$(sha256sum "$KEYSTORE" | awk '{print $1}')
else
  SHA=$(shasum -a 256 "$KEYSTORE" | awk '{print $1}')
fi
echo "SHA-256 (of the keystore file, not the cert): $SHA"
echo ""

echo "keytool listing (verify the alias and the canonical SHA-256 below matches"
echo "testing/verify-release-signer.sh's EXPECTED value):"
echo ""
# The store password is needed to read the listing; ask for it interactively
# (never via argv, so it does not end up in shell history).
read -r -s -p "keystore store password (for keytool -list, not echoed): " STORE_PASS </dev/tty
echo ""
LISTING=$(keytool -list -v -keystore "$KEYSTORE" -storepass "$STORE_PASS" 2>&1 || true)
printf '%s\n' "$LISTING" | sed 's/^/  /'
echo ""

# The first alias in the keystore is the one AGP's signing config uses by
# default. The user can confirm by reading the listing above.
ALIAS=$(printf '%s\n' "$LISTING" | awk '/^Alias name:/{print $3; exit}')
CERT_SHA=$(printf '%s\n' "$LISTING" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-Z' 'a-z')

echo "Detected alias: $ALIAS"
echo "Certificate SHA-256: $CERT_SHA"
echo "Canonical expected:   0c69794b7934452bdf1b2f1f345314e13b86ef43f0d17bec9ff260dd30a443be"
if [ "$CERT_SHA" = "0c69794b7934452bdf1b2f1f345314e13b86ef43f0d17bec9ff260dd30a443be" ]; then
  echo "  ✓ matches canonical EclipseSSH release key."
else
  echo "  ✗ DOES NOT MATCH. Do not use this keystore for EclipseSSH release signing."
  echo "    Either this is a different keystore (e.g. a debug key), or the canonical"
  echo "    key has been rotated and testing/verify-release-signer.sh is stale."
fi
echo ""

# Base64-encode the keystore. macOS base64 needs -i 0 to disable line wrapping;
# GNU base64 needs -w 0. Detect which one we have.
echo "Base64-encoded keystore (paste this as RELEASE_KEYSTORE_BASE64 in"
echo "Settings -> Secrets and variables -> Actions):"
echo ""
if base64 --help 2>&1 | grep -q -- -w; then
  base64 -w 0 "$KEYSTORE"
else
  base64 -i 0 "$KEYSTORE"
fi
echo ""
echo ""

echo "=========================================================================="
echo "GitHub Secrets to set (Settings -> Secrets and variables -> Actions):"
echo "=========================================================================="
echo ""
echo "  RELEASE_KEYSTORE_BASE64 = (the base64 string printed above)"
echo ""
echo "  RELEASE_KEY_ALIAS       = $ALIAS  (the alias detected from the listing)"
echo ""
echo "  RELEASE_STORE_PASSWORD  = the store password you typed above"
echo "                           (NOT printed here for security — you own the"
echo "                           keystore, type it into the GitHub secret UI"
echo "                           directly. Clear your terminal scrollback"
echo "                           after running this script.)"
echo ""
echo "  RELEASE_KEY_PASSWORD    = the key password you typed above"
echo "                           (same — type into the GitHub secret UI"
echo "                           directly, do not paste from this output.)"
echo ""
echo "=========================================================================="
echo "After setting the four secrets, delete the zip and any local copy of the"
echo "keystore. The zip password is now compromised (it was shared in a chat"
echo "log); treat the zip itself as compromised and shred it:"
echo "  shred -uvz $ZIP"
echo "=========================================================================="
