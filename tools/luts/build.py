#!/usr/bin/env python3
"""Pinned, headless sRGB LUT build and independent source-data validation."""
import argparse
import gzip
import hashlib
import importlib
import json
import os
from pathlib import Path
import platform
import struct
import subprocess
import sys

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault("NUMBA_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import colour
import numpy as np
import png
from PIL import Image, ImageDraw
from scipy.interpolate import RegularGridInterpolator

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
SIZE = 33
PARAMETERS = dict(
    mode="full", input_colorspace="sRGB", exp_kelvin=6500, tint=0.0,
    exp_comp=0.0, adx_coding=True, adx_scaling=1.0, red_light=0.0,
    green_light=0.0, blue_light=0.0, projector_kelvin=6500,
    white_clip=True, white_balance=1, output_gamut="Rec. 709",
    sat_adjust=1.0, shadow_comp=0.0, gamma_func="sRGB", push_pull=0.0,
    inversion=False, custom_inversion=False, inversion_gamma=3.0,
    idealized_curve=False, apd_intermediate=False,
    reference_negative=None, upsampling_method="SFL upsampling",
)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2,
                                    allow_nan=False) + "\n", encoding="utf-8")


def grid(n):
    return np.stack(np.meshgrid(*([np.linspace(0, 1, n)] * 3), indexing="ij"), -1)


def sample(table, values):
    """Runtime contract: clamp encoded sRGB, trilinear in encoded sRGB."""
    values = np.asarray(values, dtype=np.float64)
    if not np.isfinite(values).all():
        raise ValueError("non-finite input")
    q = np.clip(values, 0, 1) * (len(table) - 1)
    lo = np.minimum(q.astype(int), len(table) - 2)
    f = q - lo
    result = np.zeros_like(values)
    for r in (0, 1):
        for g in (0, 1):
            for b in (0, 1):
                c = np.array([r, g, b])
                w = np.prod(np.where(c, f, 1 - f), axis=-1)
                ix = lo + c
                result += table[ix[..., 0], ix[..., 1], ix[..., 2]] * w[..., None]
    return result


def encode(table):
    table = np.asarray(table)
    if table.shape != (SIZE, SIZE, SIZE, 3) or not np.isfinite(table).all():
        raise ValueError("expected finite 33 cubed RGB table")
    if table.min() < 0 or table.max() > 1:
        raise ValueError("out-of-domain LUT output")
    # CUBE order: red changes fastest, then green, then blue; RGB interleaved.
    payload = np.rint(table.transpose(2, 1, 0, 3) * 65535).astype("<u2").tobytes()
    return b"B7LT" + struct.pack("<HHII", 1, SIZE, 3, 0) + payload


def decode(raw):
    if len(raw) != 16 + SIZE ** 3 * 6:
        raise ValueError("invalid length")
    if raw[:4] != b"B7LT" or struct.unpack("<HHII", raw[4:16]) != (1, SIZE, 3, 0):
        raise ValueError("invalid header")
    return np.frombuffer(raw, "<u2", offset=16).reshape(SIZE, SIZE, SIZE, 3).transpose(2, 1, 0, 3) / 65535.0


def read_hald(path):
    with Path(path).open("rb") as stream:
        width, height, rows, info = png.Reader(file=stream).asDirect()
        level = round(width ** (1 / 3))
        if width != height or level ** 3 != width or info["planes"] != 3:
            raise ValueError("expected square RGB Hald CLUT")
        n = level ** 2
        # pypng preserves 16-bit RGB; Pillow would silently reduce it to 8 bits.
        pixels = np.vstack([np.asarray(row) for row in rows]).astype(np.float64)
    table = pixels.reshape(n, n, n, 3).transpose(2, 1, 0, 3)
    return table / (2 ** info["bitdepth"] - 1), info["bitdepth"]


