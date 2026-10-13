#!/usr/bin/env bash
# Fails when a keystore is not the canonical release key.
#
# Why this exists: `release.yml` and `tagged-release.yml` both restore the keystore
# from the `RELEASE_KEYSTORE_BASE64` secret and then report "signed with the release
# key" as soon as a build succeeds - a check that *a* key was present, never that
# *the* key was. The failure that misses has already shipped once: v1.5.0 went out
# signed by a keystore regenerated on 2026-08-15 that had replaced the canonical one
# in the checkout, and every existing install answered
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. See AUDIT-REPORT.md section 56.
# `verify-release-signer.sh` catches that from the finished APK, after the build;
# this catches it from the keystore itself, before anything is signed - the
# difference between a wasted run and a shipped mistake.
#
# The expected certificate is read out of `verify-release-signer.sh` rather than
# written again here, so rotating the release key stays a one-file edit and the two
# scripts cannot come to disagree about which key is canonical.
#
# It reads the store password out of the properties and never echoes it. The one
# thing it prints is the certificate, which every published APK carries anyway.
#
# Usage: testing/verify-keystore.sh <keystore> <keystore.properties>
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPECTED="$(sed -n 's/^EXPECTED="\([0-9a-f]\{64\}\)"$/\1/p' "$HERE/verify-release-signer.sh" 2>/dev/null | head -1)"
if [ -z "$EXPECTED" ]; then
  echo "verify-keystore.sh: verify-release-signer.sh no longer holds the canonical fingerprint in the" >&2
  echo "                    shape this script reads - this script is out of date" >&2
  exit 2
fi

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <keystore> <keystore.properties>" >&2
  exit 2
fi
KEYSTORE="$1"
PROPERTIES="$2"

for f in "$KEYSTORE" "$PROPERTIES"; do
  [ -f "$f" ] || { echo "verify-keystore.sh: no such file: $f" >&2; exit 2; }
done

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || {
  echo "verify-keystore.sh: no keytool - set JAVA_HOME or put one on PATH" >&2
  exit 2
}

STOREPASS="$(sed -n 's/^storePassword=//p' "$PROPERTIES" | head -1)"
ALIAS="$(sed -n 's/^keyAlias=//p' "$PROPERTIES" | head -1)"
[ -n "$STOREPASS" ] || { echo "verify-keystore.sh: $PROPERTIES has no storePassword" >&2; exit 2; }
[ -n "$ALIAS" ] || { echo "verify-keystore.sh: $PROPERTIES has no keyAlias" >&2; exit 2; }

# `-list` proves the store password opens the store *and* fails outright when the
# alias is absent, so a properties file that does not belong to this keystore is
# caught here rather than at signing time.
if ! listing="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$STOREPASS" -alias "$ALIAS" 2>&1)"; then
  echo "FAIL: the keystore could not be read with the alias and password in $PROPERTIES"
  printf '%s\n' "$listing" | sed 's/^/    /'
  exit 1
fi

got="$(printf '%s\n' "$listing" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-Z' 'a-z')"
if [ -z "$got" ]; then
  echo "FAIL: keytool reported no SHA-256 fingerprint for alias '$ALIAS'" >&2
  exit 1
fi

if [ "$got" != "$EXPECTED" ]; then
  echo "FAIL: the keystore holds $got"
  echo "      the canonical key is $EXPECTED"
  echo
  echo "Do not sign with this keystore. An APK signed by a different key installs on a"
  echo "fresh device and refuses to install over any existing one."
  exit 1
fi

echo "ok   keystore is the canonical release key (alias '$ALIAS', SHA-256 $got)"
