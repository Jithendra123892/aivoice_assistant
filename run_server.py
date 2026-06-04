#!/usr/bin/env python3
"""
Run the TGSPDCL AI Voice Call Assistant server
"""

import os
import sys

# Add the project root to the path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import the required modules
from main import app
import uvicorn

if __name__ == "__main__":
    # Run the FastAPI application
    uvicorn.run("aivoice_assistant.main:app", host="0.0.0.0", port=8000, log_level="info", reload=True)