def checkout(name, source):
    dest = BASE / ".sources" / name
    if not dest.exists():
        dest.parent.mkdir(exist_ok=True)
        subprocess.run(["git", "clone", "--no-checkout", source["repo"], str(dest)], check=True)
        subprocess.run(["git", "-C", str(dest), "checkout", "--detach", source["commit"]], check=True)
    actual = subprocess.check_output(["git", "-C", str(dest), "rev-parse", "HEAD"], text=True).strip()
    if actual != source["commit"]:
        raise ValueError(f"{name}: unexpected source commit {actual}")
    if subprocess.check_output(["git", "-C", str(dest), "status", "--porcelain", "--untracked-files=no"]):
        raise ValueError(f"{name}: modified source checkout")
    license_path = dest / ("LICENSE" if name == "spectral" else "LICENSE.txt")
    if digest(license_path) != source["license_sha256"]:
        raise ValueError(f"{name}: license changed")
    return dest


def spectral_source(preset, dest):
    sys.path.insert(0, str(dest / "src"))
    stocks = importlib.import_module("spectral_film_lut")
    film_class = importlib.import_module("spectral_film_lut.film_spectral").FilmSpectral
    utils = importlib.import_module("spectral_film_lut.utils")
    neg = film_class(getattr(stocks, preset["negative"]), gray_value=0.18)
    pr = film_class(getattr(stocks, preset["print"]), gray_value=0.18) if preset["print"] else None
    params = PARAMETERS | {"color_masking": float(neg.color_masking)}
    table = utils.create_lut(neg, pr, lut_size=SIZE, cube=False,
                             linear_scaling=1.0, filter_mode=None, **params)
    def reference(values):
        return np.clip(utils.film_conversion(values.astype(np.float32), neg, pr, **params), 0, 1)
    # Resolve actual upstream definition files rather than inferring paths.
    files = ["src/spectral_film_lut/utils.py", "src/spectral_film_lut/film_spectral.py"]
    for symbol in (preset["negative"], preset["print"]):
        if symbol:
            for module in list(sys.modules.values()):
                path = getattr(module, "__file__", None)
                if path and str(dest) in path and symbol in vars(module) and module is not stocks:
                    files.append(str(Path(path).relative_to(dest)))
    files = sorted(set(files))
    return table, reference, dict(parameters=params, gray_value=0.18,
        lut_size=SIZE, linear_scaling=1.0, filter_mode=None,
        files=[dict(path=f, sha256=digest(dest / f)) for f in files])


def source_table(preset, sources, checkouts):
    source = sources[preset["source"]]
    if preset["source"] == "spectral":
        return spectral_source(preset, checkouts["spectral"])
    if preset["source"] == "t3mujinpack":
        path = checkouts["t3mujinpack"] / preset["file"]
        original, depth = read_hald(path)
        details = dict(files=[dict(path=preset["file"], sha256=digest(path))],
                       source_size=len(original), source_bit_depth=depth,
                       method="Hald red-fastest decode; encoded-sRGB trilinear resampling to 33^3")
    else:
        raw = gzip.decompress((BASE / "sources/editorhub" / (preset["file"] + ".gz")).read_bytes())
        if hashlib.sha256(raw).hexdigest() != source["files"][preset["file"]]:
            raise ValueError("CC0 source checksum mismatch")
        if b"(CC0)" not in raw.splitlines()[0]:
            raise ValueError("missing per-file CC0 declaration")
        work = BASE / ".work"
        work.mkdir(exist_ok=True)
        path = work / preset["file"]
        path.write_bytes(raw)
        lut = colour.io.read_LUT(str(path))
        if not isinstance(lut, colour.LUT3D) or not np.array_equal(lut.domain, [[0, 0, 0], [1, 1, 1]]):
            raise ValueError("unexpected CUBE domain")
        original = lut.table
        details = dict(files=[dict(path=preset["file"], sha256=hashlib.sha256(raw).hexdigest(),
                         url="https://editor-hub.com/luts/" + preset["file"])],
                       source_size=len(original), method="CUBE red-fastest import; encoded-sRGB trilinear resampling to 33^3")
    axes = (np.linspace(0, 1, len(original)),) * 3
    # Independent SciPy implementation evaluates the full original source grid.
    interpolator = RegularGridInterpolator(axes, original, method="linear")
    def reference(values):
        return interpolator(np.clip(values, 0, 1))
    return sample(original, grid(SIZE)), reference, details


