#!/usr/bin/env python3
"""Export the official WASB tennis checkpoint to a fixed-shape ONNX heatmap."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from omegaconf import OmegaConf
from torch import nn


WASB_SOURCE_COMMIT = "923462cacdeb3353b84ddebdedb3f4b7a8553b0f"
TENNIS_CHECKPOINT_SHA256 = "9d391239ab10c733f8e5bfadf16ab72838e7a8ebc88e8ae2038501c03d42b4bb"
INPUT_SHAPE = (1, 9, 288, 512)
OUTPUT_SHAPE = (1, 1, 288, 512)


class LastFrameHeatmap(nn.Module):
    def __init__(self, hrnet: nn.Module) -> None:
        super().__init__()
        self.hrnet = hrnet

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        heatmaps = self.hrnet(frames)[0]
        return torch.sigmoid(heatmaps[:, 2:3, :, :])


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_hrnet(source_root: Path, checkpoint_path: Path) -> LastFrameHeatmap:
    expected_revision = WASB_SOURCE_COMMIT
    actual_revision = subprocess.check_output(
        ["git", "-C", str(source_root), "rev-parse", "HEAD"], text=True
    ).strip()
    if actual_revision != expected_revision:
        raise ValueError(
            f"Expected WASB source {expected_revision}, found {actual_revision}"
        )

    checkpoint_sha256 = sha256_file(checkpoint_path)
    if checkpoint_sha256 != TENNIS_CHECKPOINT_SHA256:
        raise ValueError(
            "Checkpoint SHA-256 does not match the upstream tennis artifact: "
            f"{checkpoint_sha256}"
        )

    config_path = source_root / "src/configs/model/wasb.yaml"
    config = OmegaConf.load(config_path)
    model_path = source_root / "src/models/hrnet.py"
    spec = importlib.util.spec_from_file_location("wasb_hrnet_export", model_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load WASB HRNet source at {model_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    hrnet = module.HRNet(config)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    state_dict = checkpoint["model_state_dict"]
    hrnet.load_state_dict(state_dict, strict=True)
    hrnet.eval()
    return LastFrameHeatmap(hrnet).eval()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wasb-root", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--torch-threads", type=int, default=1)
    args = parser.parse_args()

    torch.set_num_threads(args.torch_threads)
    model = load_hrnet(args.wasb_root.resolve(), args.checkpoint.resolve())
    parameter_count = sum(parameter.numel() for parameter in model.parameters())
    if parameter_count != 1_481_427:
        raise ValueError(f"Unexpected WASB parameter count: {parameter_count}")

    sample = torch.zeros(INPUT_SHAPE, dtype=torch.float32)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (sample,),
        str(args.output),
        input_names=["frames"],
        output_names=["heatmap"],
        opset_version=18,
        dynamo=True,
    )

    onnx.checker.check_model(str(args.output))
    reference_input = np.random.default_rng(20261011).normal(
        0.0, 1.0, INPUT_SHAPE
    ).astype(np.float32)
    with torch.inference_mode():
        expected = model(torch.from_numpy(reference_input)).cpu().numpy()

    runtime = ort.InferenceSession(
        str(args.output), providers=["CPUExecutionProvider"]
    )
    actual = runtime.run(["heatmap"], {"frames": reference_input})[0]
    if actual.shape != OUTPUT_SHAPE:
        raise ValueError(f"Unexpected ONNX output shape: {actual.shape}")
    np.testing.assert_allclose(actual, expected, rtol=1e-3, atol=1e-4)

    metadata = {
        "source": "nttcom/WASB-SBDT",
        "sourceRevision": WASB_SOURCE_COMMIT,
        "checkpoint": "wasb_tennis_best.pth.tar",
        "checkpointSha256": sha256_file(args.checkpoint),
        "parameterCount": parameter_count,
        "inputName": "frames",
        "inputShapeNchw": list(INPUT_SHAPE),
        "inputPreprocessing": {
            "colorOrder": "RGB",
            "layout": "NCHW",
            "frameOrder": "oldest-to-newest",
            "normalization": "(pixel/255 - ImageNetMean) / ImageNetStd",
            "mean": [0.485, 0.456, 0.406],
            "std": [0.229, 0.224, 0.225],
        },
        "outputName": "heatmap",
        "outputShapeNchw": list(OUTPUT_SHAPE),
        "outputActivation": "sigmoid of third input-frame heatmap",
        "onnxOpset": 18,
        "parityInputSeed": 20261011,
        "parityMaxAbsoluteError": float(np.max(np.abs(actual - expected))),
        "exporterTorchVersion": torch.__version__,
        "onnxRuntimeVersion": ort.__version__,
    }
    metadata_path = args.output.with_suffix(args.output.suffix + ".json")
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()