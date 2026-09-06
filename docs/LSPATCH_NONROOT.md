# Non-root delivery with LSPatch 1.2

Use [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch) to run this module in an app without
root or Zygisk. LSPatch embeds Vector's framework runtime in the target APK. The module itself
remains the same modern libxposed API-102 APK used with rooted Vector.

Only patch apps you own or are explicitly authorized to test.

## 1. Current release and compatibility

Checked on **2026-09-06**: the latest stable release is
[LSPatch **v1.2, build 487**](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2), released
2026-08-23. Use stable for this workflow. The newest published canary checked, `canary-486`, predates
this stable build; a canary label does not necessarily mean newer code. Canaries are prereleases
and only the five newest are retained, so this guide does not depend on a canary download.

| CLI asset | SHA-256 (GitHub release asset digest) |
|---|---|
| `lspatch-v1.2-487-release.jar` | `d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165` |
| `lspatch-v1.2-487-debug.jar` | `5f2afaecc0fe36296bdd607acce2521a1dadd1c7c8ab6e3d163cbc77d8121153` |

Use **JDK 21 or newer** to run these JARs (their CLI classes use Java 21 bytecode). From the
repository root, download and verify the pinned patcher:

```sh
LSPATCH_JAR="$(./tools/download-lspatch.sh)"
java -jar "$LSPATCH_JAR" --help
```