def lab(rgb):
    return colour.XYZ_to_Lab(colour.sRGB_to_XYZ(np.asarray(rgb, dtype=np.float64)))


def delta(a, b):
    d = np.asarray(colour.delta_E(lab(a), lab(b), method="CIE 2000"))
    return dict(mean=float(d.mean()), p95=float(np.percentile(d, 95)), max=float(d.max()))


def synthetic():
    # Entirely generated, 384x256 encoded-sRGB ramps, colours, random patches.
    x, y = np.meshgrid(np.linspace(0, 1, 384), np.linspace(0, 1, 256))
    image = np.stack([x, y, (x + y) / 2], -1)
    image[:48] = x[:48, :, None]
    rng = np.random.default_rng(7133)
    image[128:] = np.repeat(np.repeat(rng.random((8, 24, 3)), 16, 0), 16, 1)
    return image


def validate(table, restored, reference, patches):
    expected = reference(patches)
    actual = sample(restored, patches)
    gray = np.repeat(np.linspace(0, 1, 1025)[:, None], 3, axis=1)
    source_gray, output_gray = reference(gray), sample(restored, gray)
    luminance = colour.sRGB_to_XYZ(output_gray)[:, 1]
    source_luminance = colour.sRGB_to_XYZ(source_gray)[:, 1]
    corners = grid(2).reshape(-1, 3)
    qerr = float(np.max(np.abs(table - restored)))
    report = dict(
        source_vs_builtin_deltaE00=delta(expected, actual),
        quantization_only_deltaE00=delta(sample(table, patches), actual),
        quantization_max_rgb=qerr,
        gray_source_vs_builtin_deltaE00=delta(source_gray, output_gray),
        gray_neutral_chroma_max=float(np.max(np.linalg.norm(lab(output_gray)[:, 1:], axis=-1))),
        gray_identity_rgb_max=float(np.max(np.abs(output_gray - gray))),
        gray_rgb_channel_spread_max=float(np.max(np.ptp(output_gray, axis=-1))),
        gray_luminance_decreases=int(np.sum(np.diff(luminance) < -1e-6)),
        gray_luminance_min_step=float(np.min(np.diff(luminance))),
        source_gray_luminance_decreases=int(np.sum(np.diff(source_luminance) < -1e-6)),
        source_gray_luminance_min_step=float(np.min(np.diff(source_luminance))),
        gray_channel_decreases=[int(np.sum(np.diff(output_gray[:, c]) < -1e-6)) for c in range(3)],
        corners_input=corners.tolist(), corners_output=sample(restored, corners).tolist(),
        corners_source_rgb_max_error=float(np.max(np.abs(reference(corners) - sample(restored, corners)))),
        range_min=float(restored.min()), range_max=float(restored.max()),
        rgb_channel_spread_max=float(np.max(np.ptp(restored, axis=-1))),
    )
    # Gray neutrality and monotonicity describe the look, not identity pass gates.
    report["passed"] = bool(np.isfinite(expected).all() and np.isfinite(actual).all()
        and qerr <= 0.5 / 65535 + 1e-12
        and report["quantization_only_deltaE00"]["max"] < 0.02
        and report["source_vs_builtin_deltaE00"]["p95"] < 2.0)
    return report


