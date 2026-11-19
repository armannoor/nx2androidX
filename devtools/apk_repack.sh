#!/usr/bin/env bash
# Repack the debug APK into a small one: the same app without the zip's slack.
#
#   bash devtools/apk_repack.sh [apk]
#
# The Android build leaves a big hole in the debug APK (a few hundred megabytes) so that the next build can
# write into it; the app's own content is a few tens of megabytes. Installing that over adb sends the whole
# thing, which is slow for nothing. This writes the entries without the hole, aligns them and signs the
# result with the debug key — the same key the build used, so it installs over the build that is on the phone.
#
# The result is for installing on a phone (and for handing to someone); the release APK is built by the CI
# with the payload and is signed there.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
root=$PWD

apk=${1:-$(ls -t "$root/android/app/build/outputs/apk/debug"/app-debug.apk 2>/dev/null | head -1)}
[[ -n ${apk:-} && -s $apk ]] || { echo "apk_repack: no APK to repack (build one first)" >&2; exit 1; }

# the build tools: apksigner and zipalign (the newest in the SDK)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
tools=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[[ -n ${tools:-} && -x ${tools}apksigner ]] || { echo "apk_repack: no apksigner in $sdk/build-tools" >&2; exit 1; }

key=${NX2A_KEYSTORE:-$HOME/.android/nx2android.keystore}
pass=${NX2A_KEYSTORE_PASSWORD:-nx2android}
alias=${NX2A_KEY_ALIAS:-nx2android}
if [[ ! -f $key ]]; then
    # the machine's debug key: the build fell back to it too
    key=$HOME/.android/debug.keystore
    pass=android
    alias=androiddebugkey
fi
[[ -f $key ]] || { echo "apk_repack: no key to sign with" >&2; exit 1; }

out=${apk%.apk}-small.apk
tmp=${apk%.apk}-repacked.apk
echo ">> repacking $(basename "$apk") ($(du -m "$apk" | cut -f1) MB)"
python3 "$root/devtools/apk_repack.py" "$apk" "$tmp"
"$tools/zipalign" -p -f 4 "$tmp" "$tmp.aligned"
rm -f "$tmp"
mv "$tmp.aligned" "$tmp"
"$tools/apksigner" sign --ks "$key" --ks-pass "pass:$pass" --key-pass "pass:$pass" --ks-key-alias "$alias" --out "$out" "$tmp"
rm -f "$tmp"
"$tools/apksigner" verify --print-certs "$out" >/dev/null || echo "   (the signature does not verify: it is a debug build)"
echo
echo "APK: $out ($(du -m "$out" | cut -f1) MB)"
