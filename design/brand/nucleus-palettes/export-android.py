"""Export the selected fresh-blue SVG to Android's adaptive icon layers."""
from pathlib import Path
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
RES = OUT.parents[2] / 'app/src/main/res'
SVG = '{http://www.w3.org/2000/svg}'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
source = ET.parse(OUT / 'matter-fresh-blue.svg').getroot()
geometry = source.find(SVG+'g')
assert geometry.attrib['transform'] == 'translate(256 256) scale(1.45) translate(-255 -254.5)'
clip = geometry.find('.//'+SVG+'clipPath/'+SVG+'path').attrib['d']
paint = geometry.find(SVG+'g')


def attrs(**kwargs):
    return {f'{{{ANDROID}}}{key}': str(value) for key, value in kwargs.items()}


def vector(monochrome=False):
    root = ET.Element('vector', attrs(width='108dp', height='108dp', viewportWidth=512, viewportHeight=512))
    # 108 dp layers are cropped to the nominal central 72 dp launcher viewport.
    # Compensate by 2/3 so the displayed size matches the approved 512 px SVG.
    group = ET.SubElement(root, 'group', attrs(pivotX=255, pivotY=254.5,
        translateX=1, translateY=1.5, scaleX=1.45*2/3, scaleY=1.45*2/3))
    if monochrome:
        ET.SubElement(group, 'path', attrs(pathData=clip, fillType='evenOdd', fillColor='#FFFFFF'))
    else:
        ET.SubElement(group, 'clip-path', attrs(pathData=clip, fillType='evenOdd'))
        for shape in paint:
            ET.SubElement(group, 'path', attrs(pathData=shape.attrib['d'],
                fillColor=shape.attrib['fill'], fillType='evenOdd' if shape.attrib.get('fill-rule') == 'evenodd' else 'nonZero'))
    return root


def save(path, element):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(element, space='    ')
    ET.ElementTree(element).write(path, encoding='utf-8', xml_declaration=True)


save(RES/'drawable/ic_launcher_foreground.xml', vector())
save(RES/'drawable/ic_launcher_monochrome.xml', vector(True))
colors = ET.Element('resources')
ET.SubElement(colors, 'color', name='ic_launcher_background').text = '#FFFFFF'
save(RES/'values/ic_launcher_colors.xml', colors)

# Static previews use the actual exported VectorDrawable path data and transforms.
def preview_content(root, identity, tint=None):
    group = root.find('group')
    a = {key.split('}')[-1]: value for key, value in group.attrib.items()}
    content = ''
    for shape in group:
        s = {key.split('}')[-1]: value for key, value in shape.attrib.items()}
        if shape.tag == 'clip-path':
            content += f'<defs><clipPath id="{identity}"><path d="{s["pathData"]}" clip-rule="evenodd"/></clipPath></defs>'
            continue
        clip_attr = f' clip-path="url(#{identity})"' if group.find('clip-path') is not None else ''
        rule = 'evenodd' if s.get('fillType') == 'evenOdd' else 'nonzero'
        content += f'<path d="{s["pathData"]}" fill="{tint or s["fillColor"]}" fill-rule="{rule}"{clip_attr}/>'
    return (f'<g transform="translate({a["translateX"]} {a["translateY"]}) translate({a["pivotX"]} {a["pivotY"]}) '
            f'scale({a["scaleX"]} {a["scaleY"]}) translate(-{a["pivotX"]} -{a["pivotY"]})">{content}</g>')


board = '<rect width="1080" height="430" fill="#F0F4F8"/>'
board += '<text x="40" y="54" font-size="28" font-family="Microsoft YaHei, sans-serif" fill="#354252">Matter / 清新蓝 · 应用图标</text>'
for i, label in enumerate(('圆角方形', '圆形', '主题单色')):
    x = 48+i*352
    shape = '<rect width="280" height="280" rx="62"/>' if i == 0 else '<circle cx="140" cy="140" r="140"/>'
    board += f'<defs><clipPath id="mask-{i}">{shape}</clipPath></defs>'
    board += f'<g transform="translate({x} 88)"><g clip-path="url(#mask-{i})">'
    board += f'<rect width="280" height="280" fill="{"#DDE8F3" if i == 2 else "#FFFFFF"}"/>'
    root = ET.parse(RES/f'drawable/ic_launcher_{"monochrome" if i == 2 else "foreground"}.xml').getroot()
    content = preview_content(root, f'core-{i}', '#536F8E' if i == 2 else None)
    board += f'<svg width="280" height="280" viewBox="85.333333 85.333333 341.333333 341.333333">{content}</svg></g></g>'
    board += f'<text x="{x}" y="407" font-size="18" font-family="Microsoft YaHei, sans-serif" fill="#667789">{label}</text>'
(OUT/'matter-launcher-preview.svg').write_text(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="430" viewBox="0 0 1080 430">{board}</svg>', encoding='utf-8')
