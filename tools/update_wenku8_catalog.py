"""Compact a downloaded mojimoon/wenku8 catalogue; no novel content is included."""
import argparse
import csv
import gzip
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--catalog', type=Path, required=True)
parser.add_argument('--aliases', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
catalog = json.loads(args.catalog.read_text(encoding='utf-8-sig'))
books = {}
for page in catalog['pages'].values():
    for book in page:
        books[int(book['aid'])] = book
aliases = {}
with args.aliases.open(encoding='utf-8-sig', newline='') as stream:
    for row in csv.DictReader(stream):
        aliases.setdefault(int(row['aid']), []).append(row['title'])

lines = ['wenku8-search-v1']
for aid, book in sorted(books.items()):
    fields = [str(aid), book['title'], book['author'], *aliases.get(aid, [])]
    assert aid > 0 and all('\t' not in value and '\n' not in value and '\r' not in value for value in fields)
    lines.append('\t'.join(fields))
payload = ('\n'.join(lines) + '\n').encode('utf-8')
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_bytes(gzip.compress(payload, compresslevel=9, mtime=0))
print(f'{len(books)} books, {len(payload)} bytes TSV, {args.output.stat().st_size} bytes gzip')
