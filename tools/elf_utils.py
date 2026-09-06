"""Shared, bounded ELF64/AArch64 readers for the host analysis commands.

Addresses accepted by these helpers are ELF virtual addresses, before the runtime load bias.
Only ordinary .rela.dyn R_AARCH64_RELATIVE relocations are decoded (not RELR or Android packed RELA).
"""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import struct
import sys


class ElfError(ValueError):
    pass


@dataclass(frozen=True)
class Segment:
    va: int
    offset: int
    filesz: int
    memsz: int
    flags: int

    def contains(self, va: int, size: int = 1) -> bool:
        return size >= 0 and self.va <= va and va + size <= self.va + self.filesz


class ElfImage:
    def __init__(self, path: str | Path):
        self.data = Path(path).read_bytes()
        if len(self.data) < 64 or self.data[:7] != b"\x7fELF\x02\x01\x01":
            raise ElfError("Expected a complete little-endian ELF64 header")
        header = self.unpack("<HHIQQQIHHHHHH", 16)
        kind, machine, version, _, phoff, shoff, _, ehsize, phentsz, phnum, shentsz, shnum, shstr = header
        if machine != 183 or kind not in (2, 3) or version != 1:
            raise ElfError("Expected an AArch64 ELF executable or shared library")
        if ehsize < 64 or phentsz < 56:
            raise ElfError("Invalid ELF/program-header size")
        self.sections = {}
        if shoff:
            if shentsz < 64:
                raise ElfError("Invalid ELF section-header size")
            first = self.unpack("<IIQQQQIIQQ", shoff)
            shnum = shnum or first[5]
            shstr = first[6] if shstr == 0xFFFF else shstr
            phnum = first[7] if phnum == 0xFFFF else phnum
            self.check_span(shoff, shentsz * shnum)
            if shstr >= shnum:
                raise ElfError("Invalid ELF section-name table index")
            headers = [self.unpack("<IIQQQQIIQQ", shoff + i * shentsz) for i in range(shnum)]
            names = self.span(headers[shstr][4], headers[shstr][5]) if shstr else b"\0"
            for h in headers:
                end = names.find(b"\0", h[0])
                if end < 0:
                    raise ElfError("Unterminated ELF section name")
                name = names[h[0]:end].decode("utf-8", errors="replace")
                self.sections[name] = h
        elif shnum or phnum == 0xFFFF:
            raise ElfError("Missing ELF section headers")

        self.check_span(phoff, phentsz * phnum)
        self.segments = []
        for i in range(phnum):
            ptype, flags, off, va, _, filesz, memsz, _ = self.unpack("<IIQQQQQQ", phoff + i * phentsz)
            if ptype != 1:
                continue
            if filesz > memsz:
                raise ElfError("ELF LOAD filesz exceeds memsz")
            self.check_span(off, filesz)
            self.segments.append(Segment(va, off, filesz, memsz, flags))
        if not self.segments:
            raise ElfError("ELF contains no LOAD segments")
        self.segments.sort(key=lambda segment: segment.va)
        self.relocations = self._relative_relocations()

    def check_span(self, offset: int, size: int) -> None:
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise ElfError(f"Truncated ELF range at file offset 0x{offset:X}, size 0x{size:X}")

    def span(self, offset: int, size: int) -> bytes:
        self.check_span(offset, size)
        return self.data[offset:offset + size]

    def unpack(self, fmt: str, offset: int):
        self.check_span(offset, struct.calcsize(fmt))
        return struct.unpack_from(fmt, self.data, offset)

    def segment_at(self, va: int, size: int = 1, *, executable: bool = False):
        return next((s for s in self.segments if s.contains(va, size) and (not executable or s.flags & 1)), None)

    def va_to_off(self, va: int, size: int = 1):
        segment = self.segment_at(va, size)
        return None if segment is None else segment.offset + va - segment.va

    def off_to_va(self, offset: int):
        return next((s.va + offset - s.offset for s in self.segments
                     if s.offset <= offset < s.offset + s.filesz), None)

    def read_int(self, va: int, size: int):
        offset = self.va_to_off(va, size)
        if offset is None:
            return None
        return int.from_bytes(self.span(offset, size), "little")

    def read_string(self, va: int, maxlen: int = 80):
        segment = self.segment_at(va)
        if segment is None:
            return None
        size = min(maxlen, segment.va + segment.filesz - va)
        value = self.span(segment.offset + va - segment.va, size).split(b"\0", 1)[0]
        return value.decode("utf-8", errors="replace")

    def _relative_relocations(self):
        section = self.sections.get(".rela.dyn")
        if section is None:
            return {}
        _, kind, _, _, offset, size, _, _, _, entsize = section
        if kind != 4 or entsize != 24 or size % 24:
            raise ElfError("Invalid ELF .rela.dyn layout")
        self.check_span(offset, size)
        return {va: addend for va, info, addend in struct.iter_unpack("<QQq", memoryview(self.data)[offset:offset + size])
                if info & 0xFFFFFFFF == 1027}

    def function_start(self, va: int, max_back: int = 0x1000):
        segment = self.segment_at(va, 4, executable=True)
        if va % 4 or segment is None:
            raise ElfError(f"0x{va:X} is not a file-backed, aligned executable address")
        lower = max(segment.va, va - max_back)
        for address in range(va, lower - 1, -4):
            word = self.read_int(address, 4)
            if is_prologue(word):
                previous = address - 4
                if previous >= lower and self.read_int(previous, 4) in (0xD503233F, 0xD503237F):
                    return previous
                return address
        return va  # heuristic only; absence of a prologue is not proof of a function boundary

    def string_xrefs(self, target: int):
        hits = []
        for segment in self.segments:
            if not segment.flags & 1:
                continue
            end = segment.va + segment.filesz
            start = (segment.va + 3) & ~3
            for pc in range(start, end - 3, 4):
                word = self.read_int(pc, 4)
                if word & 0x9F000000 != 0x90000000:
                    continue
                register = word & 31
                if register == 31:
                    continue
                imm = ((word >> 5) & 0x7FFFF) * 4 + ((word >> 29) & 3)
                if imm & (1 << 20):
                    imm -= 1 << 21
                page = (pc & ~0xFFF) + imm * 4096
                for address in range(pc + 4, min(end - 3, pc + 32), 4):
                    candidate = self.read_int(address, 4)
                    resolved = None
                    if (candidate >> 5) & 31 == register:
                        imm12 = (candidate >> 10) & 0xFFF
                        if candidate & 0xFF800000 == 0x91000000:
                            resolved = page + (imm12 << (12 if candidate & (1 << 22) else 0))
                        else:
                            scale = {0x39400000: 1, 0xB9400000: 4, 0xF9400000: 8}.get(candidate & 0xFFC00000)
                            if scale:
                                resolved = page + imm12 * scale
                    if resolved == target:
                        hits.append(pc)
                        break
                    # Conservative window: do not carry the page through a register overwrite or branch.
                    if (candidate & 31 == register or candidate & 0x7C000000 == 0x14000000
                            or candidate & 0xFE000000 == 0xD6000000
                            or candidate & 0x7E000000 in (0x34000000, 0x36000000)
                            or candidate & 0xFF000010 == 0x54000000):
                        break
        return hits


def is_prologue(word: int) -> bool:
    return (word in (0xD503233F, 0xD503237F)
            or word & 0xFFC07FFF in (0xA9807BFD, 0xA9007BFD)
            or word & 0xFF8003FF == 0xD10003FF)


def image_from_env() -> ElfImage:
    path = os.environ.get("RE_SO")
    if not path:
        raise ElfError("Set RE_SO to the AArch64 ELF file to inspect")
    return ElfImage(path)


def positive_count(value: str) -> int:
    count = int(value)
    if not 1 <= count <= 4096:
        raise ValueError("Count must be between 1 and 4096")
    return count


def cli(main) -> None:
    try:
        main()
    except (ValueError, OSError) as error:
        sys.exit(f"error: {error}")
