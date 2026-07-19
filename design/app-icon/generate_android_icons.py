#!/usr/bin/env python3
"""Generate Android launcher resources from the approved transparent icon mark."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("framenest-foreground.png")
RES = ROOT / "app" / "src" / "main" / "res"

DENSITIES = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}

GRADIENT_STOPS = (
    (0.0, (50, 239, 192)),
    (0.48, (16, 222, 173)),
    (1.0, (3, 190, 166)),
)


def interpolate_color(t: float) -> tuple[int, int, int]:
    for index in range(len(GRADIENT_STOPS) - 1):
        start_t, start = GRADIENT_STOPS[index]
        end_t, end = GRADIENT_STOPS[index + 1]
        if t <= end_t:
            progress = (t - start_t) / (end_t - start_t)
            return tuple(round(a + (b - a) * progress) for a, b in zip(start, end))
    return GRADIENT_STOPS[-1][1]


def make_background(size: int) -> Image.Image:
    denominator = max(1, 2 * (size - 1))
    pixels: list[tuple[int, int, int, int]] = []
    for y in range(size):
        for x in range(size):
            t = (x + y) / denominator
            red, green, blue = interpolate_color(t)

            # A restrained central vignette preserves contrast behind the glossy mark.
            dx = (x - size / 2) / (size / 2)
            dy = (y - size / 2) / (size / 2)
            center = max(0.0, 1.0 - (dx * dx + dy * dy) ** 0.5)
            shade = 1.0 - 0.06 * center
            pixels.append((round(red * shade), round(green * shade), round(blue * shade), 255))

    result = Image.new("RGBA", (size, size))
    result.putdata(pixels)
    return result


def trimmed_mark() -> Image.Image:
    source = Image.open(SOURCE).convert("RGBA")
    bounds = source.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError(f"No visible pixels in {SOURCE}")
    return source.crop(bounds)


def make_foreground(mark: Image.Image, size: int, fraction: float) -> Image.Image:
    longest = max(mark.size)
    scale = (size * fraction) / longest
    resized = mark.resize(
        (max(1, round(mark.width * scale)), max(1, round(mark.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = ((size - resized.width) // 2, (size - resized.height) // 2)
    canvas.alpha_composite(resized, offset)
    return canvas


def make_monochrome(foreground: Image.Image) -> Image.Image:
    white = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    white.putalpha(foreground.getchannel("A"))
    return white


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
    mark = trimmed_mark()

    for density, (adaptive_size, legacy_size) in DENSITIES.items():
        target = RES / f"mipmap-{density}"

        adaptive_background = make_background(adaptive_size)
        adaptive_foreground = make_foreground(mark, adaptive_size, fraction=0.62)
        write_png(adaptive_background, target / "ic_launcher_background.png")
        write_png(adaptive_foreground, target / "ic_launcher_foreground.png")
        write_png(make_monochrome(adaptive_foreground), target / "ic_launcher_monochrome.png")

        legacy = make_background(legacy_size)
        legacy.alpha_composite(make_foreground(mark, legacy_size, fraction=0.70))
        write_png(apply_mask(legacy, round_icon=False), target / "ic_launcher.png")
        write_png(apply_mask(legacy, round_icon=True), target / "ic_launcher_round.png")

    preview = make_background(1024)
    preview.alpha_composite(make_foreground(mark, 1024, fraction=0.68))
    write_png(
        apply_mask(preview, round_icon=False),
        Path(__file__).with_name("framenest-app-icon-preview.png"),
    )


if __name__ == "__main__":
    main()
