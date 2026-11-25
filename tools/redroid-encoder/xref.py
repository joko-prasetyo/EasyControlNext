"""Find where RedroidAVCEnc configures the FFmpeg encoder: locate GOT-relative references to key strings
in .text (i386 PIC: lea off(%ebx)), then disassemble the surrounding function."""
import re
import struct
import sys

import capstone

d = open(sys.argv[1], "rb").read()
shoff, = struct.unpack_from("<I", d, 0x20)
shentsize, shnum, shstrndx = struct.unpack_from("<HHH", d, 0x2E)
secs = []
for i in range(shnum):
    name, typ, flags, addr, off, size = struct.unpack_from("<IIIIII", d, shoff + i * shentsize)
    secs.append([name, typ, flags, addr, off, size])
st = secs[shstrndx]
for s in secs:
    s[0] = d[st[4] + s[0]: d.index(b"\0", st[4] + s[0])].decode()
sec = {s[0]: s for s in secs}
text = sec[".text"]
got = sec.get(".got.plt") or sec[".got"]
GOT = got[3]


def off2va(o):
    for n, t, f, a, off, sz in secs:
        if a and t != 8 and off <= o < off + sz:
            return a + (o - off)


def va2off(v):
    for n, t, f, a, off, sz in secs:
        if a and t != 8 and a <= v < a + sz:
            return off + (v - a)


def cstr_va(s):
    out = []
    for m in re.finditer(re.escape(b"\0" + s.encode() + b"\0"), d):
        out.append(off2va(m.start() + 1))
    return [v for v in out if v]


tbytes = d[text[4]: text[4] + text[5]]


def xrefs(va):
    disp = struct.pack("<i", va - GOT)
    return [text[3] + m.start() for m in re.finditer(re.escape(disp), tbytes)]


print("GOT base %#x (%s)" % (GOT, got[0]))
targets = sys.argv[2:]
for s in targets:
    for va in cstr_va(s):
        xs = xrefs(va)
        print("%-28r @%#x  xrefs: %s" % (s, va, [hex(x) for x in xs[:12]]))

if "--dis" in sys.argv:
    idx = sys.argv.index("--dis")
    start = int(sys.argv[idx + 1], 16)
    length = int(sys.argv[idx + 2], 16)
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    code = d[va2off(start): va2off(start) + length]
    for ins in md.disasm(code, start):
        note = ""
        m = re.search(r"\[ebx ([+-]) (0x[0-9a-f]+)\]", ins.op_str)
        if m:
            tv = GOT + (int(m.group(2), 16) if m.group(1) == "+" else -int(m.group(2), 16))
            o = va2off(tv)
            if o and sec[".rodata"][3] <= tv < sec[".rodata"][3] + sec[".rodata"][5]:
                note = "   ; " + repr(d[o: d.index(b"\0", o)][:50].decode("latin1"))
        print("%#x: %-8s %s%s" % (ins.address, ins.mnemonic, ins.op_str, note))
