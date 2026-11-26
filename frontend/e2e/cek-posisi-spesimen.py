import json, sys
from PIL import Image
for label, name in (("a4","surat-uji"),("rotasi90","pindai-lanskap")):
    box = json.load(open(f"/tmp/e2e/box-{label}.json"))
    a, c = box["after"], box["canvasBox"]
    img = Image.open(f"/tmp/e2e/render-{name}.png").convert("RGB")
    W, H = img.size
    k = W / c["width"]  # point per piksel CSS
    exp = [(a["x"]-c["x"])*k, (a["y"]-c["y"])*k, (a["x"]-c["x"]+a["width"])*k, (a["y"]-c["y"]+a["height"])*k]
    px = img.load(); xs=[]; ys=[]
    for x in range(W):
        for y in range(H):
            r,g,b = px[x,y]
            if b > 100 and b - r > 60:  # garis spesimen biru
                xs.append(x); ys.append(y)
    got = [min(xs), min(ys), max(xs), max(ys)] if xs else None
    inside = got and got[0] >= exp[0]-3 and got[1] >= exp[1]-3 and got[2] <= exp[2]+3 and got[3] <= exp[3]+3
    print(label, f"halaman {W}x{H}", "kotak layar->pt", [round(v,1) for v in exp], "spesimen", got, "DI DALAM" if inside else "MELESET")
