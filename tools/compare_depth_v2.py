#!/usr/bin/env python3
"""Generate private Depth Anything V2 preview sheets and per-image CPU timings.

Usage: python tools/compare_depth_v2.py MODEL.onnx OUTPUT_DIR IMAGE [IMAGE ...]
The model and rendered user images should stay outside Git.
"""

import argparse
import json
import os
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps


def model_input(image, longest_side=392):
    width, height = image.size
    scale = longest_side / max(width, height)
    width = max(14, round(width * scale / 14) * 14)
    height = max(14, round(height * scale / 14) * 14)
    rgb = np.asarray(image.resize((width, height), Image.Resampling.BICUBIC), dtype=np.float32) / 255
    rgb = (rgb - np.array([0.485, 0.456, 0.406], dtype=np.float32)) / np.array(
        [0.229, 0.224, 0.225], dtype=np.float32
    )
    return np.ascontiguousarray(rgb.transpose(2, 0, 1)[None]), width, height


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--midas", type=Path, help="official MiDaS v2.1 small ONNX source")
    args = parser.parse_args()
    model_path, output_dir = args.model.resolve(), args.output_dir.resolve()
    images = [path.resolve() for path in args.images]
    output_dir.mkdir(parents=True, exist_ok=True)
    # ONNX Runtime telemetry may create ':memory:.ses' in the current directory.
    os.chdir(output_dir)
    import onnxruntime as ort
    start = time.perf_counter()
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - start) * 1000
    midas_session = (ort.InferenceSession(str(args.midas.resolve()), providers=["CPUExecutionProvider"])
                     if args.midas else None)
    rows = []
    for path in images:
        with Image.open(path) as source:
            image = ImageOps.exif_transpose(source).convert("RGB")
        tensor, width, height = model_input(image)
        start = time.perf_counter()
        raw = session.run(["depth"], {"image": tensor})[0][0]
        inference_ms = (time.perf_counter() - start) * 1000
        low, high = np.percentile(raw, (2, 98))
        depth = np.uint8(np.clip((raw - low) / max(high - low, 1e-6), 0, 1) * 255)
        preview_width = 480
        preview_height = round(preview_width * image.height / image.width)
        rgb = image.resize((preview_width, preview_height), Image.Resampling.LANCZOS)
        depth_image = Image.fromarray(depth).resize(rgb.size, Image.Resampling.BILINEAR).convert("RGB")
        sheet = Image.new("RGB", (preview_width * (3 if midas_session else 2), preview_height))
        sheet.paste(rgb, (0, 0))
        sheet.paste(depth_image, (preview_width, 0))
        midas_ms = None
        if midas_session:
            midas_rgb = np.asarray(image.resize((256, 256), Image.Resampling.BICUBIC),
                                   dtype=np.float32) / 255
            midas_input = np.ascontiguousarray(midas_rgb.transpose(2, 0, 1)[None])
            start = time.perf_counter()
            midas_raw = midas_session.run(None, {"0": midas_input})[0][0]
            midas_ms = round((time.perf_counter() - start) * 1000)
            midas_low, midas_high = np.percentile(midas_raw, (2, 98))
            midas_depth = np.uint8(np.clip((midas_raw - midas_low)
                                           / max(midas_high - midas_low, 1e-6), 0, 1) * 255)
            midas_image = Image.fromarray(midas_depth).resize(rgb.size, Image.Resampling.BILINEAR)
            sheet.paste(midas_image.convert("RGB"), (preview_width * 2, 0))
        sheet_path = output_dir / f"{path.stem}_v2_compare.jpg"
        sheet.save(sheet_path, quality=88)
        rows.append({"file": path.name, "input": [width, height], "v2_inference_ms": round(inference_ms),
                     "midas_inference_ms": midas_ms,
                     "preview": str(sheet_path)})
        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
    (output_dir / "timings.json").write_text(
        json.dumps({"model": str(model_path), "load_ms": round(load_ms), "images": rows},
                   ensure_ascii=False, indent=2) + "\n"
    )


if __name__ == "__main__":
    main()
