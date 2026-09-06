#!/usr/bin/env python3
"""Guess an ARM64 function start without reading before its executable LOAD segment."""
import argparse
from elf_utils import cli, image_from_env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("address", type=lambda value: int(value, 16), help="Instruction ELF virtual address")
    args = parser.parse_args()
    print(f"func start ~0x{image_from_env().function_start(args.address):X}")


if __name__ == "__main__":
    cli(main)
