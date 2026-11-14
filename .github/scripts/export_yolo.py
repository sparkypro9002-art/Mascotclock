import glob, os, shutil
from ultralytics import YOLO

model = YOLO("yolov8n.pt")
try:
    model.export(format="tflite", int8=True, imgsz=320)
except Exception as e:
    print("INT8 fallback:", e)
    model.export(format="tflite", imgsz=320)

os.makedirs("app/src/main/assets", exist_ok=True)
files = sorted(glob.glob("yolov8n_saved_model/*.tflite"))
assert files, "No .tflite file generated!"
print("Bundling model:", files[0])
shutil.copy(files[0], "app/src/main/assets/yolov8n.tflite")
