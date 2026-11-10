"""
把键盘用的 SVG 线条图标转成 Android VectorDrawable，写到 app/src/main/res/drawable/ic_*.xml。

用法：python -I tools/make_icons.py
线条图标统一 24×24 视口、1.8 线宽、圆头；颜色写成白色，绘制时用 setTint 染成皮肤色。
"""
import os
import re

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'drawable')

# 名字 → SVG 元素。fill="c" 表示实心（白色，可染色）；fill="#RRGGBB" 表示固定颜色
ICONS = {
    'shift': '<path d="M12 4.5 4.5 12H9v6.5h6V12h4.5z"/>',
    'shift_on': '<path fill="c" d="M12 4.5 4.5 12H9v6.5h6V12h4.5z"/>',
    'shift_lock': '<path fill="c" d="M12 3.5 4.5 11H9v5h6v-5h4.5z"/><path d="M8.5 20h7" stroke-width="2.2"/>',
    'del': '<path d="M9.2 5.5H19a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H9.2L3.5 12z"/><path d="m11.6 9.4 5.2 5.2m0-5.2-5.2 5.2"/>',
    'enter': '<path d="M19.5 5.5v6a3 3 0 0 1-3 3h-12"/><path d="m8.5 10.5-4 4 4 4"/>',
    'search': '<circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 5 5"/>',
    'send': '<path d="M20.5 3.5 3.5 10.6l6.9 2.6 2.7 7.3z"/><path d="m10.4 13.2 4.6-4.6"/>',
    'go': '<path d="M4.5 12h15M13.5 6l6 6-6 6"/>',
    'next': '<path d="M4 12h12.5M11.5 7l5 5-5 5M20 6v12"/>',
    'done': '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    'globe': '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.4 3.6 5.2 3.6 8.5s-1.2 6.1-3.6 8.5c-2.4-2.4-3.6-5.2-3.6-8.5s1.2-6.1 3.6-8.5z"/>',
    'emoji': '<circle cx="12" cy="12" r="8.5"/><path d="M8.3 13.8a4.3 4.3 0 0 0 7.4 0"/><path d="M9.3 9.6v.4M14.7 9.6v.4" stroke-width="2.4"/>',
    'mic': '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
    'grid': '<rect x="4.5" y="4.5" width="6" height="6" rx="1.5"/><rect x="13.5" y="4.5" width="6" height="6" rx="1.5"/><rect x="4.5" y="13.5" width="6" height="6" rx="1.5"/><rect x="13.5" y="13.5" width="6" height="6" rx="1.5"/>',
    'cursor': '<path d="M12 5v14M9.5 5h5M9.5 19h5M6 9.5 3.5 12 6 14.5M18 9.5l2.5 2.5-2.5 2.5"/>',
    'clip': '<rect x="5.5" y="5" width="13" height="15.5" rx="2"/><path d="M9 5V3.5h6V5M8.8 10.5h6.4M8.8 14h4.2"/>',
    'palette': '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.2 0 1.8-.8 1.8-1.7 0-1.1-1-1.4-1-2.4 0-.9.7-1.4 1.6-1.4h1.9a4.2 4.2 0 0 0 4.2-4.2c0-4.1-3.8-7.3-8.5-7.3z"/><path d="M7.8 11.5h.01M10 7.8h.01M14.2 7.8h.01" stroke-width="2.4"/>',
    'kbd': '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h.01M10.3 10h.01M13.7 10h.01M17 10h.01M8 14.5h8" stroke-width="2"/>',
    'chevron_down': '<path d="m6.5 9.5 5.5 5.5 5.5-5.5"/>',
    'close': '<path d="m7 7 10 10M17 7 7 17"/>',
    'undo': '<path d="M9 14 4.5 9.5 9 5"/><path d="M4.5 9.5H14a5.5 5.5 0 0 1 0 11h-3"/>',
    'redo': '<path d="m15 14 4.5-4.5L15 5"/><path d="M19.5 9.5H10a5.5 5.5 0 0 0 0 11h3"/>',
    'select_all': '<path d="M4 7V4h3M17 4h3v3M20 17v3h-3M7 20H4v-3M10.5 4h3M10.5 20h3M4 10.5v3M20 10.5v3"/><rect x="8.5" y="8.5" width="7" height="7" rx="1"/>',
    'adjust': '<path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/>',
    'toolbar': '<rect x="3.5" y="4.5" width="17" height="6" rx="1.5"/><path d="M8 16h5M15.5 13.5v5M13 16h5"/>',
    'float': '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><rect x="9" y="11" width="9" height="6" rx="1.2"/>',
    'handwrite': '<path d="M4 16c2-6 4-9 5.5-9S9 12 11 12s3-5 4.5-5 1 6 4.5 7"/><path d="M4 20h16"/>',
    # 1.3.0：数字键盘、九键、剪贴板长按菜单用的图标
    'symbol': '<circle cx="7.5" cy="7.5" r="2.5"/><circle cx="16.5" cy="16.5" r="2.5"/><path d="M18.5 5.5 5.5 18.5"/>',
    'back': '<path d="M19.5 12h-15M10.5 6l-6 6 6 6"/>',
    'space': '<path d="M4 10v4.5h16V10"/>',
    'reset': '<path d="M9 5.7a7 7 0 1 0 6.2-.1"/><path fill="c" d="M9 2v7l4-3.5z"/>',
    'split_layout': '<path fill="c" d="M3 4h5v2H3zM3 8h5v2H3zM3 12h5v2H3zM3 16h5v2H3zM3 20h5v1H3zM11 4h10v17H11z"/>',
    'refresh': '<path d="M19 12a7 7 0 1 1-.94-3.5"/><path d="M18.06 4v4.5h-4.5"/>',
    'split': '<rect x="3.5" y="7" width="7" height="10" rx="1.5"/><rect x="13.5" y="7" width="7" height="10" rx="1.5"/><path d="M12 4v2M12 18v2"/>',
    'tag': '<path d="M3.5 12.2V4.5a1 1 0 0 1 1-1h7.7l8.3 8.3-8.7 8.7z"/><path d="M8 8h.01" stroke-width="2.6"/>',
    'download': '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19.5h14"/>',
    # 1.5.0：手写重写、收起键盘、分词插入
    'eraser': '<path d="m14.5 4.5 5 5-9 9H6l-2.5-2.5a1.5 1.5 0 0 1 0-2.1z"/><path d="m9.5 9.5 5 5M10.5 18.5H20"/>',
    'kbd_hide': '<rect x="3" y="3.5" width="18" height="11" rx="2"/><path d="M7 7.5h.01M10.3 7.5h.01M13.7 7.5h.01M17 7.5h.01M8 11h8" stroke-width="1.9"/><path d="m9 18 3 3 3-3"/>',
    'insert': '<path d="M4 6h16M4 18h8"/><path d="M12 9.5v5M9.5 12h5"/><path d="M17 15v5M14.5 17.5 17 20l2.5-2.5"/>',
    'translate': '<path d="M4 6h8M8 4v2M10.5 6c-.6 3.6-2.8 6.4-6 8M6 9c1 2 2.6 3.6 4.6 4.6"/><path d="m12.5 20 3.8-9 3.8 9M13.8 17h5"/>',
    'clip_stack': '<rect x="8" y="7.5" width="12" height="13" rx="2"/><path d="M16 7.5V5.5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2V15a2 2 0 0 0 2 2h2M11 12h6M11 15.5h4"/>',
    'pin': '<path d="M9 4h6l-1 5 3 3v1.5H7V12l3-3z"/><path d="M12 13.5V20"/>',
    'edit': '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/>',
    'chevron_up': '<path d="m6.5 14.5 5.5-5.5 5.5 5.5"/>',
    'chevron_left': '<path d="m14.5 6.5-5.5 5.5 5.5 5.5"/>',
    'chevron_right': '<path d="m9.5 6.5 5.5 5.5-5.5 5.5"/>',
    'home': '<path d="M5 5.5v13M19 12H9M13.5 7.5 9 12l4.5 4.5"/>',
    'end': '<path d="M19 5.5v13M5 12h10M10.5 7.5 15 12l-4.5 4.5"/>',
    'select': '<path d="M12 5v14M9.5 5h5M9.5 19h5"/><path d="M4 9h3v6H4zM17 9h3v6h-3z" stroke-width="1.5"/>',
    'copy': '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M15.5 8.5V6.5a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2"/>',
    'cut': '<circle cx="7" cy="17" r="2.6"/><circle cx="17" cy="17" r="2.6"/><path d="M8.8 15.2 17 4.5M15.2 15.2 7 4.5"/>',
    'paste': '<rect x="5.5" y="5" width="13" height="15.5" rx="2"/><path d="M9 5V3.5h6V5M12 9.5v6M9.5 13l2.5 2.5 2.5-2.5"/>',
    'trash': '<path d="M5 7h14M10 7V5h4v2M7 7l.8 12a1.5 1.5 0 0 0 1.5 1.4h5.4a1.5 1.5 0 0 0 1.5-1.4L17 7"/>',
    # 齿轮（参考 Lucide settings 图标，ISC 许可）
    'settings': '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    'lock_open': '<rect x="5" y="11" width="14" height="9.5" rx="2"/><path d="M8.5 11V7.5a3.5 3.5 0 0 1 6.8-1.2"/><path d="M12 14.5v2.5"/>',
    'leaf': '<path fill="c" d="M4.5 19.5C3.6 10.4 9.2 4.5 19.8 4.2c.2 10.4-5.4 16-15.3 15.3z"/>',
    'cat_day': ('<path fill="#1B1B1B" d="M5.3 10.6 4.6 4.4c0-.6.6-.8 1-.4l3.6 3.4zM18.7 10.6l.7-6.2c0-.6-.6-.8-1-.4l-3.6 3.4z"/>'
                '<path fill="#F0492C" d="M12 6.4c0-2.2 2.1-3.4 3.6-2.5-.6 1.8-2 2.5-3.6 2.5z"/>'
                '<ellipse fill="#1B1B1B" cx="12" cy="13.4" rx="8.4" ry="6.9"/>'
                '<circle fill="#FFFFFF" cx="8.6" cy="13.6" r="2.5"/><circle fill="#FFFFFF" cx="15.4" cy="13.6" r="2.5"/>'
                '<circle fill="#1B1B1B" cx="8.6" cy="13.6" r="1.15"/><circle fill="#1B1B1B" cx="15.4" cy="13.6" r="1.15"/>'),
    'cat_night': ('<path fill="#F2F2F2" d="M5.3 10.6 4.6 4.4c0-.6.6-.8 1-.4l3.6 3.4zM18.7 10.6l.7-6.2c0-.6-.6-.8-1-.4l-3.6 3.4z"/>'
                  '<path fill="#FF5A3C" d="M12 6.4c0-2.2 2.1-3.4 3.6-2.5-.6 1.8-2 2.5-3.6 2.5z"/>'
                  '<ellipse fill="#F2F2F2" cx="12" cy="13.4" rx="8.4" ry="6.9"/>'
                  '<circle fill="#161617" cx="8.6" cy="13.6" r="2.5"/><circle fill="#161617" cx="15.4" cy="13.6" r="2.5"/>'
                  '<circle fill="#F2F2F2" cx="8.6" cy="13.6" r="1.15"/><circle fill="#F2F2F2" cx="15.4" cy="13.6" r="1.15"/>'),
}

