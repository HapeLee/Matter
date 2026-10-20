"""Three-tone vector studies. Preserve the current ring and app resources."""
from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
BRAND = OUT.parent
NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
ring_source = ET.parse(BRAND / "ring-fries/matter-c.svg").getroot()
RING = ET.tostring(ring_source.find(f"{NS}g[@id='ring']"), encoding="unicode")
LIGHT, MID, SHADE = "#E5D1AB", "#C8AC79", "#A58B60"


def svg(body, width=512, height=512):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}"><title>Matter / Three-tone studies</title>{body}</svg>'


def place(body, x, y, size):
    return f'<g transform="translate({x} {y}) scale({size / 512})">{body}</g>'


def text(x, y, value, size=18, color="#45433D", weight=400):
    return f'<text x="{x}" y="{y}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="{size}" font-weight="{weight}" fill="{color}">{escape(value)}</text>'


nucleus_source = ET.parse(BRAND / "core-studies/matter-nucleus-core.svg").getroot()
outline = next(nucleus_source.iter(f"{NS}path")).attrib["d"]
# The clip includes the opening, so all tones retain a genuinely transparent hole.
nucleus = (
    f'<defs><clipPath id="nucleus-clip"><path d="{outline}" clip-rule="evenodd"/></clipPath></defs>'
    f'<g clip-path="url(#nucleus-clip)">'
    f'<path d="{outline}" fill="{MID}" fill-rule="evenodd"/>'
    f'<path d="M120 130H390V192 C320 183 298 203 278 228 C250 265 201 278 136 267 Z" fill="{LIGHT}"/>'
    f'<path d="M376 222 C341 259 314 298 289 319 C261 343 221 347 181 334 L180 390H390Z" fill="{SHADE}"/>'
    '</g>'
)

aggregate_source = ET.parse(BRAND / "core-studies/matter-aggregate-core.svg").getroot()
aggregate_group = aggregate_source.find(f"{NS}g")
aggregate_group.attrib.pop("fill", None)
for element, color in zip(aggregate_group, (LIGHT, MID, SHADE)):
    element.set("fill", color)
aggregate = ET.tostring(aggregate_group, encoding="unicode")

joined_source = ET.parse(BRAND / "core-studies/matter-joined-core.svg").getroot()
joined_path = next(joined_source.iter(f"{NS}path"))
joined_path.set("stroke", MID)
joined = (
    ET.tostring(joined_path, encoding="unicode")
    + f'<path d="M165 325V199 Q165 182 181 182 Q189 182 197 194L256 278" fill="none" stroke="{LIGHT}" stroke-width="42" stroke-linecap="round" stroke-linejoin="round"/>'
    + f'<path d="M331 182Q347 182 347 199V325" fill="none" stroke="{SHADE}" stroke-width="42" stroke-linecap="round"/>'
)

concepts = [
    ("nucleus", "A / 柔光凝核", "用三个弧面表达受光、主体与背光", nucleus),
    ("aggregate", "B / 砂金聚合", "三个单元逐级变暗，形成轻微旋转感", aggregate),
    ("joined", "C / 折带 M", "明暗随笔画转折，像一条折起的带子", joined),
]
board = '<rect width="1440" height="900" fill="#F4F2ED"/>'
board += text(56, 73, "Matter / 用明暗表达形状", 38, weight=600)
board += text(58, 116, "砂金色系 · 三档平涂 · 保留现有金色圆环", 21, "#7E786C")
for i, (slug, title, note, core) in enumerate(concepts):
    x = 48 + i * 456
    board += f'<rect x="{x}" y="156" width="432" height="654" rx="26" fill="white"/>'
    board += place(RING + core, x + 16, 166, 400)
    board += text(x + 28, 593, title, 27, weight=600)
    board += text(x + 28, 632, note, 18, "#7E786C")
    board += f'<path d="M{x+28} 660H{x+404}" stroke="#EEEAE1"/>'
    for k, color in enumerate((LIGHT, MID, SHADE)):
        board += f'<rect x="{x+28+k*34}" y="701" width="26" height="26" rx="8" fill="{color}"/>'
    board += text(x + 28, 770, "同色系明暗", 15, "#7E786C")
    # Distinct clip ids keep repeated SVG instances independent.
    for k, size in enumerate((64, 48, 32)):
        small_core = core.replace("nucleus-clip", f"small-{i}-{k}")
        board += place(RING + small_core, x + 203 + k*68, 687 + (64-size)/2, size)
    board += text(x + 207, 770, "64 / 48 / 32 px", 15, "#7E786C")
    (OUT / f"matter-{slug}.svg").write_text(svg(RING + core), encoding="utf-8")
    (OUT / f"matter-{slug}-icon.svg").write_text(
        svg('<rect width="512" height="512" rx="112" fill="white"/>' +
            f'<g transform="translate(256 256) scale(0.99) translate(-256 -256)">{RING}{core}</g>'),
        encoding="utf-8",
    )
board += text(56, 865, "圆环负责清晰边界，中心通过大面积浅色与少量背光面形成体积。", 21, "#7E786C")
(OUT / "matter-tonal-studies.svg").write_text(svg(board, 1440, 900), encoding="utf-8")
(OUT / "README.md").write_text(
    "# Matter 三档明暗方案\n\n"
    "保留现有金色圆环（半径 186、线宽 26）。中心采用砂金色系：受光 #E5D1AB、主体 #C8AC79、背光 #A58B60。全部是矢量平涂，不依赖渐变、阴影滤镜或位图。\n\n"
    "A 通过三块连续弧面塑造柔软实体；B 将明暗分配给三个聚合单元；C 将明暗分配给连笔 M 的转折。A 的中心开孔保持透明。\n\n"
    "每个方案都有透明底 SVG 和白底图标展示稿。概念稿尚未接入 Android；小尺寸为静态矢量渲染检查。\n",
    encoding="utf-8",
)
