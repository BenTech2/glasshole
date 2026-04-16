#!/usr/bin/env bash
# Assembles release artifacts for a GlassHole version tag.
#
# Produces in dist/<version>/ :
#   glasshole-phone.apk
#   glasshole-glass-ee1-<version>.zip
#   glasshole-glass-ee2-<version>.zip
#   glasshole-glass-xe-<version>.zip
#
# Each glass zip contains the base app for that variant, the 5 core plugins,
# and the matching stream-viewer variant. The stream viewer is treated as a
# core component — share-to-glass for YouTube/Twitch relies on it and every
# glass should have one installed out of the box.
#
# Usage:
#   scripts/build-release.sh <version>           # release build (default)
#   scripts/build-release.sh <version> debug     # debug build
#
# Example:
#   scripts/build-release.sh v0.3.0-alpha

set -euo pipefail

VERSION="${1:?usage: build-release.sh <version> [debug|release]}"
VARIANT="${2:-release}"

case "$VARIANT" in
    debug)   V_CAP=Debug   ;;
    release) V_CAP=Release ;;
    *) echo "variant must be 'debug' or 'release'" >&2; exit 1 ;;
esac
V_LOW="${VARIANT}"

cd "$(dirname "$0")/.."

DIST="dist/${VERSION}"
mkdir -p "${DIST}"

VARIANTS="ee1 ee2 xe"

# Lookup: base-app module for a given variant
base_module() {
    case "$1" in
        ee1) echo glass-ee1 ;;
        ee2) echo glass-ee2 ;;
        xe)  echo glass-xe ;;
    esac
}

# Lookup: stream-viewer module for a given variant
viewer_module() {
    case "$1" in
        ee1) echo stream-viewer-ee1 ;;
        ee2) echo stream-viewer-ee2 ;;
        xe)  echo stream-viewer-xe ;;
    esac
}

# Plugins shared across all variants.
PLUGINS_COMMON="plugin-notes-glass plugin-calc-glass plugin-device-glass plugin-gallery-glass"
# Plugins only in the EE2 bundle (minSdk 27).
PLUGINS_EE2_EXTRA="plugin-camera2-glass plugin-gallery2-glass"

# ── Build all APKs ────────────────────────────────────────────────────────
GRADLE_TASKS=( ":phone:assemble${V_CAP}" )
for v in $VARIANTS; do
    GRADLE_TASKS+=( ":$(base_module "$v"):assemble${V_CAP}" )
    GRADLE_TASKS+=( ":$(viewer_module "$v"):assemble${V_CAP}" )
done
for p in $PLUGINS_COMMON $PLUGINS_EE2_EXTRA; do
    GRADLE_TASKS+=( ":${p}:assemble${V_CAP}" )
done

echo "▶ building ${#GRADLE_TASKS[@]} modules (${VARIANT})"
./gradlew "${GRADLE_TASKS[@]}"

# apk_path <module> → prints path to the built APK (debug or release, signed or unsigned)
apk_path() {
    local module="$1"
    local dir="${module}/build/outputs/apk/${V_LOW}"
    for candidate in \
        "${dir}/${module}-${V_LOW}.apk" \
        "${dir}/${module}-${V_LOW}-unsigned.apk" \
    ; do
        [[ -f "$candidate" ]] && { echo "$candidate"; return 0; }
    done
    echo "✗ no APK found for module ${module} under ${dir}" >&2
    ls "${dir}" >&2 || true
    return 1
}

# ── Phone APK ─────────────────────────────────────────────────────────────
cp "$(apk_path phone)" "${DIST}/glasshole-phone.apk"
echo "✓ ${DIST}/glasshole-phone.apk"

# ── Per-variant glass zip ─────────────────────────────────────────────────
for v in $VARIANTS; do
    ZIP_NAME="glasshole-glass-${v}-${VERSION}.zip"
    STAGE="${DIST}/_stage-${v}"
    rm -rf "${STAGE}"
    mkdir -p "${STAGE}"

    # Base app
    cp "$(apk_path "$(base_module "$v")")" "${STAGE}/glasshole-base-${v}.apk"

    # Stream viewer (core component — every glass gets one)
    cp "$(apk_path "$(viewer_module "$v")")" "${STAGE}/glasshole-stream-viewer-${v}.apk"

    # Common plugins. Rename "plugin-notes-glass" → "plugin-notes.apk" in the zip.
    for p in $PLUGINS_COMMON; do
        short="${p#plugin-}"; short="${short%-glass}"
        cp "$(apk_path "${p}")" "${STAGE}/plugin-${short}.apk"
    done

    # EE2-only extras
    if [[ "$v" == "ee2" ]]; then
        for p in $PLUGINS_EE2_EXTRA; do
            short="${p#plugin-}"; short="${short%-glass}"
            cp "$(apk_path "${p}")" "${STAGE}/plugin-${short}.apk"
        done
    fi

    (cd "${STAGE}" && zip -q -r "../${ZIP_NAME}" .)
    rm -rf "${STAGE}"
    echo "✓ ${DIST}/${ZIP_NAME}"
done

echo ""
echo "Done. Artifacts in ${DIST}/"
ls -lh "${DIST}/"
