"""Download the official CosyVoice-300M-SFT model for offline fixed-audio generation."""

from pathlib import Path

from modelscope import snapshot_download


snapshot_download(
    "iic/CosyVoice-300M-SFT",
    local_dir=str(Path(r"C:\\codex-cosyvoice\\pretrained_models\\CosyVoice-300M-SFT-ms")),
)
