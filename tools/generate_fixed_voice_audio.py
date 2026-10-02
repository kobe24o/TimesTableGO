"""Generate the app's fixed Chinese prompt audio with a local CosyVoice model."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import types
from pathlib import Path

import imageio_ffmpeg
import torch
import torchaudio


def read_lines(path: Path) -> list[tuple[str, str]]:
    entries: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        try:
            resource_name, text = line.split("\t", maxsplit=1)
        except ValueError as error:
            raise ValueError(f"Invalid voice line: {line!r}") from error
        if resource_name in seen or not resource_name.replace("_", "").isalnum():
            raise ValueError(f"Invalid or duplicate resource name: {resource_name}")
        seen.add(resource_name)
        entries.append((resource_name, text))
    if len(entries) != 85:
        raise ValueError(f"Expected 85 fixed voice lines, got {len(entries)}")
    return entries


def correction_entries() -> list[tuple[str, str]]:
    numerals = {
        1: "一", 2: "二", 3: "三", 4: "四", 5: "五", 6: "六", 7: "七", 8: "八", 9: "九",
        10: "十", 12: "十二", 14: "十四", 15: "十五", 16: "十六", 18: "十八", 20: "二十",
        21: "二十一", 24: "二十四", 25: "二十五", 27: "二十七", 28: "二十八", 30: "三十",
        32: "三十二", 35: "三十五", 36: "三十六", 40: "四十", 42: "四十二", 45: "四十五",
        48: "四十八", 49: "四十九", 54: "五十四", 56: "五十六", 60: "六十", 63: "六十三",
        64: "六十四", 70: "七十", 72: "七十二", 80: "八十", 81: "八十一",
    }
    return [
        (f"correction_{a}_{b}", f"{numerals[a]}乘{numerals[b]}等于{numerals[a * b]}。")
        for a in range(1, 10)
        for b in range(1, 10)
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cosyvoice-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--lines", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--speaker", default="中文女")
    args = parser.parse_args()

    if not args.model_dir.joinpath("cosyvoice.yaml").is_file():
        raise FileNotFoundError(f"CosyVoice model is incomplete: {args.model_dir}")
    sys.path.insert(0, str(args.cosyvoice_dir))
    sys.path.insert(0, str(args.cosyvoice_dir / "third_party" / "Matcha-TTS"))
    # CosyVoice's model YAML imports the training-only pitch extractor even though
    # this fixed-voice inference path never calls it.  pyworld has no CPython 3.13
    # Windows wheel, so provide an import placeholder when it is absent.
    try:
        import pyworld  # pylint: disable=import-outside-toplevel,unused-import
    except ImportError:
        sys.modules["pyworld"] = types.ModuleType("pyworld")
    from cosyvoice.cli.cosyvoice import AutoModel  # pylint: disable=import-outside-toplevel

    # The project voice files are generated once; CPU avoids differences from a local GPU.
    torch.set_num_threads(max(1, min(8, torch.get_num_threads())))
    model = AutoModel(model_dir=str(args.model_dir), load_jit=False, load_trt=False, fp16=False)
    if args.speaker not in model.list_available_spks():
        raise ValueError(f"Speaker {args.speaker!r} is unavailable: {model.list_available_spks()}")

    entries = read_lines(args.lines) + correction_entries()
    if len(entries) != 166:
        raise ValueError(f"Expected 166 fixed voice lines, got {len(entries)}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    temporary_dir = Path(tempfile.mkdtemp(prefix="cosyvoice-fixed-audio-"))
    try:
        for resource_name, text in entries:
            destination = args.output_dir / f"{resource_name}.ogg"
            wav_path = temporary_dir / f"{resource_name}.wav"
            chunks = [output["tts_speech"] for output in model.inference_sft(text, args.speaker, stream=False)]
            if not chunks:
                raise RuntimeError(f"CosyVoice produced no audio for {resource_name}")
            # Text normalization can split an input into several inference results.
            # Preserve every part instead of silently keeping only the first one.
            speech = torch.cat(chunks, dim=1)
            torchaudio.save(str(wav_path), speech, model.sample_rate)
            subprocess.run(
                [ffmpeg, "-y", "-loglevel", "error", "-i", str(wav_path), "-ar", "22050", "-ac", "1",
                 "-c:a", "libvorbis", "-q:a", "4", str(destination)],
                check=True,
            )
            if not destination.is_file() or destination.stat().st_size < 256:
                raise RuntimeError(f"Invalid generated audio: {destination}")
            print(f"Generated {destination.name}")
    finally:
        shutil.rmtree(temporary_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
