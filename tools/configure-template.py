#!/usr/bin/env python3
"""Configure a module after validating all inputs and preparing the complete text edit plan."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
NATIVE_LIB_RE = re.compile(r"[a-z][a-z0-9_]*")
JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue
 default do double else enum extends final finally float for goto if implements import instanceof
 int interface long native new package private protected public return short static strictfp super
 switch synchronized this throw throws transient try void volatile while true false null _""".split())


def validate_package(value: str, *, java: bool = False) -> str:
    if not PACKAGE_RE.fullmatch(value) or (java and any(p in JAVA_KEYWORDS for p in value.split("."))):
        raise ValueError(f"Invalid {'Java ' if java else ''}package name: {value}")
    return value


def single_line(value: str, option: str) -> str:
    if not value.strip() or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError(f"{option} must be nonempty text without control characters")
    return value


def normalized_targets(raw: list[str]) -> list[str]:
    targets = list(dict.fromkeys(v.strip() for item in raw for v in item.split(",") if v.strip()))
    if not targets:
        raise ValueError("At least one --target package is required")
    return [validate_package(value) for value in targets]


def android_string(value: str) -> str:
    # Android string escaping happens after XML parsing; both layers must be escaped.
    value = value.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    return escape(value.replace("@", "\\@").replace("?", "\\?"))


def replace_once(pattern: str, replacement: str, text: str, label: str, flags: int = 0) -> str:
    updated, count = re.subn(pattern, lambda _: replacement, text, flags=flags)
    if count != 1:
        raise ValueError(f"Expected exactly one {label}; found {count}")
    return updated


