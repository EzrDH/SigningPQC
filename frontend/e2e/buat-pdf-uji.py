import sys
def pdf(path, title, rotate=0):
    text = f"BT /F1 18 Tf 72 760 Td ({title}) Tj ET BT /F1 11 Tf 72 730 Td (Dokumen uji sintetis untuk aplikasi tanda tangan.) Tj ET"
    for i in range(12):
        text += f" BT /F1 11 Tf 72 {700-i*18} Td (Baris isi dokumen uji nomor {i+1}.) Tj ET"
    rot = f" /Rotate {rotate}" if rotate else ""
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.28 841.89]{rot} /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(text)} >>\nstream\n{text}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = b"%PDF-1.7\n"
    offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode()
    x = len(out)
    out += f"xref\n0 {len(objs)+1}\n0000000000 65535 f \n".encode()
    for o in offs:
        out += f"{o:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n".encode()
    open(path, "wb").write(out)
pdf(sys.argv[1] + "/surat-uji.pdf", "SURAT KETERANGAN UJI")
pdf(sys.argv[1] + "/pindai-lanskap.pdf", "HALAMAN BEROTASI 90", 90)
