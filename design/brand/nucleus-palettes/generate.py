"""Ring-free, three-tone variants of the approved A silhouette."""
from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
source = ET.parse(OUT.parent / "tonal-studies/matter-nucleus.svg").getroot()
core = "".join(ET.tostring(child, encoding="unicode") for child in source
               if child.tag != NS + "title" and child.attrib.get("id") != "ring")
OLD = ("#E5D1AB", "#C8AC79", "#A58B60")
palettes = [
    ("mist-blue", "01 / 雾蓝", "清冷、安静，像磨砂陶瓷", ("#B9C9DD", "#7E96B4", "#536B88")),
    ("slate-lilac", "02 / 灰紫", "柔和、内敛，多一点个性", ("#C5C2D9", "#9791B3", "#6C6689")),
    ("mineral-teal", "03 / 青灰", "温润、自然，像浅色矿石", ("#BDCEC9", "#88A69F", "#5D7E77")),
]


def svg(body, w=512, h=512):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><title>Matter / Ring-free nucleus</title>{body}</svg>'


def mark(colors, identity):
    body = core.replace("nucleus-clip", identity)
    for original, color in zip(OLD, colors):
        body = body.replace(original, color)
    # Enlarged from the ringed version and centred by the silhouette bounds.
    return f'<g transform="translate(256 256) scale(1.45) translate(-255 -254.5)">{body}</g>'


def place(body, x, y, size):
    return f'<g transform="translate({x} {y}) scale({size/512})">{body}</g>'


def text(x, y, value, size=18, color="#717985", weight=400):
    return f'<text x="{x}" y="{y}" fill="{color}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="{size}" font-weight="{weight}">{escape(value)}</text>'


board = '<rect width="1440" height="940" fill="#F0F2F5"/>'
board += text(56, 72, "Matter / 柔和的物质感", 38, "#354252", 600)
board += text(58, 115, "保留 A 的轮廓与三层弧面 · 去掉圆环 · 低饱和冷色", 21)
for i, (slug, title, note, colors) in enumerate(palettes):
    x = 48 + i * 456
    board += f'<rect x="{x}" y="156" width="432" height="712" rx="28" fill="#FFFFFF"/>'
    board += place(mark(colors, f"hero-{i}"), x + 16, 171, 400)
    board += text(x + 28, 591, title, 28, "#354252", 600)
    board += text(x + 28, 630, note, 19)
    for k, color in enumerate(colors):
        board += f'<rect x="{x+28+k*38}" y="655" width="30" height="12" rx="6" fill="{color}"/>'
    board += f'<path d="M{x+28} 695H{x+404}" stroke="#EDF0F3"/>'
    board += f'<rect x="{x+28}" y="716" width="100" height="100" rx="24" fill="#F1F4F8"/>'
    board += place(mark(colors, f"light-{i}"), x + 28, 716, 100)
    board += f'<rect x="{x+148}" y="716" width="100" height="100" rx="24" fill="#283340"/>'
    board += place(mark(colors, f"dark-{i}"), x + 148, 716, 100)
    board += place(mark(colors, f"small-{i}"), x + 277, 742, 48)
    board += place(mark(colors, f"tiny-{i}"), x + 349, 750, 32)
    board += text(x + 31, 845, "浅底", 15)
    board += text(x + 151, 845, "深底", 15)
    board += text(x + 279, 845, "48 / 32 px", 15)
    body = mark(colors, f"mark-{slug}")
    (OUT / f"matter-{slug}.svg").write_text(svg(body), encoding="utf-8")
    (OUT / f"matter-{slug}-icon.svg").write_text(svg('<rect width="512" height="512" rx="112" fill="#FFFFFF"/>' + body), encoding="utf-8")
board += text(56, 914, "建议：雾蓝更清爽耐看；灰紫更有个性。三档明暗保持形状层次，中心开孔保持透明。", 19)
(OUT / "matter-palette-board.svg").write_text(svg(board, 1440, 940), encoding="utf-8")
(OUT / "README.md").write_text(
    "# Matter 无圆环凝核配色\n\n"
    "沿用 A 的柔软轮廓、偏心透明开孔和三档明暗；移除外环，主体放大 1.45 倍并按轮廓居中。\n\n"
    "- 雾蓝：#B9C9DD / #7E96B4 / #536B88\n"
    "- 灰紫：#C5C2D9 / #9791B3 / #6C6689\n"
    "- 青灰：#BDCEC9 / #88A69F / #5D7E77\n\n"
    "每组包括透明底 SVG 与白底圆角展示稿。没有渐变或位图，浅底和深底预览使用同一配色。当前仅为设计候选，未修改 Android 图标资源。\n",
    encoding="utf-8",
)
