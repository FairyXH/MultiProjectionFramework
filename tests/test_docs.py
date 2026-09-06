"""Compile the documented native resolver against the APIs the template actually exports."""
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import unittest

ROOT = Path(os.environ.get("TEMPLATE_TEST_ROOT", Path(__file__).resolve().parents[1]))


class DocumentationTests(unittest.TestCase):
    def test_resolver_example_links_against_native_utils(self):
        sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get(
            "ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk"))))
        host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
        compiler = sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt" / host / "bin/aarch64-linux-android28-clang++"
        if not compiler.exists():
            self.skipTest("Android NDK r29 is required to link the documentation example")
        doc = (ROOT / "docs/ENGINE_GODOT_LUA_COCOS.md").read_text()
        code = next(block for block in re.findall(r"```cpp\n(.*?)```", doc, re.S)
                    if "inline void* resolve(" in block)
        version_code = next(block for block in re.findall(r"```cpp\n(.*?)```", doc, re.S)
                            if "// Godot embeds its version string" in block)
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "documented-resolver.cpp"
            source.write_text(code + '\nvoid exercise_version_marker() {\n' + version_code + '\n}\nextern "C" void* exercise(const char* a,const char* b,const char* c) { return resolve(a,b,c); }\n')
            result = subprocess.run([str(compiler), "-std=c++20", "-shared", "-fPIC", "-Wl,--no-undefined",
                                     "-I", str(ROOT / "app/src/main/cpp"), str(source),
                                     str(ROOT / "app/src/main/cpp/native_utils.cpp"), "-llog", "-ldl",
                                     "-o", str(Path(temp) / "libdocumented-resolver.so")],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_rtti_example_length_matches_class_name(self):
        doc = (ROOT / "docs/NATIVE_MODDING_PLAYBOOK.md").read_text()
        match = re.search(r'resolve_rtti_vtable\(info, "(\d+)(CSomeClass)"\)', doc)
        self.assertIsNotNone(match)
        self.assertEqual(int(match[1]), len(match[2]))


if __name__ == "__main__":
    unittest.main()
