"""Build patched variants of redroid's /vendor/lib/libOmxCore.so (FFmpeg h264_vaapi wrapper).

The encoder setup code hard-codes:  rc_mode="CQP", global_quality=29, gop_size=150.
Variants:
  qpNN      keep CQP, change the fixed QP (higher = smaller, +6 ~ half the bitrate), GOP -> 3600 frames
  vbrNNNk   switch to VBR with a fixed target bitrate (the "global_quality" store becomes "bit_rate"), GOP -> 3600
Every patch is verified against the expected original bytes and re-disassembled.
"""
import hashlib
import re
import struct
import sys

import capstone

src, outdir = sys.argv[1], sys.argv[2]
d = bytearray(open(src, "rb").read())
shoff, = struct.unpack_from("<I", d, 0x20)
shentsize, shnum, shstrndx = struct.unpack_from("<HHH", d, 0x2E)
secs = []
for i in range(shnum):
    name, typ, flags, addr, off, size = struct.unpack_from("<IIIIII", d, shoff + i * shentsize)
    secs.append([name, typ, flags, addr, off, size])
st = secs[shstrndx]
for s in secs:
    s[0] = bytes(d[st[4] + s[0]: d.index(b"\0", st[4] + s[0])]).decode()
sec = {s[0]: s for s in secs}
GOT = sec[".got.plt"][3]


def va2off(v):
    for n, t, f, a, off, sz in secs:
        if a and t != 8 and a <= v < a + sz:
            return off + (v - a)


def off2va(o):
    for n, t, f, a, off, sz in secs:
        if a and t != 8 and off <= o < off + sz:
            return a + (o - off)


VA_QP, VA_GOP, VA_LEA_CQP = 0x42ad92, 0x42ad99, 0x42ae3c
ORIG_QP = bytes.fromhex("c740301d000000")   # mov dword [eax+0x30], 29   (AVCodecContext.global_quality)
ORIG_GOP = bytes.fromhex("c7406896000000")  # mov dword [eax+0x68], 150  (AVCodecContext.gop_size)
assert d[va2off(VA_QP):va2off(VA_QP) + 7] == ORIG_QP, "unexpected bytes at QP site"
assert d[va2off(VA_GOP):va2off(VA_GOP) + 7] == ORIG_GOP, "unexpected bytes at GOP site"
lea = bytes(d[va2off(VA_LEA_CQP):va2off(VA_LEA_CQP) + 6])
assert lea[:2] == b"\x8d\x83", lea.hex()                 # lea eax, [ebx + disp32]
cqp_va = GOT + struct.unpack("<i", lea[2:])[0]
assert bytes(d[va2off(cqp_va):va2off(cqp_va) + 4]) == b"CQP\0"
# a NUL-terminated "VBR" string already present in .rodata
ro = sec[".rodata"]
m = re.search(rb"\0VBR\0", bytes(d[ro[4]:ro[4] + ro[5]]))
assert m, "no VBR string"
vbr_va = off2va(ro[4] + m.start() + 1)
print("original sha256", hashlib.sha256(d).hexdigest())
print("CQP string @%#x, VBR string @%#x" % (cqp_va, vbr_va))

md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)


def show(buf, va, n):
    out = []
    for ins in md.disasm(bytes(buf[va2off(va):va2off(va) + n]), va):
        out.append("%#x: %s %s" % (ins.address, ins.mnemonic, ins.op_str))
    return out


GOP = 3600
variants = [("gop3600", ("qp", 29))] + [("qp%d" % q, ("qp", q)) for q in (31, 32, 34, 36, 38, 40)] + [("vbr%dk" % k, ("vbr", k * 1000)) for k in (500, 800, 1200)]
for name, (kind, val) in variants:
    b = bytearray(d)
    b[va2off(VA_GOP):va2off(VA_GOP) + 7] = b"\xc7\x40\x68" + struct.pack("<I", GOP)
    if kind == "qp":
        b[va2off(VA_QP):va2off(VA_QP) + 7] = b"\xc7\x40\x30" + struct.pack("<I", val)
    else:
        # bit_rate is the int64 at +0x24 (high half stays 0 from allocation); global_quality is unused outside CQP
        b[va2off(VA_QP):va2off(VA_QP) + 7] = b"\xc7\x40\x24" + struct.pack("<I", val)
        b[va2off(VA_LEA_CQP) + 2:va2off(VA_LEA_CQP) + 6] = struct.pack("<i", vbr_va - GOT)
    path = "%s/libOmxCore_%s.so" % (outdir, name)
    open(path, "wb").write(b)
    diff = sum(1 for x, y in zip(d, b) if x != y)
    lea2 = bytes(b[va2off(VA_LEA_CQP):va2off(VA_LEA_CQP) + 6])
    tgt = GOT + struct.unpack("<i", lea2[2:])[0]
    rc = bytes(b[va2off(tgt):va2off(tgt) + 3]).decode()
    print("%-9s bytes changed=%2d  rc_mode=%s  | %s | %s" % (name, diff, rc, show(b, VA_QP, 7)[0], show(b, VA_GOP, 7)[0]))
