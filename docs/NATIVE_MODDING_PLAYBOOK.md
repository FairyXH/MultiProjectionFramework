# Native analysis and instrumentation workflow

Use this workflow for native Android apps you own or are authorized to test. The template provides
an ARM64 analysis and patch scaffold; it ships no target-specific offsets or enabled code features.
For managed engines, see [IL2CPP](ENGINE_IL2CPP.md) and [Godot/Lua/Cocos](ENGINE_GODOT_LUA_COCOS.md).

## 1. Identify the exact binary and address space

Keep the original base APK and required splits from one app version. Extract the native library
for the process ABI: a library may live in an ABI split rather than the base APK.

```sh
export RE_SO="$PWD/artifacts/target/lib/arm64-v8a/libtarget.so"
```

The host tools support **little-endian ELF64 AArch64 executables/shared libraries**. Their address
arguments are ELF virtual addresses, before the process load bias. They do not treat file offsets
as addresses. For example, `strings -t x "$RE_SO"` reports **file offsets**; convert a result before
passing it to the xref scanner:

```sh
python3 tools/deep.py va 0x<file-offset>
python3 tools/find_str_xref.py 0x<elf-virtual-address>
```

`deep.py off` converts the other way. `deep.py dis` requires Capstone; the other commands use only
Python's standard library. See [tools/README.md](../tools/README.md) for installation and command syntax.

For a zero-based Android shared object, `runtime_address = dlpi_addr + elf_va`.
`dl_iterate_phdr` supplies that load bias. A `/proc/<pid>/maps` executable mapping start may include
an ELF segment offset or an APK file offset; it is not interchangeable with the load bias.
These distinctions follow the [ELF program-header model](https://refspecs.linuxfoundation.org/elf/gabi4+/ch5.pheader.html).

## 2. Treat names and patterns as candidates

Use exports, strings, RTTI and disassembly to identify a candidate function. An app update can
change or remove any of these anchors. Record the app version, ELF build ID, ABI, expected bytes,
callers and function signature with each result.

- `find_str_xref.py` scans executable LOAD segments for nearby ADRP+ADD/LDR pairs. Its forward
  window and function-prologue search are heuristics; verify the result in disassembly.
- `fstart.py` stays within the containing executable segment and searches at most 4 KiB backward.
  When no recognizable prologue is found, it returns the supplied address as a candidate.
- `deep.py vtbl`, `vtbl.py` and `rela.py slot` share one parser for ordinary `.rela.dyn`
  `R_AARCH64_RELATIVE` addends. They report missing relocations explicitly; they do not decode
  RELR, Android packed relocations, or symbol-based relocations.
- `deep.py u64` reads raw file-backed bytes. A zero word can still have a relocation; use the
  relocation commands for vtable slots instead of interpreting that zero as the runtime pointer.

Do not assume that a recognizable name or opcode proves ownership of the function. Confirm its
calling convention, argument types, side effects and lifetime before invoking or changing it.

## 3. Integrate a validated candidate

`app/src/main/cpp/template_native.cpp` contains optional building blocks:

- `resolve_by_string_xref` and `resolve_rtti_vtable` locate candidates. For a simple unqualified
  class name, the RTTI length prefix is its byte length: `resolve_rtti_vtable(info, "10CSomeClass")`
  uses `10 = strlen("CSomeClass")`. Nested names use the corresponding Itanium encoding.
- `find_module_info` obtains load bias and segment bounds from program headers. Raw C++ scanners
  require readable memory that remains mapped for the duration of the scan; aggregate segment
  bounds do not guarantee that gaps between segments are mapped.
- `patch_instruction` compares one expected instruction before writing. Its input address must
  already be valid and aligned. `write_code` by itself does not validate that it is the intended
  function; a successful `mprotect` is not such validation.
- `CodeFeature` captures original words and `feature_apply` writes replacements or captured
  originals. The resolver must supply a validated address. Two-word changes are separate writes,
  so they are not atomic and a second-write failure can leave a partial change.

The template's `g_features` array is empty and `TARGET_LIB` is unset. To add a feature, provide a
validated resolver, extend that array, select the target library, and implement the settings bridge
that calls `feature_apply`. The overlay's Java feature registry is **not automatically connected**
to native `CodeFeature` entries. The existing native toggle gates initial installation; turning it
off after installation does not unhook or revert native code. Restart the target to reset installed
scaffolding. Keep hot reload disabled until the module has explicit teardown.

Choose a hook when you need to inspect arguments, call the original, or change a return value.
Choose a byte edit only when you have established its complete behavior and can manage concurrent
execution. Both change process state and can be observed by integrity checks; neither provides a
universal compatibility or invisibility guarantee. See [ShadowHook notes](SHADOWHOOK_NOTES.md).

## 4. Verify in the target process

Build the native helper with the API-28 NDK compiler if using `tools/memtool.c`; its root-only command
syntax is in [tools/README.md](../tools/README.md). Keep probes confined to your test process.
A write through `/proc/<pid>/mem` is not by itself proof that a running CPU has observed a changed
instruction stream; check instruction-cache synchronization as part of a code-patch design.

Before enabling a feature by default, verify startup, installation, at least one invocation,
disable/re-enable behavior, process restarts, and an app version where the resolver should refuse
to apply. Read back changed bytes and check crash/tombstone output. Run on each supported ABI and
Android version; host builds and static matches cannot establish runtime compatibility.

Preserve valid pointer tags when required by the target ABI. Blindly stripping the top byte can
break [MTE's allocation-tag checks](https://source.android.com/docs/security/test/memory-safety/arm-mte).
Keep target-specific evidence in an ignored `artifacts/` directory or the appropriate app branch,
with enough detail to repeat the test.
