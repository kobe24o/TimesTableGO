"""Prepare bundled offline resources. macOS afconvert or ffmpeg required.
Pass --model with a downloaded original when Git LFS is unavailable.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--model', type=Path)
args = p.parse_args()
source = ROOT / 'app/src/main/assets/asr'
target = ROOT / 'flutter_app/assets/asr'
manifest = json.loads((source / 'manifest.json').read_text())
for item in manifest['files']:
    src = args.model if item['path'] == 'sensevoice/model.int8.onnx' and args.model else source / item['path']
    if hashlib.sha256(src.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit(f"Resource hash mismatch (fetch Git LFS): {item['path']}")
    dst = target / item['path']
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)
shutil.copyfile(source / 'manifest.json', target / 'manifest.json')
audio = ROOT / 'flutter_app/assets/audio'
audio.mkdir(parents=True, exist_ok=True)
for suffix in ['', '_en']:
    names = [name for a in range(1, 10) for b in range(1, 10) for name in (f'prompt_{a}_{b}{suffix}', f'correction_{a}_{b}{suffix}')]
    names += [f'feedback_{f}{suffix}' for f in ('correct', 'incorrect', 'listen', 'no_speech')]
    for name in names:
        src = ROOT / f'app/src/main/res/raw/{name}.ogg'
        dst = audio / f'{name}.wav'
        if shutil.which('ffmpeg'):
            command = ['ffmpeg', '-v', 'error', '-y', '-i', str(src), '-ac', '1', '-ar', '22050', '-c:a', 'pcm_s16le', str(dst)]
        else:
            command = ['afconvert', '-f', 'WAVE', '-d', 'LEI16', str(src), str(dst)]
        subprocess.run(command, check=True, capture_output=True)
licenses = ROOT / 'flutter_app/assets/licenses'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copyfile(ROOT / 'app/src/main/assets/licenses/cosyvoice-notice.txt', licenses / 'cosyvoice-notice.txt')
print('Prepared 332 WAV recordings, verified models and licenses.')
