#!/usr/bin/env bash
# One-time release signing setup.
#
# WHY THIS EXISTS
#   Without a keystore, every GitHub Actions run signs the release APK with a THROWAWAY debug key that
#   is regenerated per job. Android treats each build as signed by a different app, so installing a new
#   version over the old one fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and you must uninstall first.
#   Generating the key once and storing it as a repo secret makes every future build upgrade cleanly.
#
# USAGE (run once, on this machine):
#   ./tools/make-keystore.sh
#   gh secret set LIMOO_KEYSTORE_B64      < limoo-release.jks.base64
#   gh secret set LIMOO_KEYSTORE_PASSWORD --body '<store password>'
#   gh secret set LIMOO_KEY_ALIAS         --body 'limoo'
#   gh secret set LIMOO_KEY_PASSWORD      --body '<key password>'
#   rm limoo-release.jks.base64           # never commit the key or its base64
#
# Then every push builds an APK that installs over the previous one. Keep the .jks somewhere safe:
# losing it means you cannot update existing installs without another uninstall.

set -euo pipefail
cd "$(dirname "$0")/.."

STORE_PASS="${1:-}"
KEY_PASS="${2:-$STORE_PASS}"

if [ -z "$STORE_PASS" ]; then
  echo "usage: $0 <store-password> [key-password]" >&2
  echo "  (key password defaults to the store password)" >&2
  exit 2
fi

OUT="limoo-release.jks"
if [ -e "$OUT" ]; then
  echo "$OUT already exists - refusing to overwrite it." >&2
  echo "Deleting or rotating this key breaks updates for everyone who already has the app." >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias limoo \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=Limoo, OU=Limoo, O=Limoo, L=-, ST=-, C=US" 2>&1 | tail -3

base64 -w0 "$OUT" > "$OUT.base64"

echo
echo "Created $OUT (alias 'limoo', valid 30 years)."
echo "Now run:"
echo "  gh secret set LIMOO_KEYSTORE_B64      < $OUT.base64"
echo "  gh secret set LIMOO_KEYSTORE_PASSWORD --body '<the store password you just chose>'"
echo "  gh secret set LIMOO_KEY_ALIAS         --body 'limoo'"
echo "  gh secret set LIMOO_KEY_PASSWORD      --body '<the key password you just chose>'"
echo "  rm $OUT $OUT.base64"
echo
echo "Keep $OUT somewhere safe and outside the repo."
