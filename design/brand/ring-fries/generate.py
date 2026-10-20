"""Generate Matter's editable ring + fries M design exploration."""
from pathlib import Path
from html import escape
import math
import re
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
INK = "#252A27"
GOLD = "#E6A52E"
PAPER = "#F8F5ED"
SELECTED = "c"
C_RING_WIDTH = 26
APP_ICON_SCALE = 0.86
ICON_BACKGROUND = "#FFFFFF"


def bar(x1, y1, x2, y2, width=36, color=GOLD):
    length = math.hypot(x2 - x1, y2 - y1)
    angle = math.degrees(math.atan2(y2 - y1, x2 - x1)) - 90
    return (
        f'<rect x="{-width / 2:g}" y="{-length / 2:.3f}" '
        f'width="{width}" height="{length:.3f}" rx="8" fill="{color}" '
        f'transform="translate({(x1+x2)/2:g} {(y1+y2)/2:g}) rotate({angle:.3f})"/>'
    )


def mark(variant="a", mono=None, dark=False):
    ink = mono or ("#F8F5ED" if dark else INK)
    gold = mono or GOLD
    ring_color = gold if variant == "c" else ink
    if variant == "b":
        ring = (
            f'<path d="M 415.081 159.202 A 186 186 0 1 1 352.798 96.919" '
            f'stroke="{ring_color}" stroke-width="16" stroke-linecap="round" fill="none"/>'
        )
    else:
        ring_width = C_RING_WIDTH if variant == "c" else 16
        ring = f'<circle cx="256" cy="256" r="186" stroke="{ring_color}" stroke-width="{ring_width}" fill="none"/>'
    fill = ink if variant == "c" else gold
    if variant == "c":
        left = bar(151, 184, 172, 330, 40, fill)
        right = bar(361, 184, 340, 330, 40, fill)
    else:
        left = '<rect x="143" y="180" width="40" height="148" rx="9" fill="' + fill + '"/>'
        right = '<rect x="329" y="180" width="40" height="148" rx="9" fill="' + fill + '"/>'
    fries = left + bar(211, 195, 235, 263, color=fill) + bar(277, 263, 301, 195, color=fill) + right
    transform = ' transform="rotate(-9 256 256)"' if variant == "b" else ""
    return f'<g id="ring">{ring}</g><g id="fries-m"{transform}>{fries}</g>'


def svg(content, width=512, height=512, title="Matter — ring & fries M"):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" fill="none">'
        f'<title>{escape(title)}</title>{content}</svg>'
    )


def write(name, content):
    (OUT / name).write_text(content, encoding="utf-8")


def text(x, y, value, size=18, fill=INK, weight=400):
    return (
        f'<text x="{x}" y="{y}" fill="{fill}" font-size="{size}" font-weight="{weight}" '
        f'font-family="Segoe UI, Microsoft YaHei, sans-serif">{escape(value)}</text>'
    )


def placed(content, x, y, size):
    return f'<g transform="translate({x} {y}) scale({size / 512:g})">{content}</g>'


for variant in ("a", "b", "c"):
    write(f"matter-{variant}.svg", svg(mark(variant)))
write("matter-mono.svg", svg(mark(SELECTED, mono=INK)))
write("matter-reversed.svg", svg(mark(SELECTED, dark=True)))
write("matter-app-icon.svg", svg(
    f'<rect width="512" height="512" rx="112" fill="{ICON_BACKGROUND}"/>'
    + f'<g transform="translate(256 256) scale({APP_ICON_SCALE}) translate(-256 -256)">{mark(SELECTED)}</g>'
))

board = f'<rect width="1600" height="1120" fill="{PAPER}"/>'
board += text(72, 75, "Matter", 48, weight=650)
board += text(74, 112, "圆环 × 薯条 × M", 20, "#6B7169")
board += text(1176, 76, "FORM STUDY / 01", 16, "#6B7169")
board += '<path d="M72 146H1528" stroke="#DCDDD3"/>'
titles = ["A / 聚合", "B / 轨迹", "C / 松弛 · 已选用"]
subtitles = ["完整圆环 / 四个单元 / 重心稳定", "开口圆环 / 微倾字形 / 更有动势", "金色圆环 / 向外舒展 / 更有玩心"]
for i, variant in enumerate(("a", "b", "c")):
    x = 72 + i * 496
    board += f'<rect x="{x}" y="180" width="464" height="480" rx="24" fill="#FFFFFF"/>'
    board += placed(mark(variant), x + 49, 184, 366)
    board += text(x + 30, 589, titles[i], 25, weight=600)
    board += text(x + 30, 625, subtitles[i], 17, "#6B7169")

