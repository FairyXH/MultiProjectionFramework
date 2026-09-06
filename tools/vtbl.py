#!/usr/bin/env python3
"""Resolve vtable slots through ordinary .rela.dyn R_AARCH64_RELATIVE addends."""
import argparse
from elf_utils import cli, image_from_env, positive_count
from rela import print_slots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("address", type=lambda value: int(value, 16), help="Vtable ELF virtual address")
    parser.add_argument("count", type=positive_count, nargs="?", default=40)
    args = parser.parse_args()
    print_slots(image_from_env(), args.address, args.count)


if __name__ == "__main__":
    cli(main)
