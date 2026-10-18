"""Generate the app's English fixed prompt audio.

The original Chinese pipeline (generate_fixed_voice_audio.py) relies on a local
CosyVoice model whose bundled speakers are Chinese-only. For English we instead
use the macOS system TTS (`say`) with a natural English voice, then package the
result into the same OGG Vorbis format (22050 Hz, mono, q4) the app already
expects. This keeps the *_en resource names the app looks up in sync.

Resource naming mirrors PromptAudioIndex in OfflineVoiceDomain.kt:
  prompt_{a}_{b}_en      -> "What is A times B?"
  feedback_correct_en    -> "Correct!"
  feedback_incorrect_en  -> "Try again."
  feedback_listen_en     -> "Listen carefully."
  feedback_no_speech_en  -> "I didn't catch that, please repeat."
  correction_{a}_{b}_en  -> "A times B equals C."

Total: 81 prompts + 4 feedbacks + 81 corrections = 166 files.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ONES = {
    1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven",
    8: "eight", 9: "nine", 10: "ten", 11: "eleven", 12: "twelve", 13: "thirteen",
    14: "fourteen", 15: "fifteen", 16: "sixteen", 17: "seventeen", 18: "eighteen",
    19: "nineteen",
}
TENS = {
    2: "twenty", 3: "thirty", 4: "forty", 5: "fifty", 6: "sixty", 7: "seventy",
    8: "eighty", 9: "ninety",
}


def en_num(n: int) -> str:
    """English words for 0..99 (the app only needs 0..81)."""
    if n == 0:
        return "zero"
    if 1 <= n <= 19:
        return ONES[n]
    tens, units = divmod(n, 10)
    if units == 0:
        return TENS[tens]
    return f"{TENS[tens]}-{ONES[units]}"


def prompt_feedback_entries() -> list[tuple[str, str]]:
    """81 prompt lines (9x9) + 4 feedback lines, mirroring fixed_voice_lines.txt."""
    entries: list[tuple[str, str]] = []
    for a in range(1, 10):
        for b in range(1, 10):
            entries.append((f"prompt_{a}_{b}_en", f"What is {a} times {b}?"))
    entries += [
        ("feedback_correct_en", "Correct!"),
        ("feedback_incorrect_en", "Try again."),
        ("feedback_listen_en", "Listen carefully."),
        ("feedback_no_speech_en", "I didn't catch that, please repeat."),
    ]
    return entries


def correction_entries() -> list[tuple[str, str]]:
    """81 correction lines: 'A times B equals C.'"""
    return [
        (f"correction_{a}_{b}_en", f"{en_num(a)} times {en_num(b)} equals {en_num(a * b)}.")
        for a in range(1, 10)
        for b in range(1, 10)
    ]


def all_entries() -> list[tuple[str, str]]:
    return prompt_feedback_entries() + correction_entries()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--voice", default="Samantha", help="macOS voice name for `say`")
    parser.add_argument(
        "--ffmpeg",
        type=Path,
        default=None,
        help="Path to ffmpeg binary (auto-detected via imageio-ffmpeg when omitted)",
    )
    parser.add_argument(
        "--lines-out",
        type=Path,
        default=None,
        help="Optional: write the editable 85-line prompt/feedback text file here",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Regenerate even if the .ogg already exists",
    )
    args = parser.parse_args()

    if args.ffmpeg is None:
        try:
            import imageio_ffmpeg

            args.ffmpeg = Path(imageio_ffmpeg.get_ffmpeg_exe())
        except Exception as error:  # pylint: disable=broad-except
            raise SystemExit(
                "ffmpeg not found; install imageio-ffmpeg or pass --ffmpeg PATH"
            ) from error

    entries = all_entries()
    if len(entries) != 166:
        raise ValueError(f"Expected 166 fixed voice lines, got {len(entries)}")

    if args.lines_out is not None:
        args.lines_out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.lines_out, "w", encoding="utf-8") as handle:
            for name, text in prompt_feedback_entries():
                handle.write(f"{name}\t{text}\n")
        print(f"Wrote {args.lines_out}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    temp_dir = Path(tempfile.mkdtemp(prefix="en-fixed-audio-"))
    try:
        for name, text in entries:
            destination = args.output_dir / f"{name}.ogg"
            if destination.exists() and not args.force:
                continue
            aiff = temp_dir / f"{name}.aiff"
            subprocess.run(
                ["say", "-v", args.voice, "-o", str(aiff), text],
                check=True,
            )
            subprocess.run(
                [
                    str(args.ffmpeg), "-y", "-loglevel", "error", "-i", str(aiff),
                    "-ar", "22050", "-ac", "1", "-c:a", "libvorbis", "-q:a", "4",
                    str(destination),
                ],
                check=True,
            )
            if not destination.is_file() or destination.stat().st_size < 256:
                raise RuntimeError(f"Invalid generated audio: {destination}")
            print(f"Generated {destination.name}")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
