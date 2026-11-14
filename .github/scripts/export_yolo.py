import glob, os, shutil
from ultralytics import YOLO

model = YOLO("yolov8n.pt")
try:
    exported = model.export(format="tflite", int8=True, imgsz=320)
except Exception as e:
    print("INT8 fallback:", e)
    exported = model.export(format="tflite", imgsz=320)

os.makedirs("app/src/main/assets", exist_ok=True)
candidates = [str(exported)] if exported and os.path.exists(str(exported)) else []
if not candidates:
    candidates = sorted(
        f for f in glob.glob("**/*.tflite", recursive=True)
        if not f.startswith("app/src/main/assets")
    )

assert candidates, "No .tflite file found!"
print("Bundling model:", candidates[0])
shutil.copy(candidates[0], "app/src/main/assets/yolov8n.tflite")
