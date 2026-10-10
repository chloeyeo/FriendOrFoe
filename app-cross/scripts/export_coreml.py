"""Export the iPhone detectors to Core ML with NMS (so Vision returns labelled boxes).

    FofCoco    YOLO11n, COCO 80 classes: person, bird (and the airplane/kite drone proxies)
    FofDrone   sapoepsilon/yolov11s-drone-detector (Apache-2.0): 'drone'
    FofDrone2  TomSmail/drone-yolo-v1 (CC-BY-4.0): 'quadcopter', 'fixed-wing', used only to confirm FofDrone

Chosen on 20 real drone photos (DJI Phantom 4, Inspire, Mavic 2/Pro/Mini/Mini 4, FPV racers, hexacopters, a VTOL
fixed-wing, a Parrot AR) against 168 drone-free COCO images: FofDrone at 0.78 alone keeps 14/20 and fires on 3/168;
requiring FofDrone2 to agree (a box of its own overlapping at IoU >= 0.3) keeps 14/20 and fires on 2/168. On its own
FofDrone2 adds false drones, so it only confirms. The COCO airplane/kite proxies found no drone FofDrone missed.
Usage: python export_coreml.py <work dir> <FofDrone .pt> <FofDrone2 .pt>; run through scripts/export-coreml.sh.
"""

import shutil
import sys
from pathlib import Path

from ultralytics import YOLO

work = Path(sys.argv[1])
work.mkdir(parents=True, exist_ok=True)

for name, weights in {"FofCoco": "yolo11n.pt", "FofDrone": sys.argv[2], "FofDrone2": sys.argv[3]}.items():
    model = YOLO(weights)
    print(name, "classes:", model.names)
    exported = Path(model.export(format="coreml", nms=True, imgsz=640, half=True))
    dest = work / f"{name}.mlpackage"
    if dest.exists():
        shutil.rmtree(dest)
    shutil.move(str(exported), dest)
    print(name, "->", dest)
