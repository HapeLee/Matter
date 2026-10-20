"""A slightly brighter, fresher blue revision of the selected ring-free mark."""
from pathlib import Path
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
NS = '{http://www.w3.org/2000/svg}'
ET.register_namespace('', NS[1:-1])
original = ET.parse(OUT / 'matter-mist-blue.svg').getroot()
before = ''.join(ET.tostring(c, encoding='unicode') for c in original if c.tag != NS+'title')
after = before.replace('#B9C9DD', '#B4D1EB').replace('#7E96B4', '#82AFD6').replace('#536B88', '#567FA9')
after = after.replace('mark-mist-blue', 'mark-fresh-blue')


def svg(body, w=512, h=512):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><title>Matter / Fresh blue</title>{body}</svg>'


def label(x, y, value, size=20, color='#536477'):
    return f'<text x="{x}" y="{y}" fill="{color}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="{size}">{value}</text>'


(OUT / 'matter-fresh-blue.svg').write_text(svg(after), encoding='utf-8')
(OUT / 'matter-fresh-blue-icon.svg').write_text(svg('<rect width="512" height="512" rx="112" fill="white"/>'+after), encoding='utf-8')
board = '<rect width="1000" height="670" fill="#F1F5F9"/>'
board += label(48, 61, 'Matter / 雾蓝微调', 32, '#354252')
board += label(48, 102, '蓝色更清透一点，保留柔和的三层弧面。')
for x, content, name in [(40, before, '原版 / 雾蓝'), (520, after, '调整后 / 更清新')]:
    board += f'<rect x="{x}" y="138" width="440" height="476" rx="28" fill="white"/>'
    board += f'<g transform="translate({x+20} 146) scale({400/512})">{content}</g>'
    board += label(x+28, 580, name, 23)
board += label(48, 650, '受光 #B4D1EB    主体 #82AFD6    背光 #567FA9', 16)
(OUT / 'matter-blue-refinement.svg').write_text(svg(board, 1000, 670), encoding='utf-8')
