#!/usr/bin/env python3
"""ELF-aware ARM64 inspection. Only the dis command requires capstone."""
import argparse
from elf_utils import ElfError, cli, image_from_env, positive_count
from rela import print_slots


def disassemble(image, start, count):
    try:
        import capstone
    except ImportError as error:
        raise ElfError("The dis command requires capstone; install it with python3 -m pip install capstone") from error
    segment = image.segment_at(start, 4, executable=True)
    if start % 4 or segment is None:
        raise ElfError("Disassembly needs a file-backed, aligned executable address")
    length = min(count * 4, segment.va + segment.filesz - start)
    offset = image.va_to_off(start)
    decoder = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
    decoder.detail = True
    registers = {}
    for instruction in decoder.disasm(image.span(offset, length), start):
        mnemonic = instruction.mnemonic
        annotation = ""
        prior = dict(registers)
        for register in instruction.regs_access()[1]:
            registers.pop(instruction.reg_name(register), None)
        operands = instruction.operands
        if mnemonic == "adrp":
            register = instruction.reg_name(operands[0].reg)
            registers[register] = operands[1].imm
            annotation = f" ; page=0x{operands[1].imm:X}"
        elif mnemonic == "add" and len(operands) == 3 and operands[2].type == capstone.arm64.ARM64_OP_IMM:
            source = instruction.reg_name(operands[1].reg)
            if source in prior:
                address = prior[source] + (operands[2].imm << operands[2].shift.value)
                registers[instruction.reg_name(operands[0].reg)] = address
                value = image.read_string(address)
                annotation = f" ; = 0x{address:X}"
                if value and value.isprintable():
                    annotation += f" {value!r}"
        elif mnemonic == "ldr" and len(operands) == 2 and operands[1].type == capstone.arm64.ARM64_OP_MEM:
            base = instruction.reg_name(operands[1].mem.base)
            if base in prior:
                address = prior[base] + operands[1].mem.disp
                size = 4 if instruction.reg_name(operands[0].reg).startswith("w") else 8
                value = image.read_int(address, size)
                annotation = f" ; [0x{address:X}] = " + (f"0x{value:X}" if value is not None else "<not file-backed>")
        if instruction.group(capstone.CS_GRP_JUMP) or instruction.group(capstone.CS_GRP_CALL):
            registers.clear()
        print(f"0x{instruction.address:08X}: {image.read_int(instruction.address, 4):08X}  "
              f"{mnemonic:7s} {instruction.op_str}{annotation}")
        if mnemonic == "ret":
            break


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("dis", "vtbl", "u64", "off", "va"))
    parser.add_argument("address", type=lambda value: int(value, 16))
    parser.add_argument("count", type=positive_count, nargs="?")
    args = parser.parse_args()
    image = image_from_env()
    if args.command == "dis":
        disassemble(image, args.address, args.count or 120)
    elif args.command == "vtbl":
        print_slots(image, args.address, args.count or 16)
    else:
        value = {"u64": lambda: image.read_int(args.address, 8),
                 "off": lambda: image.va_to_off(args.address),
                 "va": lambda: image.off_to_va(args.address)}[args.command]()
        if value is None:
            raise ElfError(f"0x{args.address:X} is not fully file-backed by a LOAD segment")
        print(f"0x{value:X}")


if __name__ == "__main__":
    cli(main)
