#!/usr/bin/env bash
# Assembles release artifacts for a GlassHole version tag.
#
# Produces in dist/<version>/ :
#   glasshole-phone.apk
#   glasshole-glass-ee1-<version>.zip
#   glasshole-glass-ee2-<version>.zip
#   glasshole-glass-xe-<version>.zip
#
# Each glass zip contains the base app for that variant, the Core plugins,
# the matching Stream Player, and (for EE2) the EE2-only plugins.
#
# Usage:
#   scripts/build-release.sh <version>           # release build (default)
#   scripts/build-release.sh <version> debug     # debug build

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

# Module ID → filesystem projectDir. Must mirror settings.gradle.kts.
module_dir() {
    case "$1" in
        phone)                   echo apps/phone ;;
        glass-ee1)               echo apps/core/glass-ee1 ;;
        glass-ee2)               echo apps/core/glass-ee2 ;;
        glass-xe)                echo apps/core/glass-xe ;;
        stream-player-ee1)       echo apps/core/stream-player-ee1 ;;
        stream-player-ee2)       echo apps/core/stream-player-ee2 ;;
        stream-player-xe)        echo apps/core/stream-player-xe ;;
        plugin-device-glass)     echo apps/core/plugin-device-glass ;;
        plugin-gallery-glass)    echo apps/core/plugin-gallery-glass ;;
        plugin-notes-glass)      echo apps/plugins/plugin-notes-glass ;;
        plugin-calc-glass)       echo apps/plugins/plugin-calc-glass ;;
        plugin-gallery2-glass)   echo apps/plugins/plugin-gallery2-glass ;;
        plugin-camera2-glass)    echo apps/plugins/plugin-camera2-glass ;;
        plugin-openclaw-glass)   echo apps/plugins/plugin-openclaw-glass ;;
        plugin-chat-glass)       echo apps/plugins/plugin-chat-glass ;;
        plugin-nav-glass)        echo apps/plugins/plugin-nav-glass ;;
        plugin-compass-glass)    echo apps/plugins/plugin-compass-glass ;;
        plugin-media-glass)      echo apps/plugins/plugin-media-glass ;;
        plugin-broadcast-glass)        echo apps/plugins/plugin-broadcast-glass ;;
        plugin-broadcast-legacy-glass) echo apps/plugins/plugin-broadcast-legacy-glass ;;
        *) echo "unknown module: $1" >&2; return 1 ;;
    esac
}

base_module() {
    case "$1" in
        ee1) echo glass-ee1 ;;
        ee2) echo glass-ee2 ;;
        xe)  echo glass-xe ;;
    esac
}

player_module() {
    case "$1" in
        ee1) echo stream-player-ee1 ;;
        ee2) echo stream-player-ee2 ;;
        xe)  echo stream-player-xe ;;
    esac
}

# Core plugins (photo-sync gallery, device controls). Shared across variants.
PLUGINS_CORE_COMMON="plugin-device-glass plugin-gallery-glass"
# User-facing plugins shared across variants.
PLUGINS_COMMON="plugin-notes-glass plugin-calc-glass plugin-openclaw-glass plugin-chat-glass plugin-compass-glass"
# EE2-only plugins (minSdk 27).
PLUGINS_EE2_EXTRA="plugin-camera2-glass plugin-gallery2-glass plugin-broadcast-glass"
# EE1 + XE get the Camera1 broadcast variant (minSdk 19).
PLUGINS_EE1_XE_EXTRA="plugin-broadcast-legacy-glass"

# ── Build all APKs ────────────────────────────────────────────────────────
GRADLE_TASKS=( ":phone:assemble${V_CAP}" )
for v in $VARIANTS; do
    # Glass base apps have launcher + standalone flavors; assemble both.
    GRADLE_TASKS+=( ":$(base_module "$v"):assembleStandalone${V_CAP}" )
    GRADLE_TASKS+=( ":$(base_module "$v"):assembleLauncher${V_CAP}" )
    GRADLE_TASKS+=( ":$(player_module "$v"):assemble${V_CAP}" )
done
for p in $PLUGINS_CORE_COMMON $PLUGINS_COMMON $PLUGINS_EE2_EXTRA $PLUGINS_EE1_XE_EXTRA; do
    GRADLE_TASKS+=( ":${p}:assemble${V_CAP}" )
done

echo "▶ building ${#GRADLE_TASKS[@]} modules (${VARIANT})"
./gradlew "${GRADLE_TASKS[@]}"

# apk_path <module> [<flavor>] → path to the built APK, resolved via module_dir.
# When <flavor> is omitted, looks at the unflavored output dir; when provided,
# resolves the flavored layout (e.g. apk/standalone/debug/<module>-<flavor>-<variant>.apk).
apk_path() {
    local module="$1"
    local flavor="${2:-}"
    local base="$(module_dir "${module}")/build/outputs/apk"
    local dir suffix
    if [[ -n "${flavor}" ]]; then
        dir="${base}/${flavor}/${V_LOW}"
        suffix="${flavor}-${V_LOW}"
    else
        dir="${base}/${V_LOW}"
        suffix="${V_LOW}"
    fi
    for candidate in \
        "${dir}/${module}-${suffix}.apk" \
        "${dir}/${module}-${suffix}-unsigned.apk" \
    ; do
        [[ -f "$candidate" ]] && { echo "$candidate"; return 0; }
    done
    echo "✗ no APK found for module ${module} (flavor='${flavor}') under ${dir}" >&2
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

    # Base app — both flavors. Standalone is the default for users who
    # want GlassHole alongside the stock Glass launcher; launcher
    # replaces stock home and adds swipe-down lockNow().
    cp "$(apk_path "$(base_module "$v")" standalone)" "${STAGE}/glasshole-base-${v}-standalone.apk"
    cp "$(apk_path "$(base_module "$v")" launcher)"   "${STAGE}/glasshole-base-${v}-launcher.apk"

    # Stream Player (core)
    cp "$(apk_path "$(player_module "$v")")" "${STAGE}/glasshole-stream-player-${v}.apk"

    # Core plugins (device controls, photo sync). Identical across variants.
    for p in $PLUGINS_CORE_COMMON; do
        short="${p#plugin-}"; short="${short%-glass}"
        cp "$(apk_path "${p}")" "${STAGE}/plugin-${short}.apk"
    done

    # User-facing plugins shared across variants.
    for p in $PLUGINS_COMMON; do
        short="${p#plugin-}"; short="${short%-glass}"
        cp "$(apk_path "${p}")" "${STAGE}/plugin-${short}.apk"
    done

    # EE2-only extras (Camera2 broadcast, camera2, gallery2)
    if [[ "$v" == "ee2" ]]; then
        for p in $PLUGINS_EE2_EXTRA; do
            short="${p#plugin-}"; short="${short%-glass}"
            cp "$(apk_path "${p}")" "${STAGE}/plugin-${short}.apk"
        done
    fi

    # EE1 + XE extras (Camera1 broadcast variant).
    if [[ "$v" == "ee1" || "$v" == "xe" ]]; then
        for p in $PLUGINS_EE1_XE_EXTRA; do
            short="${p#plugin-}"; short="${short%-legacy-glass}"; short="${short%-glass}"
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
