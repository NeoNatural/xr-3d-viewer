#!/usr/bin/env python3
"""Estimate frame-to-frame depth change in RGB|V2|MiDaS preview sheets.

This is a diagnostic, not a ground-truth accuracy measure. It rejects pairs
with poor RGB flow agreement (usually cuts or fast camera movement).
"""

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def grayscale(image):
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sheets", type=Path)
    args = parser.parse_args()
    paths = sorted(args.sheets.glob("frame_*_v2_compare.jpg"))
    if len(paths) < 2:
        parser.error("need at least two comparison sheets")
    pairs = []
    for previous, current in zip(paths, paths[1:]):
        first, second = cv2.imread(str(previous)), cv2.imread(str(current))
        height, width = first.shape[:2]
        panel = width // 3
        old_rgb, new_rgb = grayscale(first[:, :panel]), grayscale(second[:, :panel])
        # Backward flow maps a pixel in the current image to its position in the previous one.
        flow = cv2.calcOpticalFlowFarneback(new_rgb, old_rgb, None, .5, 3, 21, 3, 5, 1.2, 0)
        x, y = np.meshgrid(np.arange(panel, dtype=np.float32),
                           np.arange(height, dtype=np.float32))
        map_x, map_y = x + flow[..., 0], y + flow[..., 1]
        aligned_rgb = cv2.remap(old_rgb, map_x, map_y, cv2.INTER_LINEAR)
        valid = ((np.abs(aligned_rgb.astype(np.int16) - new_rgb.astype(np.int16)) < 25)
                 & (map_x >= 0) & (map_x < panel) & (map_y >= 0) & (map_y < height))
        row = {"from": previous.name, "to": current.name, "flow_valid_fraction": round(float(valid.mean()), 3)}
        for index, model in ((1, "v2"), (2, "midas")):
            old = grayscale(first[:, index * panel:(index + 1) * panel]).astype(np.float32) / 255
            new = grayscale(second[:, index * panel:(index + 1) * panel]).astype(np.float32) / 255
            aligned = cv2.remap(old, map_x, map_y, cv2.INTER_LINEAR)
            row[model + "_mae"] = round(float(np.abs(aligned - new)[valid].mean()), 4)
        pairs.append(row)
    stable = [pair for pair in pairs if pair["flow_valid_fraction"] >= .9]
    print(json.dumps({"pairs": pairs, "stable_pair_count": len(stable),
                      "stable_v2_mean": round(float(np.mean([p["v2_mae"] for p in stable])), 4),
                      "stable_midas_mean": round(float(np.mean([p["midas_mae"] for p in stable])), 4)},
                     indent=2))


if __name__ == "__main__":
    main()
