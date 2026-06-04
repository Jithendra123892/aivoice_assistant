from fastapi import FastAPI
from .api import router

# Create the main FastAPI application
app = FastAPI(
title="TGSPDCL AI Voice Assistant",
description="AI Voice Assistant for TGSPDCL",
version="1.0.0"
)

# Include the API router
app.include_router(router)