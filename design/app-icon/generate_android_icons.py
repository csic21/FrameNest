#!/usr/bin/env python3
"""Deterministically render the approved FrameNest launcher icon family."""

from __future__ import annotations

import colorsys
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
DESIGN_DIR = Path(__file__).resolve().parent
RES = ROOT / "app" / "src" / "main" / "res"
BRAND_DIR = ROOT / "docs" / "brand"
APPROVED_ART = DESIGN_DIR / "framenest-logo-approved.png"

DENSITIES = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}

LOGICAL_SIZE = 1024

# Adaptive icon layers are authored on a 108dp canvas, while the launcher mask is
# roughly 72dp wide. Keeping the mark at the 66dp safe-zone size (0.61) left a
# visible midnight rim on ColorOS. A small 0.68 overscan fills the mask without
# moving the central play glyph or baking any vendor-specific corner shape in.
ADAPTIVE_MARK_FRACTION = 0.68

# Shared with ui/theme/Color.kt. The launcher can use the brighter source hues;
# the light UI theme uses darker accessible roles where white text is required.
MIDNIGHT = (12, 18, 43, 255)
BACKGROUND_STOPS = (
    (0.0, (7, 11, 29)),
    (0.55, MIDNIGHT[:3]),
    (1.0, (22, 29, 63)),
)


def interpolate(stops: tuple, t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    for index in range(len(stops) - 1):
        start_t, start = stops[index]
        end_t, end = stops[index + 1]
        if t <= end_t:
            progress = (t - start_t) / (end_t - start_t)
            return tuple(round(a + (b - a) * progress) for a, b in zip(start, end))
    return stops[-1][1]


def make_background(size: int) -> Image.Image:
    """Matte midnight indigo with the approved restrained center lift."""
    pixels: list[tuple[int, int, int, int]] = []
    denominator = max(1, 2 * (size - 1))
    for y in range(size):
        for x in range(size):
            diagonal = (x + y) / denominator
            red, green, blue = interpolate(BACKGROUND_STOPS, diagonal)

            dx = (x - size * 0.5) / (size * 0.64)
            dy = (y - size * 0.48) / (size * 0.64)
            glow = max(0.0, 1.0 - math.sqrt(dx * dx + dy * dy)) ** 2
            pixels.append(
                (
                    min(255, round(red + 3 * glow)),
                    min(255, round(green + 4 * glow)),
                    min(255, round(blue + 10 * glow)),
                    255,
                )
            )

    result = Image.new("RGBA", (size, size))
    result.putdata(pixels)
    return result


def approved_mark(size: int = LOGICAL_SIZE) -> Image.Image:
    """Extract the approved ImageGen mark while preserving its exact visible geometry."""
    source = Image.open(APPROVED_ART).convert("RGBA")
    pixels = source.load()
    alpha = Image.new("L", source.size, 0)
    alpha_pixels = alpha.load()

    for y in range(source.height):
        for x in range(source.width):
            red, green, blue, _ = pixels[x, y]
            _, _, value = colorsys.rgb_to_hsv(red / 255, green / 255, blue / 255)

            # The approved artwork has a low-value navy background and high-value colored mark.
            # A short soft transition keeps the generated antialiased boundary intact.
            if value <= 0.30:
                opacity = 0
            elif value >= 0.52:
                opacity = 255
            else:
                opacity = round((value - 0.30) / 0.22 * 255)
            alpha_pixels[x, y] = opacity

    source.putalpha(alpha)
    mark = trimmed(source)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target_width = round(size * 0.78)
    target_height = round(mark.height * target_width / mark.width)
    resized = mark.resize((target_width, target_height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(
        resized,
        ((size - target_width) // 2, (size - target_height) // 2),
    )
    return canvas


def trimmed(image: Image.Image) -> Image.Image:
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("The rendered mark has no visible pixels")
    return image.crop(bounds)


def place_mark(mark: Image.Image, size: int, fraction: float) -> Image.Image:
    mark = trimmed(mark)
    scale = (size * fraction) / max(mark.size)
    resized = mark.resize(
        (max(1, round(mark.width * scale)), max(1, round(mark.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = ((size - resized.width) // 2, (size - resized.height) // 2)
    canvas.alpha_composite(resized, offset)
    return canvas


def make_monochrome(foreground: Image.Image) -> Image.Image:
    result = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    result.putalpha(foreground.getchannel("A"))
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


def compose_icon(
    source_mark: Image.Image,
    size: int,
    *,
    fraction: float,
    round_icon: bool = False,
) -> Image.Image:
    icon = make_background(size)
    icon.alpha_composite(place_mark(source_mark, size, fraction))
    return apply_mask(icon, round_icon=round_icon)


def write_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def main() -> None:
    source_mark = approved_mark(LOGICAL_SIZE)
    write_png(source_mark, DESIGN_DIR / "framenest-foreground.png")
    write_png(source_mark, BRAND_DIR / "framenest-mark-transparent.png")

    for density, (adaptive_size, legacy_size) in DENSITIES.items():
        target = RES / f"mipmap-{density}"
        adaptive_mark = place_mark(
            source_mark,
            adaptive_size,
            fraction=ADAPTIVE_MARK_FRACTION,
        )
        write_png(make_background(adaptive_size), target / "ic_launcher_background.png")
        write_png(adaptive_mark, target / "ic_launcher_foreground.png")
        write_png(make_monochrome(adaptive_mark), target / "ic_launcher_monochrome.png")
        write_png(
            compose_icon(source_mark, legacy_size, fraction=0.66),
            target / "ic_launcher.png",
        )
        write_png(
            compose_icon(source_mark, legacy_size, fraction=0.66, round_icon=True),
            target / "ic_launcher_round.png",
        )

    preview = compose_icon(source_mark, 1024, fraction=0.65)
    write_png(preview, DESIGN_DIR / "framenest-app-icon-preview.png")

    play_store = compose_icon(source_mark, 512, fraction=0.64)
    write_png(play_store, ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png")
    write_png(play_store, BRAND_DIR / "framenest-icon-512.png")
    write_png(compose_icon(source_mark, 512, fraction=0.64), BRAND_DIR / "framenest-logo-512.png")
    write_png(
        compose_icon(source_mark, 1024, fraction=0.64),
        BRAND_DIR / "framenest-logo-1024.png",
    )


if __name__ == "__main__":
    main()
