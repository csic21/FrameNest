#!/usr/bin/env python3
"""Deterministically render the FrameNest launcher and brand icon family."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
DESIGN_DIR = Path(__file__).resolve().parent
RES = ROOT / "app" / "src" / "main" / "res"
BRAND_DIR = ROOT / "docs" / "brand"

DENSITIES = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}

LOGICAL_SIZE = 1024
SUPERSAMPLE = 3

BACKGROUND_STOPS = (
    (0.0, (5, 21, 35)),
    (0.52, (8, 43, 65)),
    (1.0, (11, 72, 91)),
)
FRAME_TOP = (112, 204, 255, 255)
FRAME_BOTTOM = (54, 222, 190, 255)
NEST_TEAL = (45, 206, 178, 255)
NEST_SKY = (128, 218, 255, 255)
PLAY_CORAL = (255, 107, 92, 255)


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
    """Deep cinematic blue with a restrained teal glow behind the mark."""
    pixels: list[tuple[int, int, int, int]] = []
    denominator = max(1, 2 * (size - 1))
    for y in range(size):
        for x in range(size):
            diagonal = (x + y) / denominator
            red, green, blue = interpolate(BACKGROUND_STOPS, diagonal)

            dx = (x - size * 0.52) / (size * 0.58)
            dy = (y - size * 0.48) / (size * 0.58)
            glow = max(0.0, 1.0 - math.sqrt(dx * dx + dy * dy)) ** 2
            red = min(255, round(red + 2 * glow))
            green = min(255, round(green + 15 * glow))
            blue = min(255, round(blue + 20 * glow))
            pixels.append((red, green, blue, 255))

    result = Image.new("RGBA", (size, size))
    result.putdata(pixels)
    return result


def cubic_points(
    start: tuple[float, float],
    control_1: tuple[float, float],
    control_2: tuple[float, float],
    end: tuple[float, float],
    *,
    steps: int = 48,
) -> list[tuple[float, float]]:
    result = []
    for index in range(steps + 1):
        t = index / steps
        inverse = 1.0 - t
        x = (
            inverse**3 * start[0]
            + 3 * inverse**2 * t * control_1[0]
            + 3 * inverse * t**2 * control_2[0]
            + t**3 * end[0]
        )
        y = (
            inverse**3 * start[1]
            + 3 * inverse**2 * t * control_1[1]
            + 3 * inverse * t**2 * control_2[1]
            + t**3 * end[1]
        )
        result.append((x, y))
    return result


def scale_points(
    points: list[tuple[float, float]],
    scale: float,
) -> list[tuple[int, int]]:
    return [(round(x * scale), round(y * scale)) for x, y in points]


def draw_round_line(
    target: Image.Image,
    points: list[tuple[float, float]],
    *,
    color: tuple[int, int, int, int],
    width: float,
    scale: float,
) -> None:
    draw = ImageDraw.Draw(target)
    scaled = scale_points(points, scale)
    scaled_width = round(width * scale)
    radius = scaled_width // 2
    draw.line(scaled, fill=color, width=scaled_width, joint="curve")
    for point in (scaled[0], scaled[-1]):
        draw.ellipse(
            (point[0] - radius, point[1] - radius, point[0] + radius, point[1] + radius),
            fill=color,
        )


def vertical_gradient(size: int, top: tuple[int, ...], bottom: tuple[int, ...]) -> Image.Image:
    result = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(result)
    for y in range(size):
        t = y / max(1, size - 1)
        color = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
        draw.line((0, y, size, y), fill=color)
    return result


def rounded_triangle_mask(size: int, scale: float) -> Image.Image:
    """A softened play wedge that remains legible at 48 px."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    vertices = [(438.0, 352.0), (438.0, 536.0), (614.0, 444.0)]
    radius = 19.0
    outline: list[tuple[float, float]] = []
    for index, vertex in enumerate(vertices):
        previous = vertices[index - 1]
        following = vertices[(index + 1) % len(vertices)]

        previous_length = math.dist(vertex, previous)
        following_length = math.dist(vertex, following)
        incoming = (
            vertex[0] + (previous[0] - vertex[0]) * radius / previous_length,
            vertex[1] + (previous[1] - vertex[1]) * radius / previous_length,
        )
        outgoing = (
            vertex[0] + (following[0] - vertex[0]) * radius / following_length,
            vertex[1] + (following[1] - vertex[1]) * radius / following_length,
        )

        for step in range(9):
            t = step / 8
            inverse = 1.0 - t
            outline.append(
                (
                    inverse * inverse * incoming[0]
                    + 2 * inverse * t * vertex[0]
                    + t * t * outgoing[0],
                    inverse * inverse * incoming[1]
                    + 2 * inverse * t * vertex[1]
                    + t * t * outgoing[1],
                )
            )

    draw.polygon(scale_points(outline, scale), fill=255)
    return mask


