#!/usr/bin/env bash
# Validate every generated Xray config with the REAL core.
#
# `xray run -test -c <file>` parses the config and builds every outbound and routing rule, then exits.
# That is precisely the stage the connect bug lived in: the core rejected a config it considered
# malformed before a single packet was sent, and the error named a JSON path rather than the value.
# No network and no root are needed, so it runs fine in CI.
#
# Run only in CI. Locally it is pointless - if you could run the binary you would not need this.
#
# Requires the matrix to have been generated first:
#   ./gradlew :app:testDebugUnitTest   (XrayConfigMatrixTest writes app/build/xray-configs/)
set -euo pipefail

VERSION="${XRAY_VERSION:-}"
if [ -z "$VERSION" ]; then
  echo "::error::XRAY_VERSION is not set. Pin it in gradle.properties as limoo.xrayVersion."
  exit 1
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ZIP_URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}/Xray-linux-64.zip"
echo "Downloading $ZIP_URL"
if ! curl -fsSL --retry 3 -o "$WORK/xray.zip" "$ZIP_URL"; then
  echo "::error::Could not download Xray ${VERSION} for linux-64."
  exit 1
fi

unzip -qo "$WORK/xray.zip" -d "$WORK/xray"
chmod +x "$WORK/xray/xray"
echo "Core reports: $("$WORK/xray/xray" version | head -1)"

# Geo rules (geosite:/geoip:) resolve against data files next to the binary. Without them a config that
# is genuinely fine would be reported as broken, which is a false failure - so fetch the same files the
# app uses. A config with no geo rules does not need them.
GEO_OK=0
mkdir -p "$WORK/xray"
for asset in geoip.dat geosite.dat; do
  if curl -fsSL --retry 3 -o "$WORK/xray/$asset" \
      "https://github.com/Chocolate4U/Iran-sing-box-rules/releases/latest/download/$asset" 2>/dev/null \
   || curl -fsSL --retry 3 -o "$WORK/xray/$asset" \
      "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/$asset" 2>/dev/null; then
    echo "geo asset: $asset ok"
  else
    echo "::warning::could not fetch $asset - configs using geo rules will report a false failure"
    GEO_OK=1
  fi
done

export XRAY_LOCATION_ASSET="$WORK/xray"

DIR="app/build/xray-configs"
if [ ! -d "$DIR" ]; then
  echo "::error::$DIR does not exist - did :app:testDebugUnitTest run?"
  exit 1
fi

total=0; failed=0
for f in "$DIR"/*.json; do
  [ -e "$f" ] || continue
  total=$((total + 1))
  if ! out="$("$WORK/xray/xray" run -test -c "$f" 2>&1)"; then
    failed=$((failed + 1))
    echo "::error::core REJECTED $f"
    echo "$out" | sed 's/^/    /'
    # Print the offending config; without it the failure is not reproducible.
    sed 's/^/    | /' "$f"
    [ "$failed" -ge 5 ] && { echo "::error::stopping after 5 failures"; break; }
  fi
done

echo "checked $total configs, $failed rejected by the core"
if [ "$failed" -ne 0 ]; then
  echo "::error::$failed config(s) were rejected by Xray ${VERSION}. The builder produced something the core will not accept."
  exit 1
fi
if [ "$GEO_OK" -ne 0 ]; then
  echo "::warning::geo assets were unavailable, so geo-dependent configs were NOT fully verified"
fi
echo "all $total configs accepted by Xray ${VERSION}"
