#!/usr/bin/env python3
"""Inspect ordinary .rela.dyn R_AARCH64_RELATIVE addends using ELF virtual addresses."""
import argparse
from elf_utils import cli, image_from_env, positive_count


def print_slots(image, address, count):
    for index in range(count):
        slot = address + index * 8
        value = image.relocations.get(slot)
        rendered = f"0x{value:X}" if value is not None else "(no RELATIVE relocation)"
        print(f"[+0x{index * 8:02X}] idx {index:2d} slot=0x{slot:X} -> {rendered}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("slot", "xref", "read"))
    parser.add_argument("address", type=lambda value: int(value, 16))
    parser.add_argument("count", type=positive_count, nargs="?", default=16)
    args = parser.parse_args()
    image = image_from_env()
    if args.command == "xref":
        hits = [slot for slot, addend in image.relocations.items() if addend == args.address]
        print(f"addend 0x{args.address:X} referenced by {len(hits)} reloc offsets:")
        for slot in hits:
            print(f"  at 0x{slot:X}")
    else:
        print_slots(image, args.address, 1 if args.command == "read" else args.count)


if __name__ == "__main__":
    cli(main)
