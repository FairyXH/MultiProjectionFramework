# Template tools

Host configuration, download and analysis tools for authorized Android testing.
`memtool.c` is a separate root-only device tool.

## LSPatch download (host)

From the repository root:

```sh
LSPATCH_JAR="$(./tools/download-lspatch.sh)"
java -jar "$LSPATCH_JAR" --help
```

The downloader pins LSPatch **v1.2 build 487**, verifies SHA-256 before publishing the download,
and re-verifies cached JARs. It prints only the absolute JAR path to stdout; diagnostics go to
stderr. Downloads stay in ignored `artifacts/lspatch/`. Use `--debug` for the matching debug JAR,
or pass an output directory after the optional flag. A checksum mismatch stops with an error;
remove the corrupt cached file explicitly before retrying.

For an upgrade, review the official stable release, update the asset pins and hashes in
`download-lspatch.sh` together with `docs/LSPATCH_NONROOT.md`, and run the guide against an owned
test APK. The complete embedded-module, signing and SAI workflow is in
[`docs/LSPATCH_NONROOT.md`](../docs/LSPATCH_NONROOT.md).

## Configure a module

Run `python3 tools/configure-template.py --help` from the repository root. The script edits module
sources, metadata and package references in documentation; it does not rewrite itself, other tools,
tests or generated artifacts. Invalid options and conflicting source destinations are rejected
before any writes. An I/O failure during the write phase can still require restoring your checkout.

`--name` and `--author` must be nonempty single-line text. Names are escaped separately for Java,
Android XML and module metadata; Gradle's internal project name uses the Java package, so display
punctuation does not make an invalid build identifier. On subsequent runs, the current namespace
and native library name are detected from the source. `--old-package` explicitly checks the expected namespace;
`--native-lib` renames the CMake target and Java library constant together. Keep the implementation
file named `template_native.cpp`.

## Static analysis (host)

Requires Python 3.10+. **Only disassembly requires Capstone**:

```sh
python3 -m pip install capstone
export RE_SO="/path/to/lib/arm64-v8a/libtarget.so"
```

All commands below are run as `python3 tools/<script> ...`. Supported inputs are little-endian
ELF64 AArch64 executables/shared libraries. Addresses are **ELF virtual addresses**, before load
bias; `strings -t x` reports file offsets and must be converted with `deep.py va` first.

| Command | Result |
|---|---|
| `deep.py dis 0x<va> [count]` | Bounded ARM64 disassembly with ADRP/address annotations |
| `deep.py vtbl 0x<va> [count]` / `vtbl.py 0x<va> [count]` | Vtable slots from ordinary `.rela.dyn` RELATIVE addends |
| `deep.py u64 0x<va>` | Raw 8-byte file-backed value; does not apply relocations |
| `deep.py off 0x<va>` / `deep.py va 0x<offset>` | VA → file offset / file offset → VA |
| `find_str_xref.py 0x<string-va>` | ADRP+ADD/LDR candidates in executable LOAD segments |
| `fstart.py 0x<instruction-va>` | Prologue candidate within the executable segment and a 4 KiB backward bound |
| `rela.py xref 0x<function-va>` | RELATIVE relocation slots whose addend matches the address |
| `rela.py slot 0x<slot-va> [count]` / `rela.py read 0x<slot-va>` | Multiple/single RELATIVE relocation entries |

The tools share `elf_utils.py`; file-backed bounds are checked for the entire read. Counts must
be 1–4096. Relocation commands do not decode RELR, Android packed or symbol-based relocations.
An absent RELATIVE entry is reported explicitly and does not prove that the runtime pointer is zero.
Xrefs/prologues are heuristics: verify candidates against disassembly and the target ABI.

## Live memory (device, root) — `memtool.c`

Build once with the NDK and push:
```
$NDK/.../aarch64-linux-android28-clang -O2 memtool.c -o memtool
adb push memtool /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/memtool
```
Run as root: `adb shell su -c "/data/local/tmp/memtool <cmd> <pid> …"`.

| command | purpose |
|---|---|
| `read <pid> <addr> <len>` · `ptrs <pid> <addr> <n>` | hexdump / dump qwords |
| `w32/w64/write <pid> <addr> <hex>` | poke a word / qword / raw bytes |
| `scan <pid> <hexval64> [max]` · `scanr … <lo> <hi>` · `scan32 <pid> <hexval32>` | value scans (find objects/vtables) |
| `sscan <pid> <string>` · `scanm <pid> <val> <mask>` | string / masked scans |
| `snap <pid> <file>` + `hunt <pid> <snap> <old> <new>` | cheat-engine old→new diff |
| `modiff <pid> <vtableAddr> <sec>` | **decrement detector**: snapshot every `MonitoredObfuscated`-style guard, play, report which value dropped (finds *the* counter without knowing its value) |
| `freeze <pid> <addr> <floor> <iters> <us>` | hold a plain-int value |
| `mofreeze <pid> <addr> <val> <iters> <us>` | hold an obfuscated `shadow0^shadow1` value (keeps shadows consistent) |

**Live-test a byte-patch before shipping:** write the instruction to `base+rva` over `/proc/pid/mem`
(COW copies the page; the game keeps running), watch the effect, then bake it into a `CodeFeature`.
Use the library's ELF load bias plus a validated ELF VA. An APK executable mapping start is
not necessarily the load bias; do not copy a mapping offset from another target.

See `../docs/NATIVE_MODDING_PLAYBOOK.md` for the end-to-end workflow.