board += text(72, 719, "C / 日常使用 · 圆环加粗至 26", 21, weight=600)
board += f'<rect x="72" y="750" width="464" height="282" rx="24" fill="{INK}"/>'
board += placed(mark(SELECTED, dark=True), 92, 775, 220)
board += text(324, 873, "Matter", 32, PAPER, 600)
board += text(326, 909, "深色背景", 17, "#B8C0B5")

board += '<rect x="568" y="750" width="464" height="282" rx="24" fill="#FFFFFF"/>'
board += placed(mark(SELECTED, mono=INK), 588, 775, 220)
board += text(820, 873, "单色", 26, weight=600)
board += text(820, 909, "形状本身可识别", 17, "#6B7169")

board += '<rect x="1064" y="750" width="464" height="282" rx="24" fill="#E9EADD"/>'
for x, y, size in [(1098, 809, 112), (1240, 837, 64), (1342, 853, 32)]:
    board += f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{size * .22}" fill="{PAPER}"/>'
    board += placed(mark(SELECTED), x, y, size)
    board += text(x, 957, f"{size} px", 15, "#6B7169")
board += text(1098, 790, "桌面图标与小尺寸", 17, "#6B7169")
board += text(72, 1083, "四根独立薯条构成 M；用留白连接字形，用圆环建立整体。", 19, "#6B7169")
write("matter-design-board.svg", svg(board, 1600, 1120, "Matter / Ring & fries design studies"))

write("README.md", """# Matter 圆环薯条 M

历史 C「松弛」方案（已由清新蓝凝核替代）：金色圆环，炭灰色薯条 M，纯白底。圆环线宽从 16 调整为 26，并在画板内整体内收，保留外侧两根向外舒展的排列。

- `matter-a.svg`：初始 A 方案，透明底。
- `matter-b.svg`：开口圆环，字形倾斜 9°。
- `matter-c.svg`：选用标志，加粗金色圆环，外侧两根向外舒展。
- `matter-mono.svg`：深色单色，透明底。
- `matter-reversed.svg`：浅色圆环与金色字形，适合深色底。
- `matter-app-icon.svg`：选用 C 版的纯白圆角底展示稿。
- `matter-design-board.svg`：三方案与使用场景对比。
- `generate.py`：上述矢量文件的几何源，可重复生成。

主标志使用 512 × 512 画板。圆环半径 186，C 版线宽 26（A、B 保留 16）；外侧条宽 40、内侧条宽 36。配色：炭灰 `#252A27`、暖金 `#E6A52E`、纯白 `#FFFFFF`（应用图标底）/ 暖白 `#F8F5ED`（展示板）。

全部标志由原生 SVG 几何图形组成，无嵌入位图、滤镜或字体依赖；对比板的说明文字使用系统字体。

Android 启动图标使用独立的前景矢量和纯白背景，API 33+ 额外提供单色主题图层；常规与圆形启动入口引用同一 adaptive icon。当前 minSdk 为 31，无需旧版位图回退。前景将主标志以中心为锚点缩放至 0.66，108 dp 图层中的外径约 55.4 dp，位于 66 dp 安全区内。圆角底 SVG 仅用于展示，Android 外形由启动器裁切。

`generate.py` 只在本目录的 `android-reference` 下导出旧方案参考资源。当前应用资源由 `../nucleus-palettes/export-android.py` 生成。`matter-launcher-preview.svg` 展示圆形、圆角方形及主题单色裁切。

尺寸依据：[Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)，核对于 2026-10-21。设备启动器实际显示与动态效果尚需真机确认。
""")

# Convert the selected SVG geometry into Android VectorDrawable paths.
# Both formats share the same shapes and rotations; no raster assets are used.
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
RES = OUT / "android-reference"  # Historical study; never overwrite the selected app icon.


