import os
import sys
from pathlib import Path

# Add the project root to the system path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from api.routes import router

# Create the main application
app = FastAPI(
    title="TGSPDCL AI Voice Assistant",
    description="AI Voice Assistant for TGSPDCL",
    version="1.0.0"
)

# Serve static files from the static directory
static_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
os.makedirs(static_dir, exist_ok=True)
app.mount("/static", StaticFiles(directory=static_dir), name="static")

@app.get("/")
async def serve_index():
    # Return index.html from the static directory
    index_path = os.path.join(static_dir, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return {"message": "TGSPDCL AI Voice Call Assistant API is active. Please add static/index.html to view the dashboard."}

# Include the API router
app.include_router(router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)