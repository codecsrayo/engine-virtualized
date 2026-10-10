r"""flcrc <-> player ID for Yu-Gi-Oh! Power of Chaos: Joey the Passion.

The game keeps an 8-byte player ID (Deck.ydc header) encrypted as 13 bytes in
HKLM\Software\KONAMI\Yu-Gi-Oh! Power Of Chaos\system\flcrc. A Wine prefix without that value
makes the game invent a new ID and reset the saves. This runs the game's own cipher methods
(vtable 0x5da584: encrypt 0x439fb0, decrypt 0x439ff0) under Unicorn.

  python flcrc.py enc <16 hex ID>      -> 26 hex flcrc
  python flcrc.py dec <26 hex flcrc>   -> 16 hex ID
"""
import struct, sys
import pefile
from unicorn import Uc, UC_ARCH_X86, UC_MODE_32
from unicorn.x86_const import UC_X86_REG_ESP, UC_X86_REG_ECX, UC_X86_REG_EAX

# The cipher is run from the game's own code: point JOEY_EXE at your joey_pc.exe.
import os
EXE = os.environ.get('JOEY_EXE', 'joey_pc.exe')
ENC, DEC, VTABLE = 0x439fb0, 0x439ff0, 0x5da584


def _call(func, src, n, dst_len):
    pe = pefile.PE(EXE)
    base = pe.OPTIONAL_HEADER.ImageBase
    img = pe.get_memory_mapped_image()
    mu = Uc(UC_ARCH_X86, UC_MODE_32)
    size = (len(img) + 0xfff) & ~0xfff
    mu.mem_map(base, size)
    mu.mem_write(base, img)
    STK, DATA, RET = 0x100000, 0x200000, 0x300000
    mu.mem_map(STK, 0x10000); mu.mem_map(DATA, 0x1000); mu.mem_map(RET, 0x1000)
    obj, srcp, dstp = DATA, DATA + 0x100, DATA + 0x200
    mu.mem_write(obj, struct.pack('<I', VTABLE))
    mu.mem_write(srcp, src)
    sp = STK + 0x8000
    mu.mem_write(sp, struct.pack('<IIII', RET, srcp, n, dstp))   # ret, arg1, arg2, arg3 (stdcall)
    mu.reg_write(UC_X86_REG_ESP, sp)
    mu.reg_write(UC_X86_REG_ECX, obj)
    mu.emu_start(func, RET, count=2_000_000)
    return bytes(mu.mem_read(dstp, dst_len)), mu.reg_read(UC_X86_REG_EAX)


def encrypt(pid: bytes) -> bytes:
    return _call(ENC, pid, 8, 13)[0]


def decrypt(flcrc: bytes) -> bytes:
    out, ok = _call(DEC, flcrc, 8, 8)   # the game passes the plain length (8)
    if ok != 0:
        raise ValueError("flcrc checksum mismatch")
    return out


if __name__ == '__main__':
    mode, val = sys.argv[1], bytes.fromhex(sys.argv[2])
    print((encrypt(val) if mode == 'enc' else decrypt(val)).hex())
