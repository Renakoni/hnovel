"""Synthetic public fixtures; regenerate with fonttools 4.25.0 and py7zr 1.1.3.
No downloaded book/source/font data. RAR uses an uncompressed RAR4 record.
"""
from pathlib import Path
from io import BytesIO
from struct import pack
from zlib import crc32
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
import py7zr
import zipfile

root = Path(__file__).parent
for name, point in [('plain', 65), ('obfuscated', 0xE000), ('supplementary', 0x100000)]:
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder(['.notdef', 'triangle'])
    builder.setupCharacterMap({point: 'triangle'})
    empty = TTGlyphPen(None)
    pen = TTGlyphPen(None)
    pen.moveTo((0, 0)); pen.lineTo((100, 200)); pen.lineTo((200, 0)); pen.closePath()
    builder.setupGlyf({'.notdef': empty.glyph(), 'triangle': pen.glyph()})
    builder.setupHorizontalMetrics({'.notdef': (500, 0), 'triangle': (500, 0)})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({'familyName': 'Synthetic', 'styleName': 'Regular', 'uniqueFontIdentifier': 'synthetic-test', 'fullName': 'Synthetic Regular', 'psName': 'Synthetic-Regular'})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost(); builder.setupMaxp()
    builder.font['head'].created = builder.font['head'].modified = 3400000000
    builder.save(root / (name + '.ttf'))

payload = b'synthetic chapter'
with zipfile.ZipFile(root / 'chapter.zip', 'w') as archive:
    info = zipfile.ZipInfo('chapter.txt', (2020, 1, 1, 0, 0, 0))
    archive.writestr(info, payload)
with py7zr.SevenZipFile(root / 'chapter.7z', 'w') as archive:
    archive.writestr(payload, 'chapter.txt')

def header(kind, flags, body):
    data = pack('<BHH', kind, flags, len(body) + 7) + body
    return pack('<H', crc32(data) & 0xFFFF) + data

filename = b'chapter.txt'
main = header(0x73, 0, bytes(6))
file = header(0x74, 0x8000, pack('<IIBIIBBHI', len(payload), len(payload), 2, crc32(payload), 0, 20, 0x30, len(filename), 0x20) + filename)
(root / 'chapter.rar').write_bytes(b'Rar!\x1a\x07\x00' + main + file + payload + header(0x7b, 0, b''))