ATTR = re.compile(r'([\w-]+)="([^"]*)"')
ELEM = re.compile(r'<(path|circle|rect|ellipse)\s+([^>]*?)/>')


def num(v):
    return float(v)


def fmt(x):
    s = f'{x:.3f}'.rstrip('0').rstrip('.')
    return s if s not in ('-0', '') else '0'


def to_path(tag, a):
    if tag == 'path':
        return a['d']
    if tag == 'circle':
        cx, cy, r = num(a['cx']), num(a['cy']), num(a['r'])
        return f'M{fmt(cx - r)},{fmt(cy)}a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0z'
    if tag == 'ellipse':
        cx, cy, rx, ry = num(a['cx']), num(a['cy']), num(a['rx']), num(a['ry'])
        return f'M{fmt(cx - rx)},{fmt(cy)}a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(2 * rx)},0a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(-2 * rx)},0z'
    if tag == 'rect':
        x, y, w, h = num(a['x']), num(a['y']), num(a['width']), num(a['height'])
        r = min(num(a.get('rx', '0')), w / 2, h / 2)
        if r <= 0:
            return f'M{fmt(x)},{fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z'
        return (f'M{fmt(x + r)},{fmt(y)}h{fmt(w - 2 * r)}a{fmt(r)},{fmt(r)} 0 0,1 {fmt(r)},{fmt(r)}'
                f'v{fmt(h - 2 * r)}a{fmt(r)},{fmt(r)} 0 0,1 {fmt(-r)},{fmt(r)}'
                f'h{fmt(-(w - 2 * r))}a{fmt(r)},{fmt(r)} 0 0,1 {fmt(-r)},{fmt(-r)}'
                f'v{fmt(-(h - 2 * r))}a{fmt(r)},{fmt(r)} 0 0,1 {fmt(r)},{fmt(-r)}z')
    raise ValueError(tag)


def vector(svg):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- 由 tools/make_icons.py 生成，不要手改 -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp"',
           '    android:viewportWidth="24" android:viewportHeight="24">']
    for tag, attrs in ELEM.findall(svg):
        a = dict(ATTR.findall(attrs))
        d = to_path(tag, a)
        fill = a.get('fill')
        if fill:
            color = '#FFFFFFFF' if fill == 'c' else '#FF' + fill.lstrip('#').upper()
            out.append(f'    <path android:fillColor="{color}" android:pathData="{d}"/>')
        else:
            sw = a.get('stroke-width', '1.8')
            out.append(f'    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="{sw}"'
                       f' android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}"/>')
    out.append('</vector>')
    return '\n'.join(out) + '\n'


os.makedirs(OUT, exist_ok=True)
for name, svg in ICONS.items():
    with open(os.path.join(OUT, f'ic_{name}.xml'), 'w', encoding='utf8', newline='\n') as fp:
        fp.write(vector(svg))
print(len(ICONS), 'icons')
