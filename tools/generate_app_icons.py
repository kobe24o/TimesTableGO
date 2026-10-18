"""Resize the approved master icon for both app shells (requires Pillow)."""
from PIL import Image
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
image=Image.open(root/'design/timestablego-icon.png').convert('RGB')
icons=root/'flutter_app/ios/Runner/Assets.xcassets/AppIcon.appiconset'
for item in json.loads((icons/'Contents.json').read_text())['images']:
    if 'filename' not in item: continue
    n=round(float(item['size'].split('x')[0])*float(item['scale'].rstrip('x')))
    image.resize((n,n),Image.Resampling.LANCZOS).save(icons/item['filename'])
for density,n in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    image.resize((n,n),Image.Resampling.LANCZOS).save(root/f'flutter_app/android/app/src/main/res/mipmap-{density}/ic_launcher.png')
launch=root/'flutter_app/ios/Runner/Assets.xcassets/LaunchImage.imageset'
for scale in (1,2,3):
    filename='LaunchImage'+('' if scale==1 else f'@{scale}x')+'.png'
    image.resize((128*scale,128*scale),Image.Resampling.LANCZOS).save(launch/filename)
print('Updated iOS and Android icons and launch image.')
