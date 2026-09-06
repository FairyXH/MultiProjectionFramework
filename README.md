# LSPosed Universal Template

[![License: CC BY-NC-ND 4.0](https://img.shields.io/badge/License-CC%20BY--NC--ND%204.0-lightgrey.svg)](LICENSE)

A quick-start Android/Vector module template for authorized testing and rapid prototyping.

What it includes:

- Modern `libxposed` API 102 entry point (`io.github.libxposed:api:102.0.0`, `compileOnly`).
- One module APK for both rooted Vector and rootless LSPatch 1.2. LSPatch 1.x is rebuilt on Vector
  and loads modern libxposed API-102 modules through `META-INF/xposed/java_init.list`; the obsolete
  API-93-only `lspatch` flavor is no longer needed. See [`docs/LSPATCH_NONROOT.md`](docs/LSPATCH_NONROOT.md).
- Both `onPackageLoaded` and `onPackageReady` callbacks are overridden for broad compatibility.
- Process-level filter: `TemplateConfig.TARGET_PROCESS_SUFFIXES` + `SKIP_PROCESS_SUFFIXES`
  default to hooking only the main process and skipping common anti-cheat / push /
  crash-handler satellite processes.
- Modern libxposed metadata under `META-INF/xposed/`:
  - `java_init.list`
  - `scope.list`
  - `module.prop` with the correct `exceptionMode=protective` key
- An Application.attach hook for initialization; add app-specific behavior using the API-102 interceptor chain.
- `FeatureRegistry` — bool/float settings and overlay toggles. State is stored in
  `TemplateConfig.FEATURE_STATE_FILE_NAME` under the target app's `getFilesDir()`.
  Writes are synchronous and best-effort; keep changes out of high-frequency hook callbacks.
  This is target-process storage, not a companion-app settings bridge.
- `EngineDetector` — identifies Unity / Unreal / Cocos2d-x / Godot / Flutter /
  React-Native / Xamarin targets at startup so you can branch hook strategies.
- `NativeUtils` — JNI helpers for `/proc/self/maps` module lookup, IDA-style pattern
  scan, `dlsym` resolution, and memory read/write helpers with mapping checks. Java exception
  handling does not catch native faults or prevent a concurrent unmap; see the API comments.
- A movable dark/lavender Nyx-styled floating menu:
  - movable oval bubble
  - movable rectangular panel when opened (drag the header)
  - one toggle row per `FeatureRegistry` bool feature
- Optional native scaffold using ByteDance ShadowHook (`com.bytedance.android:shadowhook:2.0.1`)
  registered via `JNI_OnLoad` / `RegisterNatives` so there are no package-derived JNI export
  names in the `.so` symbol table.
- Debug/release split with `VERBOSE_LOGS` as a `BuildConfig` field — release builds strip
  verbose logs and run R8. Note that `proguard-rules.pro` deliberately `-keep`s the classes the
  framework/JNI must find by name — the entry class (`ModuleEntry`), `NativeBridge`, and
  `NativeUtils` (their method names are bound as strings in `JNI_OnLoad`), and
  the `FeatureRegistry.KEY_*` constants — so those names are **not** obfuscated and stay visible to
  an app enumerating its classloader. R8 can rename or remove other code according to reachability and its rules.
- Neutral log tag (`AppRuntime`) and worker thread name; release builds disable verbose Java and
  native logging by default.
- The documented LSPatch/SAI workflow deliberately signs both the module and every patched target
  split with the same existing Android debug key at `${HOME}/.android/debug.keystore`. This is a
  stable local test key, not a production-distribution key.
- Configure script supports `--native-lib` to rename the packaged `.so` away from the
  obvious `libtemplate_native.so`.
- Frida-first Android emulator workflow documentation for reconnaissance before building
  permanent hooks.
- Engine-specific native workflow notes for Unity IL2CPP and other native-heavy targets,
  kept as optional documentation so the template remains engine-neutral.

Use this only on apps/systems you own or are authorized to test.

## Current researched versions

- Verified against upstream releases on **2026-09-06**. The supported device floor is
  **Android 9 (API 28)** (`minSdk=28`); compile/target SDK 37 does not raise that minimum.
- `libxposed` API: [`102.0.0`](https://github.com/libxposed/api/releases/tag/102.0.0), still the
  latest stable API. Rooted [Vector 2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2)
  and rootless LSPatch 1.2 implement API 102.
- LSPatch: stable [`v1.2`, build 487](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)
  (2026-08-23). `./tools/download-lspatch.sh` downloads and verifies the pinned CLI JAR into
  `artifacts/lspatch/`. Re-patch existing targets to upgrade their embedded runtime, including
  targets patched with 1.0 or 1.1. See [`docs/LSPATCH_NONROOT.md`](docs/LSPATCH_NONROOT.md).
- ByteDance ShadowHook: [`2.0.1`](https://github.com/bytedance/android-inline-hook/releases/tag/v2.0.1),
  still the latest stable release. It supports `armeabi-v7a` and `arm64-v8a`, including Android 9;
  it does not support `x86`/`x86_64` or Houdini-translated environments.
- Build tools: [Android Gradle Plugin `9.4.0`](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
  [Gradle `9.7.1`](https://docs.gradle.org/9.7.1/release-notes.html), compile/target SDK 37,
  Android NDK `29.0.14206865`, CMake `4.1.2`, and AndroidX Annotation `1.10.0`.
  NDK r29 remains [the latest stable NDK](https://developer.android.com/ndk/downloads); r30 is a preview.
- Use JDK 21 for the host build and patcher (also used in CI). The resolved Kotlin stdlib remains
  `2.2.10`, managed by AGP's built-in toolchain and ShadowHook; do not override just the stdlib
  independently of the compiler. No separate Kotlin plugin or runtime pin is needed for this
  Java template.

## Quick start

From this directory:

```bash
./tools/configure-template.py \
  --package com.yourname.yourmodule \
  --name "Your Module" \
  --target com.example.target \
  --author "YourName" \
  --native-lib audio_util      # optional; renames libtemplate_native.so
```

Then build:

```bash
./gradlew :app:assembleRelease
```

Install:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Enable the module in Vector, select the target scope, then force-stop and reopen the target app:

```bash
adb logcat -c
adb shell am force-stop com.example.target
adb shell monkey -p com.example.target 1
adb logcat -s AppRuntime shadowhook_tag
```

Release builds disable verbose logs (`BuildConfig.VERBOSE_LOGS=false`); native warnings/errors
can still appear. Use `./gradlew :app:installDebug` for diagnostic logs. Clear logcat before
launching so startup messages are retained.

The configure tool validates options and source destinations before writing. It escapes display
names for Java and Android XML, uses a package-based Gradle identifier, and detects the current
namespace/native name on later runs. It leaves tools and generated artifacts untouched. Details and host regression checks are in
[`tools/README.md`](tools/README.md) and [`tests/README.md`](tests/README.md).

## Files you normally edit first

- `app/src/main/java/com/template/lsposed/TemplateConfig.java`
- `app/src/main/resources/META-INF/xposed/scope.list`
- `app/src/main/resources/META-INF/xposed/module.prop`
- `app/src/main/res/values/strings.xml`

## Test isolation and runtime work

Regression tests live in the repository-root `tests/` directory and run on the host or in CI.
They are outside the Android source sets and CMake library inputs: test runners, Java test doubles,
fixtures and ASan binaries are not packaged in the module. The module installs no Activity.onResume
or libc getpid smoke-test hooks and keeps no per-invocation demo counters. Its Application.attach
hook remains responsible for actual module initialization. Bounds and input checks that prevent
invalid memory access or corrupt settings remain in place.

## Modern libxposed API shape

The entry class extends `io.github.libxposed.api.XposedModule` and is listed in:

```text
app/src/main/resources/META-INF/xposed/java_init.list
```

Hooking is interceptor-chain based:

```java
hook(method)
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept(chain -> {
        Object result = chain.proceed();
        // post-call logic
        return result;
    });
```

Important points:

- Keep `libxposed` as `compileOnly`; never package the framework API into the APK.
- Keep scope tight. Do not hook every app unless you are building a framework/system module and know why.
- Use `PROTECTIVE` exception mode for release builds so hook failures do not crash the target app.
- Use `deoptimize(executable)` only when you actually need it; overusing deopt can hurt performance.
- API 102 hot reload is deliberately disabled (`autoHotReload=false`): the template owns native
  hooks, a worker thread, and Activity lifecycle callbacks, so safe hot reload requires a
  target-specific teardown/unhook implementation first.

## ShadowHook notes

ShadowHook is the recommended native inline-hook scaffold in this template because it is actively maintained and supports:

- hook by function address or `library + symbol`
- pending hooks for ELFs loaded later
- hook modes per proxy function (`shared`, `multi`, `unique`)
- instruction-level intercepts
- linker init/fini callbacks
- operation records for debugging

The native scaffold initializes ShadowHook and installs no demonstration hooks. Add native hooks only for your configured target, using validated symbols or addresses.

See `docs/SHADOWHOOK_NOTES.md`.

## Engine-specific native workflows

The template is deliberately not IL2CPP-specific. `EngineDetector` is only a routing helper:
use it to decide which research notes or hook installers are relevant, but keep target-specific
offsets, metadata dumps, and generated analysis files out of `main`.

For Unity IL2CPP targets, see `docs/ENGINE_NATIVE_WORKFLOWS.md`. That document covers static
metadata recovery, RVA-to-runtime-address mapping, value-type ABI checks, delayed library loading,
and settings-bridge issues that also apply to other native-heavy engines. Treat it as a playbook
for a branch that targets one app, not as default template behavior.

## Non-root delivery (LSPatch)

LSPatch 1.2 uses Vector's runtime and accepts this template's modern API-102 module directly. Build
the same release APK used by rooted Vector. For this template, the non-root workflow is always:

1. embed the module with `-m` (never use manager mode);
2. sign the module and every patched target split with `${HOME}/.android/debug.keystore`;
3. package the patched target APK set as `target-lspatched.apks`; and
4. install that bundle with SAI (Split APKs Installer).

```bash
ANDROID_DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"
test -f "$ANDROID_DEBUG_KEYSTORE"
env -u TEMPLATE_KS_PATH -u TEMPLATE_KS_PASS \
  -u TEMPLATE_KEY_ALIAS -u TEMPLATE_KEY_PASS \
  ./gradlew :app:assembleRelease
```

The module is already nested inside the patched base APK, so do not add `app-release.apk` as a
separate top-level member of the `.apks` archive. The complete copy-paste patch, signing, bundling,
verification, and SAI installation workflow is in
[`docs/LSPATCH_NONROOT.md`](docs/LSPATCH_NONROOT.md).

## Frida-first workflow

Use Frida first to answer questions like:

- What process and ABI am I actually in?
- Which classes/methods are loaded?
- Which native libraries load and when?
- Which exported symbols exist?
- Which Java methods or native symbols are stable enough to become permanent libxposed/ShadowHook hooks?

Use the latest undetected Frida server linked in `docs/FRIDA_EMULATOR_QUICKSTART.md` for this workflow. Keep the host `frida-tools` version and device `frida-server` version aligned, and do not mix these steps with stock `frida-server` unless you are intentionally debugging a version/build mismatch.

See `docs/FRIDA_EMULATOR_QUICKSTART.md`.

## Moving the menu panel

Tap the `Nyx` bubble to open the rectangular display. Drag the title/subtitle header area to move the open panel. The bubble itself is also draggable.

## Architecture warning

If you use an x86/x86_64 emulator, Java libxposed hooks can still work, but the native ShadowHook scaffold will not. For native inline-hook testing, use a real arm64 device or an arm64 emulator image on Apple Silicon.

## Repository files

- `LICENSE` — CC BY-NC-ND 4.0 license notice and official license links.
- `SECURITY.md` — safe issue-reporting expectations.
- `CONTRIBUTING.md` — contribution and validation expectations.
- `docs/ENGINE_NATIVE_WORKFLOWS.md` — optional notes for Unity IL2CPP and native-heavy targets.
- `docs/LSPATCH_NONROOT.md` — non-root delivery via LSPatch 1.2 (modes, splits, signing, CLI).
- `.github/workflows/android.yml` — GitHub Actions build for debug and release APKs.

## License

This project is licensed under Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International (`CC-BY-NC-ND-4.0`). You may share the unmodified template with attribution for non-commercial use. Do not distribute modified versions without separate permission.
