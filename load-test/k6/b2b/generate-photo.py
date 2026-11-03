"""Generate a reproducible, valid RGB PNG (~1.4 MB). No external packages or personal photos."""
from pathlib import Path
import random
import struct
import zlib


def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))


if __name__ == '__main__':
    width, height = 600, 800
    rng = random.Random(219)
    pixels = b''.join(b'\0' + rng.randbytes(width * 3) for _ in range(height))
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')
    destination = Path(__file__).parent / 'generated' / 'photo.png'
    destination.parent.mkdir(exist_ok=True)
    destination.write_bytes(png)
    print(f'{destination}: {width}x{height}, {len(png)} bytes')
