r"""Change Joey the Passion's frame-rate cap live (PC). The game paces itself with a limiter object
holding [fps][(1000<<16)/fps] (set up once from the global at 0x5eb618 = 30). Game logic is
frame-based, so a higher cap = faster game; 0 = no cap.

  python fpslive.py          -> show current cap
  python fpslive.py 40       -> set cap to 40 (game ~1.33x speed)
"""
import ctypes, struct, subprocess, sys
from ctypes import wintypes

k = ctypes.windll.kernel32
pid = int(subprocess.check_output(['powershell', '-NoProfile', '-Command',
                                   '(Get-Process Joey_Pc*).Id']).decode().split()[0])
h = k.OpenProcess(0x0010 | 0x0020 | 0x0008 | 0x0400, False, pid)


class MBI(ctypes.Structure):
    _fields_ = [('BaseAddress', ctypes.c_void_p), ('AllocationBase', ctypes.c_void_p),
                ('AllocationProtect', wintypes.DWORD), ('RegionSize', ctypes.c_size_t),
                ('State', wintypes.DWORD), ('Protect', wintypes.DWORD), ('Type', wintypes.DWORD)]


def regions():
    addr, m = 0, MBI()
    while addr < 0x7fff0000 and k.VirtualQueryEx(h, ctypes.c_void_p(addr), ctypes.byref(m), ctypes.sizeof(m)):
        if m.State == 0x1000 and m.Protect in (0x04, 0x40) and m.Type == 0x20000:  # committed RW private
            yield m.BaseAddress or 0, m.RegionSize
        addr = (m.BaseAddress or 0) + m.RegionSize


def read(a, n):
    b = ctypes.create_string_buffer(n); r = ctypes.c_size_t()
    return b.raw[:r.value] if k.ReadProcessMemory(h, ctypes.c_void_p(a), b, n, ctypes.byref(r)) else b''


def find():
    """Address of the limiter: cached per pid, else the [30][(1000<<16)/30] pair with a sane ring index."""
    import os, tempfile
    cache = os.path.join(tempfile.gettempdir(), f'fpslive_{pid}.txt')
    if os.path.exists(cache):
        a = int(open(cache).read(), 16)
        fps = struct.unpack('<I', read(a, 4))[0]
        return [(a, fps)]
    pat = struct.pack('<II', 30, 0x3e80000 // 30)
    hits = []
    for base, size in regions():
        data = read(base, size)
        i = data.find(pat)
        while i != -1:
            tail = data[i + 0x118:i + 0x11c]
            if len(tail) == 4 and struct.unpack('<I', tail)[0] < 0x40:
                hits.append((base + i, 30))
            i = data.find(pat, i + 1)
    if len(hits) == 1:
        open(cache, 'w').write(hex(hits[0][0]))
    return hits


hits = find()
if not hits:
    sys.exit('limitador no encontrado')
addr, cur = hits[0]
if len(sys.argv) < 2:
    print(f'limitador en {addr:#x}: {cur} FPS (candidatos: {len(hits)})')
else:
    fps = int(sys.argv[1])
    new = struct.pack('<II', fps, (0x3e80000 // fps) if fps else 0)
    w = ctypes.c_size_t()
    ok = k.WriteProcessMemory(h, ctypes.c_void_p(addr), new, 8, ctypes.byref(w))
    print(f'{cur} -> {fps} FPS', 'OK' if ok else 'ERROR')
