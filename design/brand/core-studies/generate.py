"""Standalone vector explorations; reads the current ring without changing app assets."""
from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
SOURCE = OUT.parent / "ring-fries/matter-c.svg"
NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
source = ET.parse(SOURCE).getroot()
ring = ET.tostring(source.find(f"{NS}g[@id='ring']"), encoding="unicode")
current = ET.tostring(source.find(f"{NS}g[@id='fries-m']"), encoding="unicode")
INK = "#252A27"

# One compact, soft silhouette with a small off-centre opening.
nucleus = f"""<g fill="{INK}">
<path fill-rule="evenodd" d="
M256 143
C284 143 297 174 316 191
C335 209 368 221 368 251
C368 282 337 295 318 315
C299 335 285 366 255 366
C225 366 212 336 192 317
C172 298 142 284 142 254
C142 224 172 211 192 191
C212 171 226 143 256 143 Z
M281 216
A29 29 0 1 0 281 274
A29 29 0 1 0 281 216 Z"/>
</g>"""

# Three rounded units share a rotational rhythm and a triangular negative space.
unit = '<path d="M241 155 C254 148 268 153 276 166 L310 222 C318 236 314 249 300 257 C287 265 273 261 265 247 L231 192 C223 178 227 163 241 155 Z"/>'
aggregate = f'<g fill="{INK}">' + "".join(
    f'<g transform="rotate({a} 256 256)">{unit}</g>' for a in (0, 120, 240)
) + "</g>"

# A single continuous letterform with an expressive deep centre.
joined = f"""<path d="M165 325 V199
Q165 182 181 182 Q189 182 197 194
L256 278 L315 194
Q323 182 331 182 Q347 182 347 199 V325"
fill="none" stroke="{INK}" stroke-width="42"
stroke-linecap="round" stroke-linejoin="round"/>"""

concepts = [
    ("current", "现版 / 分段 M", "四段各自成立，整体轮廓偏弱", current),
    ("nucleus", "A / 凝核", "柔软实体＋偏心留白，更有物质感", nucleus),
    ("aggregate", "B / 聚合", "三个单元向心旋转，更有运动感", aggregate),
    ("joined", "C / 连笔 M", "连接笔画、拉深中谷，字形更明确", joined),
]


def svg(body, width=512, height=512):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}"><title>Matter / Core studies</title>{body}</svg>'


def text(x, y, value, size=18, fill=INK, weight=400):
    return f'<text x="{x}" y="{y}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}">{escape(value)}</text>'


def place(body, x, y, size):
    return f'<g transform="translate({x} {y}) scale({size / 512})">{body}</g>'


board = '<rect width="1600" height="940" fill="#F3F2EC"/>'
board += text(48, 68, "Matter / 让中心成为标志", 36, weight=600)
board += text(50, 108, "保留你调整后的金色圆环 · 相同尺寸、相同配色，比较中心轮廓", 20, "#71756D")
for i, (slug, title, note, core) in enumerate(concepts):
    x = 48 + i * 388
    board += f'<rect x="{x}" y="150" width="364" height="716" rx="24" fill="#FFFFFF"/>'
    board += place(ring + core, x + 12, 171, 340)
    board += text(x + 24, 551, title, 25, weight=600)
    board += text(x + 24, 587, note, 16, "#71756D")
    board += f'<path d="M{x+24} 619H{x+340}" stroke="#E8E9E1"/>'
    board += text(x + 24, 650, "中心单独看", 15, "#71756D")
    board += place(core, x + 8, 665, 180)
    board += text(x + 190, 650, "48 / 32 px", 15, "#71756D")
    # Match the current Android foreground scale (0.66) and nominal 72/108 crop.
    launcher_mark = f'<g transform="translate(256 256) scale(0.99) translate(-256 -256)">{ring}{core}</g>'
    for sx, size in ((x + 196, 48), (x + 273, 32)):
        board += place(launcher_mark, sx, 718 + (48-size)/2, size)
    (OUT / f"matter-{slug}.svg").write_text(svg(ring + core), encoding="utf-8")
    (OUT / f"matter-{slug}-core.svg").write_text(svg(core), encoding="utf-8")
board += text(48, 910, "建议优先看 A 的实体感、C 的辨识度；B 更活跃，但也更接近常见旋转符号。", 19, "#71756D")
(OUT / "matter-core-studies.svg").write_text(svg(board, 1600, 940), encoding="utf-8")
(OUT / "README.md").write_text(
    "# Matter 中心图形探索\n\n"
    "独立概念稿，保留当前 matter-c.svg 的圆环（半径 186、线宽 26、金色），未接入应用。\n\n"
    "- A 凝核：完整柔软轮廓，偏心孔形成识别细节，呼应 Matter 的物质感。可能产生石头、种子或花形联想。\n"
    "- B 聚合：三个圆润单元组成旋转结构。动感强，但容易让人想到风扇或循环符号。\n"
    "- C 连笔 M：将四段连接成连续字形，保持名称识别，同时增强整体感。\n\n"
    "matter-core-studies.svg 为对比板；每个方案另有带圆环和仅中心的可编辑 SVG。小尺寸行模拟当前 Android 前景缩放与裁切比例；不是设备截图。\n",
    encoding="utf-8",
)