def attrs(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def rounded_rect_path(a):
    x, y, w, h, r = (float(a[k]) for k in ("x", "y", "width", "height", "rx"))
    return (
        f"M{x+r:g},{y:g} H{x+w-r:g} A{r:g},{r:g} 0,0 1,{x+w:g},{y+r:g} "
        f"V{y+h-r:g} A{r:g},{r:g} 0,0 1,{x+w-r:g},{y+h:g} "
        f"H{x+r:g} A{r:g},{r:g} 0,0 1,{x:g},{y+h-r:g} "
        f"V{y+r:g} A{r:g},{r:g} 0,0 1,{x+r:g},{y:g} Z"
    )


def vector(mono=None):
    root = ET.Element("vector", attrs(width="108dp", height="108dp", viewportWidth=512, viewportHeight=512))
    layer = ET.SubElement(root, "group", attrs(pivotX=256, pivotY=256, scaleX=0.66, scaleY=0.66))
    source = ET.fromstring(svg(mark(SELECTED, mono=mono)))
    for element in source.iter():
        kind = element.tag.rsplit("}", 1)[-1]
        if kind == "circle":
            ET.SubElement(layer, "path", attrs(
                pathData="M442,256 A186,186 0,1 0,70,256 A186,186 0,1 0,442,256 Z",
                fillColor="#00000000", strokeColor=element.attrib["stroke"], strokeWidth=C_RING_WIDTH,
            ))
        elif kind == "rect":
            a = element.attrib
            tx, ty, angle = map(float, re.fullmatch(r"translate\(([-\d.]+) ([-\d.]+)\) rotate\(([-\d.]+)\)", a["transform"]).groups())
            group = ET.SubElement(layer, "group", attrs(translateX=tx, translateY=ty, rotation=angle))
            ET.SubElement(group, "path", attrs(pathData=rounded_rect_path(a), fillColor=a["fill"]))
    return root


def write_xml(relative, element):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(element, space="    ")
    ET.ElementTree(element).write(path, encoding="utf-8", xml_declaration=True)


write_xml("drawable/ic_launcher_foreground.xml", vector())
write_xml("drawable/ic_launcher_monochrome.xml", vector(mono="#FFFFFF"))
colors = ET.Element("resources")
ET.SubElement(colors, "color", name="ic_launcher_background").text = ICON_BACKGROUND
write_xml("values/ic_launcher_colors.xml", colors)
for version in (26, 33):
    adaptive = ET.Element("adaptive-icon")
    ET.SubElement(adaptive, "background", attrs(drawable="@color/ic_launcher_background"))
    ET.SubElement(adaptive, "foreground", attrs(drawable="@drawable/ic_launcher_foreground"))
    if version == 33:
        ET.SubElement(adaptive, "monochrome", attrs(drawable="@drawable/ic_launcher_monochrome"))
    write_xml(f"mipmap-anydpi-v{version}/ic_launcher.xml", adaptive)


def vector_as_svg(element):
    """Preview the generated Android paths, including their actual transforms."""
    a = {key.rsplit("}", 1)[-1]: value for key, value in element.attrib.items()}
    if element.tag == "path":
        return (f'<path d="{a["pathData"]}" fill="{a["fillColor"]}" '
                f'stroke="{a.get("strokeColor", "none")}" stroke-width="{a.get("strokeWidth", 0)}"/>')
    inner = "".join(vector_as_svg(child) for child in element)
    if element.tag == "vector":
        return inner
    px, py = a.get("pivotX", 0), a.get("pivotY", 0)
    return (
        f'<g transform="translate({a.get("translateX", 0)} {a.get("translateY", 0)}) '
        f'translate({px} {py}) rotate({a.get("rotation", 0)}) '
        f'scale({a.get("scaleX", 1)} {a.get("scaleY", 1)}) translate({-float(px)} {-float(py)})">{inner}</g>'
    )


preview = '<rect width="1200" height="490" fill="#E9EADD"/>'
preview += text(48, 56, "Matter / C · 松弛", 30, weight=600)
preview += text(48, 92, "金色圆环 16 → 26 · Android 启动器裁切预览", 18, "#6B7169")
for index, label in enumerate(("圆角方形", "圆形", "主题单色")):
    x, y, size = 48 + index * 390, 140, 288
    shape = f'<rect width="{size}" height="{size}" rx="64"/>' if index == 0 else f'<circle cx="144" cy="144" r="144"/>'
    preview += f'<defs><clipPath id="mask{index}">{shape}</clipPath></defs>'
    preview += f'<g transform="translate({x} {y})"><g clip-path="url(#mask{index})">'
    preview += f'<rect width="288" height="288" fill="{ICON_BACKGROUND if index < 2 else "#DCE6CB"}"/>'
    drawable = ET.parse(RES / f'drawable/ic_launcher_{"foreground" if index < 2 else "monochrome"}.xml').getroot()
    content = vector_as_svg(drawable)
    if index == 2:
        content = content.replace("#FFFFFF", "#34402A")
    preview += f'<svg width="288" height="288" viewBox="85.333333 85.333333 341.333333 341.333333">{content}</svg></g></g>'
    preview += text(x, 466, label, 19, "#6B7169")
write("matter-launcher-preview.svg", svg(preview, 1200, 490, "Matter C / Android launcher masks"))
