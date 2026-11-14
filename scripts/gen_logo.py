"""Usage: python scripts/gen_logo.py <repo root>. Needs shapely, numpy, Pillow.

Redraws the Refine logo (dark tile, red C-ring, red disc with white play, white note) as clean
geometry, then writes every icon the app needs from that one source."""
import math, os, sys
from shapely.geometry import Point, Polygon, LineString, MultiPolygon
from shapely.ops import unary_union
from shapely import affinity
from PIL import Image, ImageDraw

REPO = sys.argv[1]
OUT = __import__("tempfile").gettempdir()  # previews only

# ---- geometry, in a 0..100 logo box, y down -------------------------------------------------
C = (48.8, 49.5)
RES = 256


def ring(width):
    a0, a1 = 45.0, 338.0  # the arc runs from bottom-right, round the left, to upper-right
    pts = [(C[0] + 36 * math.cos(math.radians(a)), C[1] + 36 * math.sin(math.radians(a)))
           for a in [a0 + (a1 - a0) * i / 200 for i in range(201)]]
    return LineString(pts).buffer(width / 2, quad_segs=24, cap_style="round")


disc = Point(C).buffer(26, quad_segs=RES // 4)
tri = Polygon([(40.7, 37.3), (40.7, 61.8), (61.2, 49.6)]).buffer(-1.6).buffer(1.6, quad_segs=16)
head = Point(59.8, 72.3).buffer(6.0, quad_segs=48)
stem = Polygon([(62.1, 54.0), (65.5, 54.0), (65.5, 72.3), (62.1, 72.3)])
flag = Polygon([(62.1, 53.4), (76.6, 49.4), (76.6, 55.4), (65.5, 58.6)]).buffer(-0.6).buffer(0.6, quad_segs=8)
note = unary_union([head, stem, flag])
gap = note.buffer(1.7, quad_segs=16)

ring_c = ring(4.0).difference(gap)
disc_c = disc.difference(gap)
# Single-colour silhouette (notification, themed icon, in-app badge): a thicker ring so it
# survives 24dp, and the play triangle punched out of the disc.
gap_m = note.buffer(2.6, quad_segs=16)
mono = unary_union([ring(5.0).difference(gap_m), disc.difference(gap_m).difference(tri), note])


# ---- vector drawable output ----------------------------------------------------------------
def polys(g):
    return list(g.geoms) if isinstance(g, MultiPolygon) else [g]


def path_data(g, tf):
    out = []
    for p in polys(g):
        for ring_ in [p.exterior, *p.interiors]:
            pts = [tf(x, y) for x, y in ring_.coords[:-1]]
            out.append("M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z")
    return "".join(out)


def grad(x1, y1, x2, y2, c1, c2):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
            f'android:startX="{x1:.1f}" android:startY="{y1:.1f}" android:endX="{x2:.1f}" android:endY="{y2:.1f}" '
            f'android:startColor="{c1}" android:endColor="{c2}"/></aapt:attr>')


def path(g, tf, fill=None, gradient=None):
    attrs = f'android:fillType="evenOdd" android:pathData="{path_data(g, tf)}"'
    if gradient:
        return f'    <path {attrs}>\n        {gradient}\n    </path>\n'
    return f'    <path android:fillColor="{fill}" {attrs}/>\n'


