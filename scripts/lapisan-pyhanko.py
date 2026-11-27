"""Struktur lapisan PDF (revisi, jenis tanda tangan, isi DSS) dibaca dengan pyHanko. Dipakai di T-021 (TC-05).

Pemakaian: python scripts/lapisan-pyhanko.py berkas1.pdf [berkas2.pdf ...]
"""
import sys

from pyhanko.pdf_utils.reader import PdfFileReader

for path in sys.argv[1:]:
    with open(path, 'rb') as f:
        r = PdfFileReader(f)
        print('==', path, '| revisi:', r.xrefs.total_revisions)
        for sig in r.embedded_signatures:
            print('  ', sig.field_name, sig.sig_object_type, '| menutup revisi', sig.signed_revision + 1,
                  '| subfilter', sig.sig_object.get('/SubFilter'))
        dss = r.root.get('/DSS')
        if dss is None:
            print('   DSS: tidak ada')
        else:
            dss = dss.get_object()
            print('   DSS:', {str(k): len(dss[k]) for k in dss.keys()})
