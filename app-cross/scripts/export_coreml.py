"""Export the iPhone detectors to Core ML with NMS (so Vision returns labelled boxes).

    FofCoco    YOLO11n, COCO 80 classes: person, bird (and the airplane/kite drone proxies)
    FofDrone   sapoepsilon/yolov11s-drone-detector (Apache-2.0): 'drone'

Chosen on 13 real drone photos (DJI Phantom 4, Inspire, Mavic 2/Pro/Mini, FPV racers, a VTOL fixed-wing) against 40
drone-free COCO val2017 images: at the iPhone's 0.6 floor it keeps 12/13 drones and fires on 4/40 images. A second
detector (TomSmail/drone-yolo-v1) only added false drones (6/40 at 0.6), and the COCO airplane/kite proxies found no
drone FofDrone missed, so neither is used.
Usage: python export_coreml.py <work dir> <FofDrone .pt>; run through scripts/export-coreml.sh.
"""

import shutil
import sys
from pathlib import Path

from ultralytics import YOLO

work = Path(sys.argv[1])
work.mkdir(parents=True, exist_ok=True)

for name, weights in {"FofCoco": "yolo11n.pt", "FofDrone": sys.argv[2]}.items():
    model = YOLO(weights)
    print(name, "classes:", model.names)
    exported = Path(model.export(format="coreml", nms=True, imgsz=640, half=True))
    dest = work / f"{name}.mlpackage"
    if dest.exists():
        shutil.rmtree(dest)
    shutil.move(str(exported), dest)
    print(name, "->", dest)