def make_mark(size: int = LOGICAL_SIZE) -> Image.Image:
    """Render the frame + woven nest + play mark on transparency."""
    work_size = size * SUPERSAMPLE
    scale = work_size / LOGICAL_SIZE
    mark = Image.new("RGBA", (work_size, work_size), (0, 0, 0, 0))

    # The open-bottom frame keeps the silhouette light; the nest closes it visually.
    frame_points: list[tuple[float, float]] = []
    frame_points += cubic_points((300, 625), (275, 625), (266, 604), (266, 574), steps=10)
    frame_points += [(266, 350)]
    frame_points += cubic_points((266, 350), (266, 286), (310, 242), (374, 242), steps=18)[1:]
    frame_points += [(650, 242)]
    frame_points += cubic_points((650, 242), (714, 242), (758, 286), (758, 350), steps=18)[1:]
    frame_points += [(758, 619)]

    frame_mask = Image.new("RGBA", (work_size, work_size), (0, 0, 0, 0))
    draw_round_line(
        frame_mask,
        frame_points,
        color=(255, 255, 255, 255),
        width=70,
        scale=scale,
    )
    frame_gradient = vertical_gradient(work_size, FRAME_TOP, FRAME_BOTTOM)
    frame_gradient.putalpha(frame_mask.getchannel("A"))
    mark.alpha_composite(frame_gradient)

    # Two broad ribbons cross into a calm bowl: a visual "nest" without tiny detail.
    outer_nest = cubic_points((278, 617), (306, 744), (548, 826), (748, 627), steps=64)
    draw_round_line(mark, outer_nest, color=NEST_TEAL, width=68, scale=scale)

    inner_nest = cubic_points((294, 694), (411, 574), (623, 568), (738, 685), steps=64)
    draw_round_line(mark, inner_nest, color=NEST_SKY, width=54, scale=scale)

    # The warm focal point is intentionally the only non-blue/teal element.
    play_mask = rounded_triangle_mask(work_size, scale)
    play = Image.new("RGBA", (work_size, work_size), PLAY_CORAL)
    play.putalpha(play_mask)
    mark.alpha_composite(play)

    return mark.resize((size, size), Image.Resampling.LANCZOS)


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


def with_shadow(foreground: Image.Image, size: int) -> Image.Image:
    alpha = foreground.getchannel("A")
    shadow_alpha = Image.new("L", (size, size), 0)
    shadow_alpha.paste(alpha, (0, round(size * 0.018)))
    shadow_alpha = shadow_alpha.filter(ImageFilter.GaussianBlur(max(1, round(size * 0.018))))
    shadow_alpha = shadow_alpha.point(lambda value: round(value * 0.34))
    shadow = Image.new("RGBA", (size, size), (0, 8, 16, 0))
    shadow.putalpha(shadow_alpha)
    shadow.alpha_composite(foreground)
    return shadow


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


def compose_icon(size: int, *, fraction: float, round_icon: bool = False) -> Image.Image:
    mark = make_mark(size)
    foreground = place_mark(mark, size, fraction)
    icon = make_background(size)
    icon.alpha_composite(with_shadow(foreground, size))
    return apply_mask(icon, round_icon=round_icon)


def write_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def main() -> None:
    source_mark = make_mark(LOGICAL_SIZE)
    write_png(source_mark, DESIGN_DIR / "framenest-foreground.png")
    write_png(source_mark, BRAND_DIR / "framenest-mark-transparent.png")

    for density, (adaptive_size, legacy_size) in DENSITIES.items():
        target = RES / f"mipmap-{density}"

        adaptive_mark = place_mark(source_mark, adaptive_size, fraction=0.60)
        write_png(make_background(adaptive_size), target / "ic_launcher_background.png")
        write_png(with_shadow(adaptive_mark, adaptive_size), target / "ic_launcher_foreground.png")
        write_png(make_monochrome(adaptive_mark), target / "ic_launcher_monochrome.png")

        write_png(compose_icon(legacy_size, fraction=0.68), target / "ic_launcher.png")
        write_png(
            compose_icon(legacy_size, fraction=0.68, round_icon=True),
            target / "ic_launcher_round.png",
        )

    preview = compose_icon(1024, fraction=0.66)
    write_png(preview, DESIGN_DIR / "framenest-app-icon-preview.png")

    play_store = compose_icon(512, fraction=0.64)
    write_png(play_store, ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png")
    write_png(play_store, BRAND_DIR / "framenest-icon-512.png")
    write_png(compose_icon(512, fraction=0.64), BRAND_DIR / "framenest-logo-512.png")
    write_png(compose_icon(1024, fraction=0.64), BRAND_DIR / "framenest-logo-1024.png")


if __name__ == "__main__":
    main()
