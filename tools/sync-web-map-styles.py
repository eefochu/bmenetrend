#!/usr/bin/env python3
"""Copy the sibling web app's detailed street styles for MapLibre Native.

Keeps every road/land-cover/label layer and zoom expression. Browser-only
font-faces are omitted (Native uses the style's Noto glyph endpoint); custom POI
symbols use bundled Android badges, with the same fallback as style-images.ts.
"""
import argparse
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--web-root', type=Path, default=root.parent / 'bmenetrend-web')
args = parser.parse_args()
assets = root / 'app/src/main/assets/map'
glyphs = json.loads((assets / 'web-poi-glyphs.json').read_text())
for theme in ('light', 'dark'):
    source = args.web_root / 'public/map' / f'google-{theme}.json'
    raw = source.read_bytes()
    style = json.loads(raw)
    style.pop('font-faces', None)
    images = style['metadata']['bmenetrend:images']['poi']
    missing = {entry[1] for entry in images.values()} - glyphs.keys()
    if missing:
        raise SystemExit('Bundled map symbol font needs regeneration: ' + ', '.join(sorted(missing)))
    known = sorted(name for name in images if name != '*')
    for layer in style['layers']:
        layout = layer.get('layout', {})
        # The web maps its Italic stack to upright Google Sans; keep that shape in Native.
        if layout.get('text-font') == ['Noto Sans Italic']:
            layout['text-font'] = ['Noto Sans Regular']
        if str(layout.get('icon-image', '')).find('bm-poi:') >= 0:
            category = ['to-string', ['get', 'class']]
            layout['icon-image'] = ['concat', 'bm-poi:', ['match', category, known, category, '*']]
    style['metadata']['bmenetrend:web-source-sha256'] = hashlib.sha256(raw).hexdigest()
    (assets / f'web-{theme}.json').write_text(json.dumps(style, ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f'{theme}: {len(style["layers"])} web layers copied, {len(images)} native badge images')
