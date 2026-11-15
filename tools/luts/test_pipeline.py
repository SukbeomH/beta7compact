"""Contract tests for channel order, precision, endpoints and corruption."""
import struct
import tempfile
import shutil
import unittest
from pathlib import Path

import colour
import numpy as np
import png

from build import decode, encode, grid, read_hald, sample
from build import ROOT
from verify import verify


class FormatTests(unittest.TestCase):
    def test_red_fastest_wire_order(self):
        raw = encode(grid(33))
        self.assertEqual(len(raw), 215638)
        self.assertEqual(struct.unpack("<3H", raw[16:22]), (0, 0, 0))
        self.assertEqual(struct.unpack("<3H", raw[22:28]), (2048, 0, 0))
        offset = 16 + 33 * 6
        self.assertEqual(struct.unpack("<3H", raw[offset:offset + 6]), (0, 2048, 0))
        offset = 16 + 33 ** 2 * 6
        self.assertEqual(struct.unpack("<3H", raw[offset:offset + 6]), (0, 0, 2048))
        np.testing.assert_array_equal(decode(raw)[-1, -1, -1], [1, 1, 1])

    def test_asymmetric_transform_and_clamping(self):
        table = grid(33)[..., [2, 0, 1]]
        values = np.array([[0.23, 0.67, 0.89], [1, 0, 1], [-0.5, 1.5, 0.4]])
        expected = np.clip(values, 0, 1)[..., [2, 0, 1]]
        np.testing.assert_allclose(sample(decode(encode(table)), values), expected, atol=0.5 / 65535)

    def test_nonlinear_interpolation_against_colour(self):
        table = grid(7) ** [0.8, 1.2, 2.0]
        points = np.random.default_rng(318).random((1000, 3))
        expected = colour.LUT3D(table).apply(points)
        np.testing.assert_allclose(sample(table, points), expected, atol=2e-7)

    def test_reject_malformed_binary(self):
        raw = encode(grid(33))
        for bad in (raw[:-1], raw + b"\x00", b"WRNG" + raw[4:],
                    raw[:4] + b"\x02\x00" + raw[6:],
                    raw[:12] + b"\x01\x00\x00\x00" + raw[16:]):
            with self.subTest(header=bad[:16]):
                with self.assertRaises(ValueError):
                    decode(bad)

    def test_reject_invalid_samples_and_tables(self):
        for value in (np.nan, np.inf):
            with self.assertRaises(ValueError):
                sample(grid(3), [[value, 0, 0]])
        for value in (np.nan, -0.001, 1.001):
            table = grid(33)
            table[0, 0, 0, 0] = value
            with self.assertRaises(ValueError):
                encode(table)

    def test_hald_preserves_16bit_and_axis_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hald.png"
            # Hald level 2 = 8x8 pixels = 4^3 lattice. Deliberate low bits.
            data = np.arange(64 * 3, dtype=np.uint16).reshape(8, 24) * 257 + 17
            with path.open("wb") as stream:
                png.Writer(8, 8, greyscale=False, bitdepth=16).write(stream, data.tolist())
            table, depth = read_hald(path)
            self.assertEqual(depth, 16)
            np.testing.assert_array_equal(np.rint(table[1, 0, 0] * 65535), data[0, 3:6])
            np.testing.assert_array_equal(np.rint(table[0, 0, 1] * 65535), data[2, :3])


class DeliveryTests(unittest.TestCase):
    def test_delivery_manifest_and_report(self):
        self.assertEqual(verify(ROOT / "assets/luts"), [])

    def test_corrupt_asset_and_missing_preview_are_reported_together(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "luts"
            shutil.copytree(ROOT / "assets/luts", output)
            path = output / "portra160.lut16"
            raw = bytearray(path.read_bytes())
            raw[100] ^= 1
            path.write_bytes(raw)
            (output / "previews/hp5.png").unlink()
            errors = verify(output)
            self.assertTrue(any("portra160: asset checksum" in error for error in errors), errors)
            self.assertTrue(any("hp5:" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
