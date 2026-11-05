"""Deterministic synthetic RGBA PNG (~1.4 MiB), never a participant photo."""
from pathlib import Path
import random
import struct
import zlib

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

target = Path(__file__).resolve().parents[1] / 'load/photo.png'
pixels = random.Random(219).randbytes(600 * 800 * 3)
rows = b''.join(b'\0' + pixels[y*1800:(y+1)*1800] for y in range(800))
target.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 600, 800, 8, 2, 0, 0, 0))
                   + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
print(f'Generated synthetic image: {target.stat().st_size} bytes')
