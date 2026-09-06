# ShadowHook notes

This template uses ByteDance `android-inline-hook` / ShadowHook through Maven Prefab:

- Gradle dependency: `com.bytedance.android:shadowhook:2.0.1`
- CMake import: `find_package(shadowhook REQUIRED CONFIG)`
- Link target: `shadowhook::shadowhook`

> **Build requirement (important):** ShadowHook **2.0.1** ships an AAR whose metadata requires
> **`compileSdk ≥ 37`**, which in turn needs **AGP 9.x** (this template pins `agp = 9.4.0`,
> `compileSdk = 37`, Gradle `9.7.1`). If you downgrade ShadowHook to 2.0.0 you can drop back to
> AGP 8.7 / compileSdk 35. Mismatching these is the usual "template won't even build" cause.

Stable version **2.0.1** was rechecked on **2026-09-06** against both
[upstream releases](https://github.com/bytedance/android-inline-hook/releases/tag/v2.0.1) and
[Maven Central](https://repo.maven.apache.org/maven2/com/bytedance/android/shadowhook/maven-metadata.xml).
There is no newer stable artifact to upgrade to. Keep 2.0.1: it includes fixes for concurrent
`dlclose`/hook installation, shared-mode stack handling, and arm64 memory corruption, plus faster
symbol lookup and concurrent hook installation at different addresses. LSPatch carries its own
framework runtime; its version is independent of the module's ShadowHook dependency.

## Hook and patch responsibilities

The default native path initializes ShadowHook without installing a demonstration hook. The
optional `CodeFeature` array is empty. Adding native features requires validated addresses and a settings bridge; Java
registry toggles do not automatically apply/revert those native entries. The native-install toggle
gates initial setup and does not unhook an installed library. See the
[native workflow](NATIVE_MODDING_PLAYBOOK.md) for the scaffold's bounds and two-word patch limitations.

## Why ShadowHook here

ShadowHook is actively maintained and currently has better Android-version coverage than older inline hook libraries that have gone stale. Useful features for LSPosed modules:

- hooks by address or `library name + symbol name`
- pending hook tasks when a library is not loaded yet
- callback after a pending hook resolves
- per-hook mode selection
- operation recording for debugging
- linker `.init`/`.fini` callbacks
- symbol lookup helpers that can bypass common linker namespace limitations

## ABI limitations

ShadowHook supports:

- `arm64-v8a`
- `armeabi-v7a`

ShadowHook 2.0.1 declares Android 4.1 through Android 17 support. This template uses
`minSdk=28` (Android 9), matching Vector/LSPatch. The AAR's `compileSdk` requirement concerns
the host build and does not require Android 17 on the device. Keep the NDK r29 default
ELF alignment (16 KiB on arm64) and uncompressed native-library packaging for newer Android devices;
this also works on Android 9 devices with 4 KiB pages.

It does **not** support:

- `x86`
- `x86_64`
- Houdini-translated ARM code on x86 Android

The template packages ARM/ARM64 libraries. Turning off `ENABLE_NATIVE_SHADOWHOOK` does not produce
an x86 APK; a Java-only fork must remove the native build/dependency and ABI filters as well.
Use a matching ARM device or emulator for the template's native scaffold.

## Native scaffold in this template

`app/src/main/cpp/template_native.cpp` initializes the hook engine and exposes the JNI helpers.
`TARGET_LIB` is empty and `g_features` contains no entries, so initialization does not patch any
app code. There is no `getpid` smoke hook, callback or hit counter. Tests live under repository-root
`tests/` and run on the host; their code and ASan instrumentation are not linked into this library.

Use `library + symbol` for an available symbol or `shadowhook_hook_func_addr` for a validated runtime
address when adding real target behavior. Keep the ABI and lifecycle requirements in
[NATIVE_MODDING_PLAYBOOK.md](NATIVE_MODDING_PLAYBOOK.md).

## Hook modes

Recommended defaults:

- `SHADOWHOOK_HOOK_WITH_SHARED_MODE` for general hooks and compatibility.
- `SHADOWHOOK_HOOK_WITH_MULTI_MODE` for hot paths after you have tested recursion behavior.
- `SHADOWHOOK_HOOK_WITH_UNIQUE_MODE` only when you intentionally require exclusive ownership of a hook point.

Shared mode proxy functions must use `SHADOWHOOK_STACK_SCOPE()` or `SHADOWHOOK_POP_STACK()` before returning.

## Practical stability checklist

- Keep native hooks minimal.
- Confirm ABI before enabling native hooks.
- Log hook return values and `shadowhook_get_errno()` while developing.
- Export operation records from the overlay/Java bridge when debugging.
- Prefer `library + symbol` for stable exported symbols.
- Prefer Frida reconnaissance before committing a permanent native hook.
- If you must hook by RVA, resolve the module base from `/proc/self/maps` after the target
  library is loaded, then install `base + rva`. Keep this code app-specific.
- Do not install native hooks from `Application.attach` synchronously when the target library
  loads later in process startup. Use a short worker thread, a linker callback, or ShadowHook
  pending hooks and log the final install result.
- Gate every app-specific hook behind a runtime feature flag. A module-level disable belongs
  in LSPosed/Vector; in-app switches should enable or disable individual behaviors.
- Avoid hooking extremely hot libc/pthread functions in production unless required and thoroughly profiled.
- Disable verbose ShadowHook logs in release builds.

## Detection hygiene for authorized testing

This template avoids obsolete hook libraries and keeps module scope explicit. For legitimate research, also consider:

- keep package/module names project-specific instead of obvious keywords
- reduce debug logs in release builds
- avoid broad global hooks
- keep hooks fail-closed and reversible
- avoid shipping unused native libraries
- document what you changed and why

Do not use this template to hide malicious behavior, bypass third-party protections outside an authorized lab, or violate app/platform terms.
