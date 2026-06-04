from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import Optional, List
import os
from datetime import datetime
import json

# Consumer Query Models
class ConsumerQueryRequest(BaseModel):
    area: str
    query: str

class ConsumerQueryResponse(BaseModel):
    response: str
    outage_info: dict

# Staff Voice Update Models
class StaffVoiceUpdate(BaseModel):
    area: str
    issue: str
    eta: str
    status: str
    staff_name: Optional[str] = None

class StaffVoiceUpdateResponse(BaseModel):
    message: str
    data: dict