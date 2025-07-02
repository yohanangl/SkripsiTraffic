# Correct import for ngrok.connect
from pyngrok import ngrok
import uvicorn
import threading
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from ultralytics import YOLO
import io
from PIL import Image

app = FastAPI()

# Load the trained YOLOv10s model
# Make sure the path to your best.pt model is correct
try:
    model = YOLO("D:/Skripsi/use_datasetSkripsi3/imagesize312/batch8_ep100/weights/best.pt")
    print("Model loaded successfully!")
except Exception as e:
    print(f"Error loading model: {e}")
    model = None

@app.get("/")
async def read_root():
    return {"message": "YOLOv10s Traffic Sign Detection API"}

@app.post("/predict/")
async def predict_image(file: UploadFile = File(...)): 
    if model is None:
        raise HTTPException(status_code=500, detail="Model not loaded. Check server logs for errors.")

    contents = await file.read()
    try:
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception:
        raise HTTPException(status_code=400, detail="Could not decode image file.")

    results = model(image)

    detections = []
    for r in results:
        for *xyxy, conf, cls in r.boxes.data:
            x1, y1, x2, y2 = map(float, xyxy)
            confidence = float(conf)
            class_id = int(cls)
            class_name = model.names[class_id]

            detections.append({
                "box": [x1, y1, x2, y2],
                "confidence": confidence,
                "class_id": class_id,
                "class_name": class_name
            })

    return JSONResponse(content={"detections": detections})

# Function to run FastAPI app
def run_api():
    uvicorn.run(app, host="0.0.0.0", port=8000)

# Start FastAPI in a separate thread
api_thread = threading.Thread(target=run_api)
api_thread.start()

# --- NGROK AUTHENTICATION STEP ---
# IMPORTANT: Replace "YOUR_NGROK_AUTH_TOKEN" with your actual token
# You can get it from https://dashboard.ngrok.com/get-started/your-authtoken
ngrok.set_auth_token("2xfTHluqFcckbB6HqFkFG8qIKgf_6q7b5Hm6APVPp6qihmd93") 

# Use ngrok to expose the FastAPI app to the internet
try:
    ngrok_tunnel = ngrok.connect(8000)
    print(f"Public URL: {ngrok_tunnel.public_url}")
except Exception as e:
    print(f"Failed to create ngrok tunnel: {e}")