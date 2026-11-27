"""Tampilkan objek-objek pada satu revisi (incremental update) PDF, tanpa isi stream biner."""
import re
import sys

path, rev = sys.argv[1], int(sys.argv[2])
data = open(path, 'rb').read()
eofs = [m.end() for m in re.finditer(rb'%%EOF\r?\n?', data)]
start = 0 if rev == 0 else eofs[rev - 1]
end = eofs[rev]
section = data[start:end]
print('revisi', rev, 'byte', start, '-', end, 'panjang', len(section))
out = re.sub(rb'stream\r?\n.*?endstream', lambda m: b'stream <%d byte> endstream' % len(m.group(0)), section,
             flags=re.S)
out = re.sub(rb'/Contents\s*<[0-9a-fA-F]+>', b'/Contents <hex>', out)
sys.stdout.write(out.decode('latin-1'))
