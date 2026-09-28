"""Exporta YOLO26n-seg a LiteRT 512 INT8.
Requiere el best.pt correcto del modelo móvil y data.yaml de calibración.
"""
from pathlib import Path
from ultralytics import YOLO

BEST = Path("best.pt")
DATA = Path("data.yaml")

if not BEST.exists():
    raise SystemExit("Falta best.pt (YOLO26n-seg móvil).")
if not DATA.exists():
    raise SystemExit("Falta data.yaml para calibración INT8.")

model = YOLO(str(BEST))
result = model.export(
    format="litert",
    imgsz=512,
    quantize=8,
    data=str(DATA),
    fraction=0.25,
    batch=1,
    device="cpu",
)
print(result)