def vector(size_dp, viewport, body, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated from the Refine logo geometry; edit scripts/gen_logo.py, not this file. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n{body}</vector>\n')


RED_HI, RED_LO = "#FFF4292E", "#FFC3121A"
BG_HI, BG_LO = "#FF3C3C41", "#FF1E1F23"


def colour_body(tf, grads=True):
    def g(x1, y1, x2, y2):
        a, b = tf(x1, y1), tf(x2, y2)
        return grad(a[0], a[1], b[0], b[1], RED_HI, RED_LO) if grads else None
    return (path(ring_c, tf, "#FFE41E26", g(30, 13, 70, 86))
            + path(disc_c, tf, "#FFE41E26", g(32, 26, 66, 76))
            + path(tri, tf, "#FFFFFFFF")
            + path(note, tf, "#FFFFFFFF"))


def fit(g, box, pad):
    """Transform placing g's bounds centred in a box x box viewport with pad on each side."""
    x0, y0, x1, y1 = g.bounds
    s = (box - 2 * pad) / max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    return lambda x, y: (box / 2 + (x - cx) * s, box / 2 + (y - cy) * s)


def write(rel, text):
    p = os.path.join(REPO, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote", rel)


adaptive = lambda x, y: (18 + 0.72 * x, 18 + 0.72 * y)  # logo box 100 -> the 72dp visible area of 108
A = "androidApp/src/main/res/drawable/"
CR = "composeApp/src/commonMain/composeResources/drawable/"
write(A + "ic_launcher_foreground.xml", vector(108, 108, colour_body(adaptive), aapt=True))
write(A + "ic_launcher_background.xml", vector(108, 108,
      f'    <path android:pathData="M0,0h108v108h-108z">\n        {grad(30, 0, 78, 108, BG_HI, BG_LO)}\n    </path>\n', aapt=True))
mono_adaptive = fit(mono, 108, 21)  # inside the 66dp safe zone
mono_small = fit(mono, 24, 1.5)
for d in [A, "core/media/media3/src/main/res/drawable/", CR]:
    write(d + "mono.xml", vector(24, 24, path(mono, mono_small, "#FFFFFFFF")))
for d in [A, CR]:
    write(d + "monochrome.xml", vector(108, 108, path(mono, mono_adaptive, "#FFFFFFFF")))
# The in-app mark beside "Refine" in the top bar: the logo in colour, no tile.
mark = fit(unary_union([ring_c, disc_c, note]), 24, 0.5)
write(CR + "logo_mark.xml", vector(24, 24, colour_body(mark, grads=False)))


# ---- raster output ---------------------------------------------------------------------------
def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (3, 5, 7))


def lin(size, p1, p2, c1, c2):
    """Linear gradient image along p1->p2 (pixel coords)."""
    import numpy as np
    yy, xx = np.mgrid[0:size, 0:size].astype("float32")
    dx, dy = p2[0] - p1[0], p2[1] - p1[1]
    t = ((xx - p1[0]) * dx + (yy - p1[1]) * dy) / (dx * dx + dy * dy)
    t = np.clip(t, 0, 1)[..., None]
    a, b = np.array(hexrgb(c1), "float32"), np.array(hexrgb(c2), "float32")
    return Image.fromarray((a + (b - a) * t).astype("uint8"), "RGB")


def mask(size, g, tf):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    for p in polys(g):
        d.polygon([tf(x, y) for x, y in p.exterior.coords], fill=255)
        for i in p.interiors:
            d.polygon([tf(x, y) for x, y in i.coords], fill=0)
    return m


def render(px, margin=0.04):
    S = px * 4
    m = margin * S
    k = (S - 2 * m) / 100
    tf = lambda x, y: (m + x * k, m + y * k)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    tile = Image.new("L", (S, S), 0)
    ImageDraw.Draw(tile).rounded_rectangle([m, m, S - m, S - m], radius=17 * k, fill=255)
    img.paste(lin(S, tf(30, 0), tf(70, 100), BG_HI, BG_LO), (0, 0), tile)
    red = lin(S, tf(32, 20), tf(66, 82), RED_HI, RED_LO)
    for g in (ring_c, disc_c):
        img.paste(red, (0, 0), mask(S, g, tf))
    white = Image.new("RGB", (S, S), (255, 255, 255))
    for g in (tri, note):
        img.paste(white, (0, 0), mask(S, g, tf))
    return img.resize((px, px), Image.LANCZOS)


big = render(1024)
big.save(os.path.join(OUT, "preview.png"))
for rel in ["androidApp/src/main/res/drawable/app_icon.png", CR + "app_icon.png", CR + "circle_app_icon.png"]:
    render(432).save(os.path.join(REPO, rel)); print("wrote", rel)
render(512).save(os.path.join(REPO, "composeApp/icon/circle_app_icon.png"))
big.save(os.path.join(REPO, "composeApp/icon/circle_app_icon.ico"),
         sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
big.save(os.path.join(REPO, "composeApp/icon/circle_app_icon.icns"))
print("wrote desktop icons")

# preview of the single-colour silhouette at notification sizes
mp = Image.new("RGBA", (300, 110), (40, 40, 40, 255))
for i, px in enumerate([96, 48, 24]):
    S = px * 4; tf = fit(mono, S, S * 0.05)
    m = mask(S, mono, tf).resize((px, px), Image.LANCZOS)
    mp.paste(Image.new("RGB", (px, px), (255, 255, 255)), (5 + [0, 110, 180][i], 5), m)
mp.save(os.path.join(OUT, "mono_preview.png"))