def configure(args: argparse.Namespace) -> str:
    new_package = validate_package(args.package, java=True)
    name = single_line(args.name, "--name")
    author = single_line(args.author, "--author")
    targets = normalized_targets(args.target)
    if args.native_lib is not None and not NATIVE_LIB_RE.fullmatch(args.native_lib):
        raise ValueError("Invalid --native-lib: use lowercase letters, digits and underscores")

    gradle = ROOT / "app/build.gradle.kts"
    gradle_text = gradle.read_text(encoding="utf-8")
    namespace = re.search(r'namespace\s*=\s*"([^"]+)"', gradle_text)
    if namespace is None:
        raise ValueError("Cannot find the current Android namespace")
    old_package = validate_package(args.old_package or namespace[1], java=True)
    if namespace[1] != old_package:
        raise ValueError("--old-package does not match the current Android namespace")
    java_root = ROOT / "app/src/main/java"
    old_dir = java_root / old_package.replace(".", "/")
    new_dir = java_root / new_package.replace(".", "/")
    config = old_dir / "TemplateConfig.java"
    config_text = config.read_text(encoding="utf-8")
    native_match = re.search(r'NATIVE_LIBRARY_NAME\s*=\s*"([^"]+)"', config_text)
    if native_match is None or not NATIVE_LIB_RE.fullmatch(native_match[1]):
        raise ValueError("Cannot find the current native library name in TemplateConfig")
    old_native = native_match[1]
    native = args.native_lib or old_native

    def package_text(text: str) -> str:
        return (text.replace(old_package, new_package)
                    .replace(old_package.replace(".", "/"), new_package.replace(".", "/")))

    # Restrict edits to template sources/metadata/docs. Never rewrite this tool, caches, or artifacts.
    paths = [gradle, ROOT / "app/proguard-rules.pro", ROOT / "app/src/main/AndroidManifest.xml"]
    paths += list((ROOT / "app/src/main/cpp").glob("*.cpp"))
    paths += list((ROOT / "app/src/main/cpp").glob("*.h"))
    paths += list((ROOT / "app/src/main/resources/META-INF/xposed").glob("*"))
    paths += [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
    sources = sorted(old_dir.rglob("*.java"))
    if not sources:
        raise ValueError(f"No Java sources found for {old_package}")
    edits: dict[Path, str] = {}
    moves: dict[Path, Path] = {}

    def plan(path: Path, text: str) -> None:
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError(f"Refusing to edit a path outside the template: {path}")
        edits[path] = text

    for path in paths + sources:
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError(f"Refusing to follow a template source symlink: {path}")
        text = package_text(path.read_text(encoding="utf-8"))
        text = text.replace(f"lib{old_native}.so", f"lib{native}.so")
        destination = new_dir / path.relative_to(old_dir) if path in sources else path
        # Existing unrelated files must not be overwritten during a package move.
        if destination != path:
            if destination.exists():
                raise ValueError(f"Destination source already exists: {destination}")
            moves[path] = destination
        plan(destination, text)

    new_config = moves.get(config, config)
    text = edits[new_config]
    target_block = ",\n".join(f'            "{target}"' for target in targets)
    text = replace_once(r"public static final String\[\] TARGET_PACKAGES = \{.*?\n    \};",
                        "public static final String[] TARGET_PACKAGES = {\n" + target_block + "\n    };",
                        text, "TARGET_PACKAGES block", re.S)
    text = replace_once(r'public static final String MENU_TITLE = "(?:\\.|[^"\\])*";',
                        "public static final String MENU_TITLE = " + json.dumps(name, ensure_ascii=False) + ";",
                        text, "MENU_TITLE")
    text = replace_once(r'public static final String NATIVE_LIBRARY_NAME = "[^"]+";',
                        f'public static final String NATIVE_LIBRARY_NAME = "{native}";', text, "native library constant")
    plan(new_config, text)

    cmake = ROOT / "app/src/main/cpp/CMakeLists.txt"
    cmake_text = cmake.read_text(encoding="utf-8")
    if not re.search(rf"add_library\(\s*{re.escape(old_native)}\s+SHARED", cmake_text):
        raise ValueError("CMake target and NATIVE_LIBRARY_NAME disagree; resolve this before configuring")
    plan(cmake, re.sub(rf"\b{re.escape(old_native)}\b(?!\.cpp)", native, cmake_text))

    metadata = ROOT / "app/src/main/resources/META-INF/xposed"
    plan(metadata / "scope.list", "\n".join(targets) + "\n")
    prop = metadata / "module.prop"
    text = edits[prop]
    for key, value in (("id", new_package), ("name", name), ("author", author)):
        # module.prop uses Java-properties escaping, independently of Java/XML source escaping.
        value = value.replace("\\", "\\\\")
        text = replace_once(rf"^{key}=.*$", f"{key}={value}", text, f"module.prop {key}", re.M)
    plan(prop, text)

    strings = ROOT / "app/src/main/res/values/strings.xml"
    text = strings.read_text(encoding="utf-8")
    text = replace_once(r'<string name="app_name"[^>]*>.*?</string>',
                        f'<string name="app_name" formatted="false">{android_string(name)}</string>',
                        text, "app_name", re.S)
    ET.fromstring(text)
    plan(strings, text)

    settings = ROOT / "settings.gradle.kts"
    # Gradle project IDs reject quotes, slashes and other valid display-name characters.
    # Keep the internal build identifier independent of the user-visible Android name.
    text = replace_once(r'rootProject\.name\s*=\s*"(?:\\.|[^"\\])*"',
                        "rootProject.name = " + json.dumps(new_package),
                        settings.read_text(encoding="utf-8"), "project name")
    plan(settings, text)

    # All input, source-layout and destination checks above finish before the first write.
    for path, text in edits.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            path.write_text(text, encoding="utf-8")
    for source in moves:
        source.unlink()
    for source in moves:
        parent = source.parent
        while parent != java_root:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
    return native


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, help="New Java namespace/applicationId")
    parser.add_argument("--name", required=True, help="Literal module/app display name")
    parser.add_argument("--target", required=True, action="append", help="Target package; repeat or comma-separate")
    parser.add_argument("--author", default="YourName")
    parser.add_argument("--old-package", help="Current namespace; defaults to app/build.gradle.kts")
    parser.add_argument("--native-lib", help="New native library short name; defaults to the current name")
    args = parser.parse_args()
    try:
        native = configure(args)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.error(str(error))
    print(f"Configured {args.package}: {args.name}\nNative library: lib{native}.so")


if __name__ == "__main__":
    main()
