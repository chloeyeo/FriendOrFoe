#!/bin/bash
# Builds the iPhone build's Core ML detectors into modules/fof-native/ios/Models (gitignored), then run
# `cd ios && pod install` (or `npx expo run:ios`) so they are copied into the app.
# Needs macOS with Xcode (coremlcompiler), uv, and network once (pip packages, YOLO11n, the two drone detectors).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${ROOT}/.coreml"
VENV="${WORK}/venv"
MODELS="${ROOT}/modules/fof-native/ios/Models"
DRONE_URL="https://huggingface.co/sapoepsilon/yolov11s-drone-detector/resolve/main/best.pt"
DRONE2_URL="https://huggingface.co/TomSmail/drone-yolo-v1/resolve/main/best.pt"

mkdir -p "${WORK}" "${MODELS}"
if [[ ! -x "${VENV}/bin/python" ]]; then
  uv venv --python 3.12 "${VENV}"
fi
# coremltools 9.0 converts with torch <= 2.7 and numpy 1.x; newer ones fail inside the converter.
VIRTUAL_ENV="${VENV}" uv pip install "ultralytics" "coremltools==9.0" "torch==2.7.1" "torchvision==0.22.1" "numpy==1.26.4"

[[ -s "${WORK}/drone.pt" ]] || curl -fL -o "${WORK}/drone.pt" "${DRONE_URL}"
[[ -s "${WORK}/drone2.pt" ]] || curl -fL -o "${WORK}/drone2.pt" "${DRONE2_URL}"

(cd "${WORK}" && "${VENV}/bin/python" "${ROOT}/scripts/export_coreml.py" "${WORK}/out" "${WORK}/drone.pt" "${WORK}/drone2.pt")

rm -rf "${MODELS}"/*.mlmodelc
for name in FofCoco FofDrone FofDrone2; do
  xcrun coremlcompiler compile "${WORK}/out/${name}.mlpackage" "${MODELS}"
done
du -sh "${MODELS}"/*.mlmodelc
