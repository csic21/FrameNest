#!/usr/bin/env python3
"""Scale the generated FrameNest icon into the Android launcher sizes."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
DESIGN_DIR = Path(__file__).resolve().parent
RES = ROOT / "app" / "src" / "main" / "res"
BRAND_DIR = ROOT / "docs" / "brand"
MASTER = DESIGN_DIR / "framenest-logo-generated.png"

DENSITIES = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}


def load_master() -> Image.Image:
    image = Image.open(MASTER).convert("RGBA")
    side = min(image.size)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return image.crop((left, top, left + side, top + side))


def fit(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.LANCZOS)


def corner_color(image: Image.Image) -> tuple[int, int, int, int]:
    samples = (
        image.getpixel((0, 0)),
        image.getpixel((image.width - 1, 0)),
        image.getpixel((0, image.height - 1)),
        image.getpixel((image.width - 1, image.height - 1)),
    )
    channels = [sum(pixel[index] for pixel in samples) // len(samples) for index in range(3)]
    return (channels[0], channels[1], channels[2], 255)


def solid(size: int, color: tuple[int, int, int, int]) -> Image.Image:
    image = Image.new("RGBA", (size, size), color)
    return image


def glyph_alpha(image: Image.Image, field: tuple[int, int, int, int]) -> Image.Image:
    """Keep the frame and play mark; drop the flat background for themed icons."""
    result = Image.new("RGBA", image.size, (255, 255, 255, 0))
    source = image.load()
    output = result.load()
    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, _ = source[x, y]
            distance = abs(red - field[0]) + abs(green - field[1]) + abs(blue - field[2])
            if distance < 36:
                continue
            output[x, y] = (255, 255, 255, min(255, (distance - 36) * 4))
    return result


def apply_mask(image: Image.Image, *, round_icon: bool) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    if round_icon:
        draw.ellipse((0, 0, image.width - 1, image.height - 1), fill=255)
    else:
        radius = round(image.width * 0.22)
        draw.rounded_rectangle((0, 0, image.width - 1, image.height - 1), radius=radius, fill=255)
    result = image.copy()
    result.putalpha(mask)
    return result


def write_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def main() -> None:
    master = load_master()
    field = corner_color(master)
    write_png(fit(master, 1024), DESIGN_DIR / "framenest-app-icon-preview.png")
    write_png(fit(master, 1024), BRAND_DIR / "framenest-logo-1024.png")
    write_png(fit(master, 512), BRAND_DIR / "framenest-logo-512.png")
    write_png(fit(master, 512), BRAND_DIR / "framenest-icon-512.png")
    write_png(fit(master, 512), ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png")
    write_png(glyph_alpha(fit(master, 1024), field), DESIGN_DIR / "framenest-foreground.png")
    write_png(glyph_alpha(fit(master, 1024), field), BRAND_DIR / "framenest-mark-transparent.png")

    for density, (adaptive_size, legacy_size) in DENSITIES.items():
        target = RES / f"mipmap-{density}"
        adaptive = fit(master, adaptive_size)
        write_png(solid(adaptive_size, field), target / "ic_launcher_background.png")
        write_png(adaptive, target / "ic_launcher_foreground.png")
        write_png(glyph_alpha(adaptive, field), target / "ic_launcher_monochrome.png")
        legacy = fit(master, legacy_size)
        write_png(apply_mask(legacy, round_icon=False), target / "ic_launcher.png")
        write_png(apply_mask(legacy, round_icon=True), target / "ic_launcher_round.png")


if __name__ == "__main__":
    main()
