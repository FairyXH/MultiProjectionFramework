#!/usr/bin/env python3
"""Find ARM64 ADRP+ADD/LDR candidates within executable ELF LOAD segments."""
import argparse
from elf_utils import cli, image_from_env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("address", type=lambda value: int(value, 16), help="String ELF virtual address")
    args = parser.parse_args()
    image = image_from_env()
    hits = image.string_xrefs(args.address)
    print(f"xrefs to 0x{args.address:X}: {len(hits)}")
    for address in hits:
        print(f"  adrp@0x{address:X} func~0x{image.function_start(address):X}")


if __name__ == "__main__":
    cli(main)
