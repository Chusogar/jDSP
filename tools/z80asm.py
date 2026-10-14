#!/usr/bin/env python3
"""Minimal two-pass Z80 assembler for the Filmation demo.

Supports the subset used by asm/filmation.asm: ORG/EQU/DB/DW/DS, labels,
$-hex, decimal, simple + - expressions, and standard (non-indexed) Z80 ops.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REGS8 = {"b": 0, "c": 1, "d": 2, "e": 3, "h": 4, "l": 5, "a": 7}
REGS8_M = {**REGS8, "(hl)": 6}
REGS16 = {"bc": 0, "de": 1, "hl": 2, "sp": 3}
REGS16_PUSH = {"bc": 0, "de": 1, "hl": 2, "af": 3}

CC_JP = {
    "nz": 0xC2,
    "z": 0xCA,
    "nc": 0xD2,
    "c": 0xDA,
    "po": 0xE2,
    "pe": 0xEA,
    "p": 0xF2,
    "m": 0xFA,
}
CC_JR = {"nz": 0x20, "z": 0x28, "nc": 0x30, "c": 0x38}
CC_CALL = {
    "nz": 0xC4,
    "z": 0xCC,
    "nc": 0xD4,
    "c": 0xDC,
    "po": 0xE4,
    "pe": 0xEC,
    "p": 0xF4,
    "m": 0xFC,
}
CC_RET = {
    "nz": 0xC0,
    "z": 0xC8,
    "nc": 0xD0,
    "c": 0xD8,
    "po": 0xE0,
    "pe": 0xE8,
    "p": 0xF0,
    "m": 0xF8,
}
ALU_NAME = {
    "add": 0,
    "adc": 1,
    "sub": 2,
    "sbc": 3,
    "and": 4,
    "xor": 5,
    "or": 6,
    "cp": 7,
}
ROT_NAME = {
    "rlc": 0,
    "rrc": 1,
    "rl": 2,
    "rr": 3,
    "sla": 4,
    "sra": 5,
    "sll": 6,
    "srl": 7,
}


class AsmError(Exception):
    def __init__(self, line_no: int, message: str):
        super().__init__(f"line {line_no}: {message}")
        self.line_no = line_no


def parse_number(token: str) -> int | None:
    t = token.strip().lower()
    if not t:
        return None
    if t.startswith("$"):
        return int(t[1:], 16)
    if t.startswith("0x"):
        return int(t, 16)
    if t.startswith("%"):
        return int(t[1:], 2)
    if t.endswith("h") and re.fullmatch(r"[0-9a-f]+h", t):
        return int(t[:-1], 16)
    if t.endswith("b") and re.fullmatch(r"[01]+b", t):
        return int(t[:-1], 2)
    if re.fullmatch(r"-?\d+", t):
        return int(t, 10)
    return None


def eval_expr(expr: str, labels: dict[str, int], pc: int, line_no: int, strict: bool = True) -> int:
    s = expr.strip()
    if not s:
        raise AsmError(line_no, "empty expression")

    def tok(src: str):
        i = 0
        n = len(src)
        while i < n:
            c = src[i]
            if c.isspace():
                i += 1
                continue
            if c in "+-*()":
                yield (c, None)
                i += 1
                continue
            if c == "$":
                j = i + 1
                if j < n and src[j] in "0123456789abcdefABCDEF":
                    j += 1
                    while j < n and src[j] in "0123456789abcdefABCDEF":
                        j += 1
                    yield ("num", int(src[i + 1 : j], 16))
                    i = j
                else:
                    yield ("num", pc)
                    i += 1
                continue
            if c == "%":
                j = i + 1
                while j < n and src[j] in "01":
                    j += 1
                yield ("num", int(src[i + 1 : j], 2))
                i = j
                continue
            if c.isdigit() or (c == "0" and i + 1 < n and src[i + 1] in "xX"):
                j = i
                if src.startswith("0x", i) or src.startswith("0X", i):
                    j = i + 2
                    while j < n and src[j] in "0123456789abcdefABCDEF":
                        j += 1
                    yield ("num", int(src[i:j], 16))
                    i = j
                    continue
                while j < n and (src[j].isalnum()):
                    j += 1
                chunk = src[i:j]
                val = parse_number(chunk)
                if val is None:
                    raise AsmError(line_no, f"bad number {chunk}")
                yield ("num", val)
                i = j
                continue
            if c.isalpha() or c == "_":
                j = i + 1
                while j < n and (src[j].isalnum() or src[j] == "_"):
                    j += 1
                name = src[i:j]
                if name not in labels:
                    if not strict:
                        yield ("num", 0)
                        i = j
                        continue
                    raise AsmError(line_no, f"unknown label '{name}'")
                yield ("num", labels[name])
                i = j
                continue
            raise AsmError(line_no, f"unexpected '{c}' in expression {expr!r}")
        yield ("end", None)

    tokens = list(tok(s))
    idx = 0

    def peek():
        return tokens[idx][0]

    def pull():
        nonlocal idx
        item = tokens[idx]
        idx += 1
        return item

    def parse_term():
        t, v = pull()
        if t == "+":
            return parse_term()
        if t == "-":
            return -parse_term()
        if t == "(":
            val = parse_add()
            if pull()[0] != ")":
                raise AsmError(line_no, f"missing ) in {expr!r}")
            return val
        if t == "num":
            return v
        raise AsmError(line_no, f"bad expression {expr!r}")

    def parse_mul():
        val = parse_term()
        while peek() == "*":
            pull()
            val *= parse_term()
        return val

    def parse_add():
        val = parse_mul()
        while peek() in ("+", "-"):
            op = pull()[0]
            rhs = parse_mul()
            val = val + rhs if op == "+" else val - rhs
        return val

    value = parse_add()
    if peek() != "end":
        raise AsmError(line_no, f"trailing junk in {expr!r}")
    return value & 0xFFFF


def split_args(arg: str) -> list[str]:
    parts: list[str] = []
    cur: list[str] = []
    depth = 0
    in_str = False
    for ch in arg:
        if in_str:
            cur.append(ch)
            if ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            cur.append(ch)
            continue
        if ch == "(":
            depth += 1
            cur.append(ch)
        elif ch == ")":
            depth -= 1
            cur.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if cur or parts:
        parts.append("".join(cur).strip())
    return [p for p in parts if p != ""]


def parse_db_items(arg: str, labels: dict[str, int], pc: int, line_no: int) -> list[int]:
    out: list[int] = []
    for item in split_args(arg):
        if item.startswith('"') and item.endswith('"'):
            out.extend(ord(ch) & 0xFF for ch in item[1:-1])
        else:
            out.append(eval_expr(item, labels, pc, line_no) & 0xFF)
    return out


class Assembler:
    def __init__(self, source: str):
        self.raw_lines = source.splitlines()
        self.labels: dict[str, int] = {}
        self.bytes = bytearray()
        self.origin = 0
        self.pc = 0
        self.min_addr = None
        self.max_addr = None
        self.listing: list[tuple[int, int, str]] = []

    def strip_comment(self, line: str) -> str:
        in_str = False
        for i, ch in enumerate(line):
            if ch == '"':
                in_str = not in_str
            elif ch == ";" and not in_str:
                return line[:i]
        return line

    def emit(self, data: list[int], line_no: int) -> None:
        for b in data:
            addr = self.pc
            if addr < 0 or addr > 0xFFFF:
                raise AsmError(line_no, f"PC out of range {addr}")
            if addr >= len(self.bytes):
                self.bytes.extend([0] * (addr - len(self.bytes) + 1))
            self.bytes[addr] = b & 0xFF
            self.min_addr = addr if self.min_addr is None else min(self.min_addr, addr)
            self.max_addr = addr if self.max_addr is None else max(self.max_addr, addr)
            self.pc = (self.pc + 1) & 0xFFFF

    def assemble(self) -> None:
        self._pass(False)
        self.bytes = bytearray()
        self.origin = 0
        self.pc = 0
        self.min_addr = None
        self.max_addr = None
        self.listing.clear()
        self._pass(True)

    def _pass(self, emit: bool) -> None:
        self.pc = 0
        for line_no, raw in enumerate(self.raw_lines, 1):
            line = self.strip_comment(raw).strip()
            if not line:
                continue
            label = None
            rest = line
            if ":" in line:
                before, after = line.split(":", 1)
                if before and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", before.strip()):
                    label = before.strip()
                    rest = after.strip()
            elif re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", line.split()[0]) and len(line.split()) >= 3 and line.split()[1].upper() == "EQU":
                pass
            parts = rest.split(None, 1) if rest else []
            if parts and parts[0].upper() == "EQU":
                raise AsmError(line_no, "EQU without name")
            if rest and len(rest.split()) >= 2 and rest.split()[1].upper() == "EQU":
                name = rest.split()[0]
                expr = rest.split(None, 2)[2]
                self.labels[name] = eval_expr(expr, self.labels, self.pc, line_no)
                continue
            if label is not None and not emit:
                if label in self.labels and self.labels[label] != self.pc:
                    # allow re-set to same address
                    pass
                self.labels[label] = self.pc
            elif label is not None:
                self.labels[label] = self.pc
            if not rest:
                continue
            op, _, arg = rest.partition(" ")
            op = op.strip()
            arg = arg.strip()
            start_pc = self.pc
            self.encode(op.upper(), arg, line_no, emit)  # encode matches uppercase opcodes
            if emit and self.pc != start_pc:
                self.listing.append((start_pc, self.pc - start_pc, raw.rstrip()))

    def encode(self, op: str, arg: str, line_no: int, emit: bool) -> None:
        def out(data: list[int]) -> None:
            if emit:
                self.emit(data, line_no)
            else:
                self.pc = (self.pc + len(data)) & 0xFFFF

        def imm8(text: str) -> int:
            return eval_expr(text, self.labels, self.pc, line_no, emit) & 0xFF

        def imm16(text: str) -> int:
            return eval_expr(text, self.labels, self.pc, line_no, emit) & 0xFFFF

        def rel8(target: str) -> int:
            dest = eval_expr(target, self.labels, self.pc, line_no, emit)
            off = dest - (self.pc + 2)
            if emit and (off < -128 or off > 127):
                raise AsmError(line_no, f"relative jump out of range ({off})")
            return off & 0xFF

        if op in ("END", "ENDS"):
            return
        if op == "ORG":
            self.pc = imm16(arg)
            return
        if op in ("DB", "DEFB", "DEFM"):
            data = parse_db_items(arg, self.labels, self.pc, line_no) if (emit or '"' in arg) else None
            if data is None:
                # first pass: count bytes without needing later labels inside strings
                count = 0
                for item in split_args(arg):
                    if item.startswith('"') and item.endswith('"'):
                        count += len(item) - 2
                    else:
                        count += 1
                self.pc = (self.pc + count) & 0xFFFF
            else:
                out(data)
            return
        if op in ("DW", "DEFW"):
            words = split_args(arg)
            data = []
            for w in words:
                v = imm16(w)
                data.extend([v & 0xFF, (v >> 8) & 0xFF])
            out(data)
            return
        if op in ("DS", "DEFS"):
            parts = split_args(arg)
            n = imm16(parts[0])
            fill = imm8(parts[1]) if len(parts) > 1 else 0
            out([fill] * n)
            return
        if op == "NOP":
            out([0x00])
            return
        if op == "DI":
            out([0xF3])
            return
        if op == "EI":
            out([0xFB])
            return
        if op == "HALT":
            out([0x76])
            return
        if op == "SCF":
            out([0x37])
            return
        if op == "CCF":
            out([0x3F])
            return
        if op == "CPL":
            out([0x2F])
            return
        if op == "NEG":
            out([0xED, 0x44])
            return
        if op == "RLCA":
            out([0x07])
            return
        if op == "RRCA":
            out([0x0F])
            return
        if op == "RLA":
            out([0x17])
            return
        if op == "RRA":
            out([0x1F])
            return
        if op == "EXX":
            out([0xD9])
            return
        if op == "LDIR":
            out([0xED, 0xB0])
            return
        if op == "LDDR":
            out([0xED, 0xB8])
            return
        if op == "LDI":
            out([0xED, 0xA0])
            return
        if op == "IM":
            mode = imm8(arg)
            out({0: [0xED, 0x46], 1: [0xED, 0x56], 2: [0xED, 0x5E]}[mode])
            return
        if op == "RET":
            if not arg:
                out([0xC9])
                return
            cc = arg.lower()
            if cc not in CC_RET:
                raise AsmError(line_no, f"bad RET {arg}")
            out([CC_RET[cc]])
            return
        if op == "DJNZ":
            out([0x10, rel8(arg)])
            return
        if op == "JR":
            parts = split_args(arg)
            if len(parts) == 1:
                out([0x18, rel8(parts[0])])
                return
            cc = parts[0].lower()
            if cc not in CC_JR:
                raise AsmError(line_no, f"bad JR {arg}")
            out([CC_JR[cc], rel8(parts[1])])
            return
        if op == "JP":
            parts = split_args(arg)
            if len(parts) == 1 and parts[0].lower() in ("(hl)",):
                out([0xE9])
                return
            if len(parts) == 1:
                nn = imm16(parts[0])
                out([0xC3, nn & 0xFF, nn >> 8])
                return
            cc = parts[0].lower()
            if cc not in CC_JP:
                raise AsmError(line_no, f"bad JP {arg}")
            nn = imm16(parts[1])
            out([CC_JP[cc], nn & 0xFF, nn >> 8])
            return
        if op == "CALL":
            parts = split_args(arg)
            if len(parts) == 1:
                nn = imm16(parts[0])
                out([0xCD, nn & 0xFF, nn >> 8])
                return
            cc = parts[0].lower()
            if cc not in CC_CALL:
                raise AsmError(line_no, f"bad CALL {arg}")
            nn = imm16(parts[1])
            out([CC_CALL[cc], nn & 0xFF, nn >> 8])
            return
        if op == "RST":
            n = imm8(arg)
            if n not in (0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38):
                raise AsmError(line_no, f"bad RST {arg}")
            out([0xC7 + n])
            return
        if op == "PUSH":
            r = arg.lower()
            if r not in REGS16_PUSH:
                raise AsmError(line_no, f"bad PUSH {arg}")
            out([0xC5 | (REGS16_PUSH[r] << 4)])
            return
        if op == "POP":
            r = arg.lower()
            if r not in REGS16_PUSH:
                raise AsmError(line_no, f"bad POP {arg}")
            out([0xC1 | (REGS16_PUSH[r] << 4)])
            return
        if op == "EX":
            a, b = [p.lower() for p in split_args(arg)]
            if {a, b} == {"de", "hl"}:
                out([0xEB])
                return
            if {a, b} == {"af", "af'"}:
                out([0x08])
                return
            if a == "(sp)" and b == "hl":
                out([0xE3])
                return
            raise AsmError(line_no, f"bad EX {arg}")
        if op == "IN":
            dest, port = [p.lower() for p in split_args(arg)]
            if dest == "a" and port.startswith("(") and port.endswith(")"):
                inner = port[1:-1].strip()
                if inner == "c":
                    out([0xED, 0x78])
                    return
                out([0xDB, imm8(inner)])
                return
            raise AsmError(line_no, f"bad IN {arg}")
        if op == "OUT":
            port, src = [p.lower() for p in split_args(arg)]
            if src == "a" and port.startswith("(") and port.endswith(")"):
                inner = port[1:-1].strip()
                if inner == "c":
                    out([0xED, 0x79])
                    return
                out([0xD3, imm8(inner)])
                return
            raise AsmError(line_no, f"bad OUT {arg}")
        if op == "INC":
            r = arg.lower()
            if r in REGS8_M:
                out([0x04 | (REGS8_M[r] << 3)])
                return
            if r in REGS16:
                out([0x03 | (REGS16[r] << 4)])
                return
            raise AsmError(line_no, f"bad INC {arg}")
        if op == "DEC":
            r = arg.lower()
            if r in REGS8_M:
                out([0x05 | (REGS8_M[r] << 3)])
                return
            if r in REGS16:
                out([0x0B | (REGS16[r] << 4)])
                return
            raise AsmError(line_no, f"bad DEC {arg}")
        if op.lower() in ALU_NAME:
            alu = ALU_NAME[op.lower()]
            op = op.lower()
            parts = split_args(arg)
            src = parts[-1].lower()
            if op in ("add", "adc", "sbc") and len(parts) == 2 and parts[0].lower() == "hl":
                rr = parts[1].lower()
                if op != "add" or rr not in REGS16:
                    if op == "adc" and rr in REGS16:
                        out([0xED, 0x4A | (REGS16[rr] << 4)])
                        return
                    if op == "sbc" and rr in REGS16:
                        out([0xED, 0x42 | (REGS16[rr] << 4)])
                        return
                    raise AsmError(line_no, f"bad {op} {arg}")
                out([0x09 | (REGS16[rr] << 4)])
                return
            if op in ("add", "adc", "sbc") and len(parts) == 2:
                if parts[0].lower() != "a":
                    raise AsmError(line_no, f"bad {op} {arg}")
            if src in REGS8_M:
                out([0x80 | (alu << 3) | REGS8_M[src]])
                return
            out([0xC6 | (alu << 3), imm8(parts[-1])])
            return
        if op in ("BIT", "SET", "RES"):
            bit_s, reg = [p.lower() for p in split_args(arg)]
            bit = imm8(bit_s)
            if bit > 7 or reg not in REGS8_M:
                raise AsmError(line_no, f"bad {op} {arg}")
            base = {"BIT": 0x40, "RES": 0x80, "SET": 0xC0}[op]
            out([0xCB, base | (bit << 3) | REGS8_M[reg]])
            return
        if op.lower() in ROT_NAME:
            op = op.lower()
            r = arg.lower()
            if r not in REGS8_M:
                raise AsmError(line_no, f"bad {op} {arg}")
            out([0xCB, (ROT_NAME[op] << 3) | REGS8_M[r]])
            return
        if op == "LD":
            dest, src = [p.strip() for p in split_args(arg)]
            dl, sl = dest.lower(), src.lower()
            if dl in REGS8_M and sl in REGS8_M:
                if dl == "(hl)" and sl == "(hl)":
                    raise AsmError(line_no, "ld (hl),(hl) is halt")
                out([0x40 | (REGS8_M[dl] << 3) | REGS8_M[sl]])
                return
            if dl in REGS8_M and not sl.startswith("("):
                out([0x06 | (REGS8_M[dl] << 3), imm8(src)])
                return
            if dl in REGS16:
                nn = imm16(src)
                out([0x01 | (REGS16[dl] << 4), nn & 0xFF, nn >> 8])
                return
            if dl == "sp" and sl == "hl":
                out([0xF9])
                return
            if dl == "a" and sl == "(bc)":
                out([0x0A])
                return
            if dl == "a" and sl == "(de)":
                out([0x1A])
                return
            if sl == "a" and dl == "(bc)":
                out([0x02])
                return
            if sl == "a" and dl == "(de)":
                out([0x12])
                return
            if dl == "a" and sl.startswith("(") and sl.endswith(")"):
                inner = src[1:-1].strip()
                if inner.lower() not in ("bc", "de", "hl"):
                    nn = imm16(inner)
                    out([0x3A, nn & 0xFF, nn >> 8])
                    return
            if sl == "a" and dl.startswith("(") and dl.endswith(")"):
                inner = dest[1:-1].strip()
                if inner.lower() not in ("bc", "de", "hl"):
                    nn = imm16(inner)
                    out([0x32, nn & 0xFF, nn >> 8])
                    return
            if dl == "hl" and sl.startswith("(") and sl.endswith(")"):
                nn = imm16(src[1:-1])
                out([0x2A, nn & 0xFF, nn >> 8])
                return
            if sl == "hl" and dl.startswith("(") and dl.endswith(")"):
                nn = imm16(dest[1:-1])
                out([0x22, nn & 0xFF, nn >> 8])
                return
            raise AsmError(line_no, f"unsupported LD {arg}")
        raise AsmError(line_no, f"unknown opcode {op} {arg}".strip())


def write_sna48(path: Path, code: bytes, org: int, pc: int) -> None:
    sna = bytearray(27 + 0xC000)
    sp = 0xFF00
    sna[23] = sp & 0xFF
    sna[24] = (sp >> 8) & 0xFF
    sna[25] = 1  # IM 1
    sna[26] = 0  # black border
    ram = memoryview(sna)[27:]
    end = org + len(code)
    if org < 0x4000 or end > 0x10000:
        raise SystemExit(f"code at {org:04X} does not fit in 48K RAM")
    ram[org - 0x4000 : org - 0x4000 + len(code)] = code
    ram[sp - 0x4000] = pc & 0xFF
    ram[sp - 0x4000 + 1] = (pc >> 8) & 0xFF
    path.write_bytes(sna)


def xor_block(flag: int, payload: bytes) -> int:
    x = flag
    for b in payload:
        x ^= b
    return x & 0xFF


def tap_block(flag: int, payload: bytes) -> bytes:
    chk = xor_block(flag, payload)
    body = bytes([flag]) + payload + bytes([chk])
    ln = len(body)
    return bytes([ln & 0xFF, (ln >> 8) & 0xFF]) + body


def u16(n: int) -> bytes:
    return bytes([n & 0xFF, (n >> 8) & 0xFF])


def basic_line(num: int, text: bytes) -> bytes:
    # Spectrum BASIC line: line number (big endian), length, tokens, 0x0D
    return bytes([(num >> 8) & 0xFF, num & 0xFF]) + u16(len(text) + 1) + text + b"\x0d"


def write_tap(path: Path, code: bytes, org: int) -> None:
    # 10 LOAD "" CODE
    # 20 RANDOMIZE USR 32768
    # tokens: LOAD=0xEF, CODE=0xAF, RANDOMIZE=0xF9, USR=0xC0, "" = 0x22 0x22
    # number encoding: 0x0E + 5-byte float
    def zx_int(n: int) -> bytes:
        return bytes([0x0E, 0x00, 0x00, n & 0xFF, (n >> 8) & 0xFF, 0x00])

    line10 = bytes([0xEF, 0x22, 0x22, 0xAF])  # LOAD "" CODE
    line20 = bytes([0xF9, 0xC0, 0x32, 0x33, 0x37, 0x36, 0x38]) + zx_int(org)  # RANDOMIZE USR 32768
    prog = basic_line(10, line10) + basic_line(20, line20)
    name_bas = b"filmation  "
    hdr_bas = bytes([0x00]) + name_bas + u16(len(prog)) + u16(10) + u16(len(prog))
    name_code = b"brick     "
    hdr_code = bytes([0x03]) + name_code + u16(len(code)) + u16(org) + u16(0)
    path.write_bytes(
        tap_block(0x00, hdr_bas)
        + tap_block(0xFF, prog)
        + tap_block(0x00, hdr_code)
        + tap_block(0xFF, code)
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("-o", "--outdir", default=None)
    args = ap.parse_args()
    src_path = Path(args.source)
    text = src_path.read_text(encoding="utf-8")
    asm = Assembler(text)
    try:
        asm.assemble()
    except AsmError as exc:
        print(exc, file=sys.stderr)
        return 1
    if asm.min_addr is None:
        print("no code emitted", file=sys.stderr)
        return 1
    org = asm.min_addr
    code = bytes(asm.bytes[org : asm.max_addr + 1])
    outdir = Path(args.outdir) if args.outdir else src_path.parent
    outdir.mkdir(parents=True, exist_ok=True)
    stem = src_path.stem
    (outdir / f"{stem}.bin").write_bytes(code)
    write_sna48(outdir / f"{stem}.sna", code, org, org)
    write_tap(outdir / f"{stem}.tap", code, org)
    sym = outdir / f"{stem}.sym"
    with sym.open("w", encoding="utf-8") as fh:
        for name in sorted(asm.labels, key=lambda n: asm.labels[n]):
            fh.write(f"{name:20s} EQU ${asm.labels[name]:04X}\n")
    lst = outdir / f"{stem}.lst"
    with lst.open("w", encoding="utf-8") as fh:
        for addr, size, raw in asm.listing:
            data = " ".join(f"{asm.bytes[addr + i]:02X}" for i in range(size)[:8])
            fh.write(f"{addr:04X}  {data:<24s} {raw}\n")
    print(f"assembled {len(code)} bytes at ${org:04X} -> {outdir / (stem + '.bin')}")
    print(f"labels: {len(asm.labels)}  entry: ${org:04X}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