The script downloads the official asset into ignored `artifacts/lspatch/`, fails on a SHA-256
mismatch, and rechecks cached files on every invocation. It prints only the absolute path to
stdout. `./tools/download-lspatch.sh --debug` fetches the matching debug JAR. Check
[`releases/latest`](https://github.com/JingMatrix/LSPatch/releases/latest) before a future upgrade;
update the script's version, build and hashes together with this document.

Compatibility:

- [LSPatch's runtime floor is Android 9 (API 28)](https://github.com/JingMatrix/LSPatch/blob/v1.2/README.md#requirements).
  This module now declares `minSdk=28` too. `compileSdk=37` and `targetSdk=37` do not make it
  Android-17-only. A target app with a higher minimum SDK retains its own requirement.
- Modern API 101+ modules and legacy Xposed modules are supported; this template requires
  **API 102**, supplied by Vector 2.2 and LSPatch 1.2. Keep `libxposed` as `compileOnly`.
- The module's native ShadowHook scaffold supports **arm64-v8a and armeabi-v7a**. LSPatch's broader
  ABI support does not add x86 support to ShadowHook. See [`SHADOWHOOK_NOTES.md`](SHADOWHOOK_NOTES.md).
- Embedded/integrated mode needs neither the manager nor Shizuku on the device after patching.

### What changes from 1.0 / 1.1

[1.1](https://github.com/JingMatrix/LSPatch/releases/tag/v1.1) improves framework logging, patch-file
handling and manager connection recovery. [1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)
fixes re-patching and manager crashes, improves companion settings delivery, and adds optional
signature level 3. The example keeps **level 2**; level 3 is arm64-only, changes native code and
can conflict with code-integrity checks. It is not needed to adopt 1.2.

Companion settings delivery is separate from module-code hot reload. This template's settings
live in the target process, and `autoHotReload=false` remains deliberate: native hooks, workers
and Activity callbacks need teardown before safe code reload. Do not infer that opening the
module launcher will reload a newly built embedded APK.

**Re-patch and reinstall every target whose embedded runtime you want to upgrade**, including
1.0 and 1.1 targets. Updating only the manager or the module's Gradle dependencies cannot replace
an already embedded loader. Start from the original, unpatched base APK and matching splits;
keep those inputs so future upgrades do not depend on unpacking a previous patch.

## 2. Build with the existing Android debug key

This workflow uses one stable local test certificate:

```text
${HOME}/.android/debug.keystore
store password: android
alias:          androiddebugkey
key password:   android
```

Run from the repository root in macOS `zsh`. The subshell stops on an error without changing your
interactive shell's options:

```zsh
(
  set -eu
  set -o pipefail
  ANDROID_DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"
  test -f "$ANDROID_DEBUG_KEYSTORE"
  keytool -list -keystore "$ANDROID_DEBUG_KEYSTORE" -storepass android -alias androiddebugkey

  env -u TEMPLATE_KS_PATH -u TEMPLATE_KS_PASS \
    -u TEMPLATE_KEY_ALIAS -u TEMPLATE_KEY_PASS \
    ./gradlew :app:assembleRelease

  MODULE_APK="$PWD/app/build/outputs/apk/release/app-release.apk"
  unzip -p "$MODULE_APK" META-INF/xposed/module.prop
  unzip -p "$MODULE_APK" META-INF/xposed/java_init.list
)
```

Without those four signing overrides, the release build uses Android's debug signing configuration.
If the key does not exist on a new Mac, run `./gradlew :app:assembleDebug` once to generate it;
do not replace an existing key. When migrating Macs, retain the same test key to allow updates.

Expected metadata includes `minApiVersion=102`, `targetApiVersion=102`, `autoHotReload=false`, and
`com.template.lsposed.ModuleEntry` (or the package chosen with `configure-template.py`). API versions
in `module.prop` are **framework API levels**, not Android SDK levels. Configure both
`TemplateConfig.TARGET_PACKAGES` and `META-INF/xposed/scope.list` for your target before building.

## 3. Required delivery: embedded module inside an SAI bundle

This template's non-root workflow is:

- pass `-m "$MODULE_APK"` to embed the module; do not use `--manager`;
- pass the original target base and every required split to one patch invocation;
- sign the module and all patched targets with the same existing Android debug key;
- package only the patched target APK set into `target-lspatched.apks`; and
- install the bundle with SAI (Split APKs Installer).

The module becomes `assets/lspatch/modules/<module-package>.apk` **inside the patched base**.
Do not put `app-release.apk` at the archive's top level: SAI installs those members as one package,
so a separate module package would invalidate the set. No separate module install is needed.

[SAI's format](https://github.com/Aefyr/SAI/blob/master/META-FORMAT.md) is a renamed ZIP archive.
SAI export metadata (`icon.png`, `meta.sai_v1.json`, `meta.sai_v2.json`) is not required for this
base-and-split installation bundle. This is an SAI bundle, not a bundletool-generated APK set.

## 4. Patch, verify, and create the bundle

The complete block below runs in **macOS zsh** from the repository root. Edit `TARGET_APKS` first.
Use only `base.apk` for a monolithic app. For a split app, put the base first, followed by every
required split from the **same version and device configuration**. Keep conventional `split_*.apk`
filenames: LSPatch 1.2 uses that prefix, together with manifest information, to recognize splits.

Install SDK Build Tools 37.0.0 if needed. `ANDROID_HOME` or `ANDROID_SDK_ROOT` overrides the default
macOS SDK path. No target is installed by this host-side block.

```zsh
(
  set -eu
  set -o pipefail
  LSPATCH_JAR="$(./tools/download-lspatch.sh)"
  MODULE_APK="$PWD/app/build/outputs/apk/release/app-release.apk"
  ANDROID_DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"
  ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}}"
  APKSIGNER="$ANDROID_SDK_DIR/build-tools/37.0.0/apksigner"
  ZIPALIGN="$ANDROID_SDK_DIR/build-tools/37.0.0/zipalign"
  SAI_BUNDLE="$PWD/target-lspatched.apks"

  TARGET_APKS=(
    "$PWD/base.apk"
    "$PWD/split_config.arm64_v8a.apk"
    "$PWD/split_config.en.apk"
    "$PWD/split_config.xxhdpi.apk"
  )

  test -x "$APKSIGNER"
  test -x "$ZIPALIGN"
  for input in "$MODULE_APK" "$ANDROID_DEBUG_KEYSTORE" "${TARGET_APKS[@]}"; do
    test -f "$input" || { printf 'Missing input: %s\n' "$input" >&2; exit 1; }
  done
  PATCH_OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/lspatch-output.XXXXXX")"
  SAI_TEMP_BUNDLE="${PATCH_OUTPUT_DIR}.apks"
  trap 'rm -f -- "$SAI_TEMP_BUNDLE"' EXIT

  java -jar "$LSPATCH_JAR" \
    -m "$MODULE_APK" \
    -k "$ANDROID_DEBUG_KEYSTORE" android androiddebugkey android \
    -o "$PATCH_OUTPUT_DIR" \
    -l 2 \
    "${TARGET_APKS[@]}"

  PATCHED_APKS=("$PATCH_OUTPUT_DIR"/*.apk)
  test "${#PATCHED_APKS[@]}" -eq "${#TARGET_APKS[@]}"

  # apksigner failures propagate through the pipeline because pipefail is enabled.
  signer_digest() {
    "$APKSIGNER" verify --min-sdk-version 28 --verbose --print-certs "$1" \
      | sed -n 's/^.* certificate SHA-256 digest: //p' | sort -u
  }
  MODULE_SIGNER="$(signer_digest "$MODULE_APK")"
  # Build Tools 37 labels signers by scheme (e.g. V2 Signer), older tools use Signer #1.
  # Require one distinct certificate; a repeated V2/V3 certificate is the same signer.
  test "${#MODULE_SIGNER}" -eq 64
  for apk in "${PATCHED_APKS[@]}"; do
    # LSPatch 1.2 aligns ZIP entries to 4 KiB. Align uncompressed .so entries for
    # 16 KiB devices if necessary, then sign again: alignment invalidates signatures.
    if ! "$ZIPALIGN" -c -P 16 4 "$apk" >/dev/null; then
      "$ZIPALIGN" -P 16 -f 4 "$apk" "$apk.aligned"
      "$APKSIGNER" sign --ks "$ANDROID_DEBUG_KEYSTORE" \
        --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
        --min-sdk-version 28 --alignment-preserved true --v4-signing-enabled false "$apk.aligned"
      mv -f "$apk.aligned" "$apk"
    fi
    "$ZIPALIGN" -c -P 16 4 "$apk"
    APK_SIGNER="$(signer_digest "$apk")"
    test "$APK_SIGNER" = "$MODULE_SIGNER"
    printf 'Verified signer and alignment: %s\n' "$apk"
  done

  # zsh arrays are 1-based. Build 487 names outputs <input-stem>-487-lspatched.apk.
  BASE_STEM="$(basename "${TARGET_APKS[1]}" .apk)"
  PATCHED_BASE="$PATCH_OUTPUT_DIR/$BASE_STEM-487-lspatched.apk"
  EMBEDDED_PATH="$(unzip -Z1 "$PATCHED_BASE" \
    | sed -n '/^assets\/lspatch\/modules\/[^/]*\.apk$/p')"
  test -n "$EMBEDDED_PATH"
  unzip -p "$PATCHED_BASE" "$EMBEDDED_PATH" > "$PATCH_OUTPUT_DIR/embedded-module.bin"
  cmp "$MODULE_APK" "$PATCH_OUTPUT_DIR/embedded-module.bin"

  (
    cd "$PATCH_OUTPUT_DIR"
    /usr/bin/zip -q -9 "$SAI_TEMP_BUNDLE" ./*.apk
  )
  /usr/bin/zip -T "$SAI_TEMP_BUNDLE"
  # Replace the prior deliverable only after every check has passed.
  mv -f "$SAI_TEMP_BUNDLE" "$SAI_BUNDLE"
  unzip -Z1 "$SAI_BUNDLE"
  shasum -a 256 "$SAI_BUNDLE"
  printf 'Bundle: %s\nVerified APKs: %s\n' "$SAI_BUNDLE" "$PATCH_OUTPUT_DIR"
)
```

The result is `target-lspatched.apks`, including for a one-APK target. A unique patch directory
prevents stale splits from leaking into the next bundle. A failed patch, missing module, bad signer
or failed archive check stops before replacing the prior bundle. The printed patch directory is
retained for inspection; remove it when no longer needed.

The embedded module is compared byte-for-byte with the release APK. This matters because the
patcher can log an invalid module and skip embedding it without failing the entire patch.
Signature checks explicitly use `--min-sdk-version 28`: split manifests can omit `uses-sdk` and
inherit it from the base. Verifying a split by itself without that floor can incorrectly demand
old JAR/v1 signatures and report `Missing META-INF/MANIFEST.MF` for a valid Android 9 APK set.

The 16 KiB ZIP alignment step also works on 4 KiB Android 9 devices. It checks packaging, not the
ELF segments inside a native library. The module uses NDK r29 and ShadowHook 2.0.1; an older target
library can still lack 16 KiB support. Test the actual target on the intended Android version and
page size. See Android's [16 KiB guidance](https://developer.android.com/guide/practices/page-sizes).
Keep `--alignment-preserved true` when signing the aligned APK: otherwise `apksigner` may move
LSPatch's native payload under `assets/` back to smaller boundaries.

## 5. Install with SAI

1. Copy `target-lspatched.apks` to the device.
2. Open SAI, choose **Install APKs**, and select the bundle.
3. Leave SAI re-signing/signing disabled; all members already share the correct certificate.
4. Approve the Android package-installer prompt.

A store build signed by a different certificate cannot be upgraded in place. If an uninstall is
necessary, back up data first: uninstalling clears app data. Previously patched copies using the
same key can normally update in place, subject to Android's package/version checks. Force-stop and
reopen the target after installing the new bundle.

## 6. CLI options and defaults

`java -jar "$LSPATCH_JAR" --help` is authoritative for the downloaded version. The v1.2 CLI's
signature default is **0**, even though the manager/release notes describe level 2 as the default.
This guide passes `-l 2` explicitly; do not drop it accidentally when upgrading commands.

| Option | Use in this workflow |
|---|---|
| `-m`, `--embed <apk>` | Required; embeds this module. |
| `-k`, `--keystore <file> <store-pass> <alias> <key-pass>` | Required; use the existing Android debug key. |
| `-l`, `--sigbypasslv 2` | Explicit `PackageManager` and libc `openat` signature handling. Level 3 is optional and arm64-only. |
| `-o`, `--output <dir>` | Use a new directory for each patch set. |
| `-f`, `--force` | Overwrites existing patch output; unnecessary with the new temporary directory. |
| `--injectdex` | Optional direct loader-dex injection for targets that need it. |
| `--documents-provider` | Optional exposure of the target's private data through the document picker. |
| `--version-code <n>` | Optional version-code override. |
| `--name <label>` | Optional launcher-label override. |
| `--target-sdk <n>` | Optional target SDK override; does not lower the target's minimum SDK. |
| `--extract-libs` | Optional `android:extractNativeLibs=true`. |
| `--cleartext` | Optional `android:usesCleartextTraffic=true`. |
| `--add-permission <name>` | Optional permission addition; repeat as needed. |
| `-d`, `--debuggable` | Optional debuggable target build. |
| `-v`, `--verbose` | Optional verbose patcher log. |

`--manager` and `--manager-package` belong to manager mode and are not used here. The standard
debug key is for repeatable local test installs, not production distribution. Signature handling
cannot satisfy Play Integrity, server-side attestation or every app-specific integrity check.

## 7. Upgrade and troubleshooting checklist

- [ ] Downloaded the pinned stable v1.2 build 487 and passed SHA-256 verification.
- [ ] Used JDK 21+ and rebuilt the API-102 module for the configured target package/scope.
- [ ] Started from original target APKs, including all required matching splits.
- [ ] Used embedded mode, explicit `-l 2`, and the same debug key for module and target APKs.
- [ ] Compared the embedded module with the freshly built release APK.
- [ ] Verified every target's signer and native-library ZIP alignment before bundling.
- [ ] Bundled only the patched target base and splits, then installed through SAI without re-signing.
- [ ] Re-patched and reinstalled after changing either LSPatch or the embedded module.
- [ ] Kept root Vector/LSPosed injection out of the same target during an LSPatch test.
- [ ] Tested startup, Java hooks, native hooks and overlay on the actual supported device/ABI.

For a failed download, check the reported digest; remove a corrupt cached JAR explicitly and retry.
For `UnsupportedClassVersionError`, select JDK 21+. For missing hooks, check scope, process filters,
modern metadata and the embedded module comparison. For loader failures, use the matching debug
JAR (`./tools/download-lspatch.sh --debug`) with `-v`, rebuild with `assembleDebug` if module logs
are needed, re-patch, force-stop and capture fresh logcat. Record LSPatch version/build, Android API,
ABI and the target's original version when reporting a problem.
