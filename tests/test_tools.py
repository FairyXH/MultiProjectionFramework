"""Public CLI regressions, using a tiny ARM64 ELF whose VAs differ from file offsets."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(os.environ.get("TEMPLATE_TEST_ROOT", Path(__file__).resolve().parents[1]))


def snapshot(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob("*") if p.is_file() and "__pycache__" not in p.parts}


class ConfigureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "template"
        shutil.copytree(ROOT, self.root, ignore=shutil.ignore_patterns(
            ".git", ".gradle", "build", ".cxx", "artifacts", "__pycache__", "tests"))

    def configure(self, *extra, name="Review Module"):
        return subprocess.run([sys.executable, str(self.root / "tools/configure-template.py"),
                               "--package", "com.review.module", "--name", name,
                               "--target", "com.example.target", *extra],
                              capture_output=True, text=True)

    def test_invalid_native_name_changes_nothing(self):
        before = snapshot(self.root)
        result = self.configure("--native-lib", "INVALID-NAME")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(snapshot(self.root), before)

    def test_wrong_old_package_changes_nothing(self):
        before = snapshot(self.root)
        result = self.configure("--old-package", "com.missing.package")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(snapshot(self.root), before)

    def test_keyword_package_is_rejected_before_edits(self):
        before = snapshot(self.root)
        result = self.configure("--package", "com.class.module")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(snapshot(self.root), before)

    def test_metadata_line_break_is_rejected_before_edits(self):
        before = snapshot(self.root)
        result = self.configure("--author", "Author\nminApiVersion=1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(snapshot(self.root), before)

    def test_quoted_name_generates_valid_xml_and_java(self):
        result = self.configure(name='Lab "A&B"')
        self.assertEqual(result.returncode, 0, result.stderr)
        ET.parse(self.root / "app/src/main/res/values/strings.xml")
        config = (self.root / "app/src/main/java/com/review/module/TemplateConfig.java").read_text()
        self.assertIn('MENU_TITLE = "Lab \\"A&B\\"";', config)

    def test_configuration_does_not_rewrite_tools_or_artifacts(self):
        artifact = self.root / "artifacts/notes.txt"
        artifact.parent.mkdir(exist_ok=True)
        artifact.write_text("com.template.lsposed YourName LSPosed Universal Template")
        before = snapshot(self.root / "tools")
        self.assertEqual(self.configure().returncode, 0)
        self.assertEqual(snapshot(self.root / "tools"), before)
        self.assertEqual(artifact.read_text(), "com.template.lsposed YourName LSPosed Universal Template")

    def test_display_punctuation_does_not_make_invalid_gradle_project_id(self):
        result = self.configure(name='Lab "A&B"')
        self.assertEqual(result.returncode, 0, result.stderr)
        text = (self.root / "settings.gradle.kts").read_text()
        literal = re.search(r'rootProject.name\s*=\s*("(?:\\.|[^"\\])*")', text)[1]
        identifier = json.loads(literal)
        self.assertFalse(any(c in identifier for c in '/\\:<>"?*|'))

    def test_second_native_rename_keeps_cmake_and_java_in_sync(self):
        self.assertEqual(self.configure("--native-lib", "first_native").returncode, 0)
        result = self.configure("--old-package", "com.review.module", "--native-lib", "second_native")
        self.assertEqual(result.returncode, 0, result.stderr)
        cmake = (self.root / "app/src/main/cpp/CMakeLists.txt").read_text()
        config = (self.root / "app/src/main/java/com/review/module/TemplateConfig.java").read_text()
        self.assertIn("add_library(second_native SHARED", cmake)
        self.assertNotIn("first_native", cmake)
        self.assertIn('NATIVE_LIBRARY_NAME = "second_native";', config)
        self.assertIn("template_native.cpp", cmake)

    def test_target_list_and_jni_names_are_consistent(self):
        result = self.configure("--target", "com.second.app,com.example.target", "--native-lib", "review_native")
        self.assertEqual(result.returncode, 0, result.stderr)
        meta = self.root / "app/src/main/resources/META-INF/xposed"
        self.assertEqual((meta / "scope.list").read_text(), "com.example.target\ncom.second.app\n")
        self.assertEqual((meta / "java_init.list").read_text().strip(), "com.review.module.ModuleEntry")
        native = (self.root / "app/src/main/cpp/template_native.cpp").read_text()
        self.assertIn('FindClass("com/review/module/NativeBridge")', native)
        self.assertFalse((self.root / "app/src/main/java/com/template/lsposed/ModuleEntry.java").exists())


def elf_fixture(path, *, sections=True, machine=183):
    data = bytearray(0x700)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16,
                     3, machine, 1, 0, 0x40, 0x500 if sections else 0, 0,
                     64, 56, 2, 64, 5 if sections else 0, 1 if sections else 0)
    struct.pack_into("<IIQQQQQQ", data, 0x40, 1, 5, 0x100, 0x8000, 0, 0x20, 0x20, 0x100)
    struct.pack_into("<IIQQQQQQ", data, 0x78, 1, 6, 0x200, 0xA000, 0, 0x40, 0x80, 0x100)
    # STP X29,X30,[SP,#-16]!; NOP; ADRP X0,0xA000; ADD X0,X0,#16; RET.
    struct.pack_into("<8I", data, 0x100, 0xA9BF7BFD, 0xD503201F, 0xD0000000,
                     0x91004000, 0xD65F03C0, 0, 0, 0)
    data[0x210:0x21E] = b"FixtureString\0"
    names = b"\0.shstrtab\0.text\0.data\0.rela.dyn\0"
    data[0x300:0x300 + len(names)] = names
    struct.pack_into("<QQq", data, 0x400, 0xA000, 1027, 0x8010)
    if sections:
        for i, (name, kind, flags, va, off, size, entsize) in enumerate([
            (".shstrtab", 3, 0, 0, 0x300, len(names), 0),
            (".text", 1, 6, 0x8000, 0x100, 0x20, 0),
            (".data", 1, 3, 0xA000, 0x200, 0x40, 0),
            (".rela.dyn", 4, 0, 0, 0x400, 24, 24),
        ], 1):
            struct.pack_into("<IIQQQQIIQQ", data, 0x500 + i * 64,
                             names.index(name.encode()), kind, flags, va, off, size, 0, 0, 8, entsize)
    path.write_bytes(data)


class ElfToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.elf = Path(self.temp.name) / "fixture.so"
        elf_fixture(self.elf)

    def run_tool(self, name, *args):
        env = os.environ.copy()
        env["RE_SO"] = str(self.elf)
        return subprocess.run([sys.executable, str(ROOT / "tools" / name), *args],
                              capture_output=True, text=True, env=env, timeout=10)

    def test_xrefs_use_virtual_addresses_and_executable_bounds(self):
        result = self.run_tool("find_str_xref.py", "0xA010")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("adrp@0x8008", result.stdout)
        self.assertIn("func~0x8000", result.stdout)

    def test_function_start_uses_va_to_file_mapping(self):
        result = self.run_tool("fstart.py", "0x8010")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("0x8000", result.stdout)

    def test_vtable_commands_agree_on_relative_relocations(self):
        for tool, args in [("vtbl.py", ("0xA000", "1")), ("rela.py", ("slot", "0xA000", "1")),
                           ("deep.py", ("vtbl", "0xA000", "1"))]:
            with self.subTest(tool=tool):
                result = self.run_tool(tool, *args)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("0x8010", result.stdout)

    def test_missing_relocations_are_reported_without_traceback(self):
        elf_fixture(self.elf, sections=False)
        result = self.run_tool("rela.py", "xref", "0x8010")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("0 reloc", result.stdout)

    def test_incomplete_pointer_read_does_not_cross_into_bss(self):
        result = self.run_tool("deep.py", "u64", "0xA03F")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("file-backed", result.stderr)

    def test_wrong_architecture_is_rejected(self):
        elf_fixture(self.elf, machine=62)
        result = self.run_tool("rela.py", "xref", "0x8010")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AArch64", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_truncated_elf_reports_actionable_error(self):
        self.elf.write_bytes(b"\x7fELF\x02\x01\x01")
        result = self.run_tool("vtbl.py", "0xA000", "1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("ELF", result.stderr)


if __name__ == "__main__":
    unittest.main()
