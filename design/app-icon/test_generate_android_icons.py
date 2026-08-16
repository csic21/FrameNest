#!/usr/bin/env python3
"""Regression checks for generated FrameNest launcher icon layers."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

from PIL import Image, ImageChops


SCRIPT = Path(__file__).with_name("generate_android_icons.py")
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("generate_android_icons", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT}")
icons = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(icons)


class GeneratedLauncherIconTest(unittest.TestCase):
    def test_adaptive_layers_fill_mask_and_keep_opaque_background(self) -> None:
        for density, (adaptive_size, _) in icons.DENSITIES.items():
            with self.subTest(density=density):
                target = icons.RES / f"mipmap-{density}"
                foreground = Image.open(target / "ic_launcher_foreground.png").convert("RGBA")
                background = Image.open(target / "ic_launcher_background.png").convert("RGBA")
                monochrome = Image.open(target / "ic_launcher_monochrome.png").convert("RGBA")

                self.assertEqual(foreground.size, (adaptive_size, adaptive_size))
                self.assertEqual(background.size, foreground.size)
                self.assertEqual(monochrome.size, foreground.size)

                bounds = foreground.getchannel("A").getbbox()
                self.assertIsNotNone(bounds)
                assert bounds is not None
                visible_fraction = max(bounds[2] - bounds[0], bounds[3] - bounds[1]) / adaptive_size
                self.assertGreaterEqual(visible_fraction, 0.675)
                self.assertLessEqual(visible_fraction, 0.69)

                self.assertEqual(background.getchannel("A").getextrema(), (255, 255))
                self.assertIsNone(
                    ImageChops.difference(
                        foreground.getchannel("A"),
                        monochrome.getchannel("A"),
                    ).getbbox()
                )


if __name__ == "__main__":
    unittest.main()