def build(output):
    sources = json.loads((BASE / "sources.lock.json").read_text())
    presets = json.loads((BASE / "presets.json").read_text())
    checkouts = {name: checkout(name, sources[name]) for name in ("spectral", "t3mujinpack")}
    for source in sources.values():
        if digest(BASE / source["license_file"]) != source["license_sha256"]:
            raise ValueError("license checksum mismatch")
    output.mkdir(parents=True, exist_ok=True)
    (output / "previews").mkdir(exist_ok=True)
    work = BASE / ".work/cubes"
    work.mkdir(parents=True, exist_ok=True)
    image = synthetic()
    Image.fromarray(np.rint(image * 255).astype(np.uint8)).save(output / "previews/input.png")
    patches = np.concatenate([grid(17).reshape(-1, 3),
        np.random.default_rng(7133).random((4096, 3)), image[::8, ::8].reshape(-1, 3)])
    entries, reports = [], []
    for preset in presets:
        table, reference, details = source_table(preset, sources, checkouts)
        table = np.asarray(table, dtype=np.float64)
        raw = encode(table)
        path = output / (preset["id"] + ".lut16")
        path.write_bytes(raw)
        restored = decode(path.read_bytes())
        # Text intermediates are reproducible, deliberately excluded from Git.
        colour.io.write_LUT(colour.LUT3D(table=table, name=preset["id"]), str(work / (preset["id"] + ".cube")), decimals=10)
        report = dict(id=preset["id"], asset_sha256=digest(path),
                      **validate(table, restored, reference, patches))
        reports.append(report)
        preview = sample(restored, image)
        preview_path = output / "previews" / (preset["id"] + ".png")
        Image.fromarray(np.rint(preview * 255).astype(np.uint8)).save(preview_path)
        source = sources[preset["source"]]
        entries.append(dict(id=preset["id"], display_name=preset["display_name"],
            category=preset["category"], path=path.name, size=SIZE, format="B7LT-v1",
            input_color_space="sRGB", output_color_space="sRGB", sha256=digest(path),
            bytes=len(raw), license_spdx=source["license"], author=source["author"],
            source=dict(provider=preset["source"], repo=source["repo"], commit=source["commit"],
                        url=source.get("url", source["repo"]), **details),
            conversion="encoded-sRGB trilinear 33^3; round-to-nearest-even UNORM16; RGB red-fastest LE",
            preview=dict(path="previews/" + preview_path.name, sha256=digest(preview_path))))
        print(preset["id"], "PASS" if report["passed"] else "FAIL",
              report["source_vs_builtin_deltaE00"], flush=True)
    write_json(output / "manifest.json", dict(schema_version=1, luts=entries))
    sheet = Image.new("RGB", (768, 7 * 148), "#202020")
    draw = ImageDraw.Draw(sheet)
    for i, name in enumerate(["input"] + [p["id"] for p in presets]):
        x, y = (i % 4) * 192, (i // 4) * 148
        with Image.open(output / "previews" / (name + ".png")) as thumb:
            sheet.paste(thumb.resize((192, 128), Image.Resampling.LANCZOS), (x, y))
        draw.text((x + 4, y + 131), name, fill="white")
    sheet.save(output / "previews/contact-sheet.png")
    notice = "beta7compact LUT notices\n\nFilm names describe approximations, not reproduction or endorsement.\n\n"
    for name, source in sources.items():
        notice += f"=== {name} | {source['repo'] or source['url']} | {source['commit'] or 'file SHA256 pinned'} ===\n"
        notice += (BASE / source["license_file"]).read_text() + "\n"
    (output / "NOTICE").write_text(notice, encoding="utf-8")
    report = dict(schema_version=1, samples=len(patches), seed=7133,
        reference="Spectral: upstream film_conversion direct; Hald/CUBE: full original grid via independent SciPy trilinear evaluator",
        limitations="CPU synthetic patches only; no Darktable GUI, app GPU or real camera parity tested",
        gates=dict(quantization_max_rgb=0.5 / 65535, quantization_deltaE00_max=0.02, source_deltaE00_p95=2.0),
        python=platform.python_version(), requirements_sha256=digest(BASE / "requirements.txt"),
        inputs_sha256={name: digest(BASE / name) for name in
                       ("build.py", "presets.json", "sources.lock.json")},
        passed=all(r["passed"] for r in reports), luts=reports)
    write_json(output / "validation.json", report)
    if not report["passed"]:
        raise SystemExit("LUT validation failed; see validation.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "assets/luts")
    args = parser.parse_args()
    build(args.output.resolve())
