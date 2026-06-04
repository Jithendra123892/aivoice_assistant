from fastapi import APIRouter, UploadFile, File, HTTPException
from pydantic import BaseModel
from typing import Optional, List
import os
from datetime import datetime
import json
import sqlite3
import sys

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import our modules
from models.database import DatabaseManager
from voice_processing.voice_processor import VoiceProcessor
from config.settings import Config
from models.nlp_model import CustomNLPModel

# Initialize components
router = APIRouter(prefix="/api/v1", tags=["voice_assistant"])

# Initialize database, voice processor and NLP models
db = DatabaseManager()
voice_processor = VoiceProcessor(Config.OPENAI_API_KEY)
nlp_model = CustomNLPModel()

class VoiceUpdateRequest(BaseModel):
    area: str
    issue: str
    eta: str
    status: str
    staff_name: Optional[str] = None

class ConsumerQueryRequest(BaseModel):
    area: str
    query: str

class VoiceNoteRequest(BaseModel):
    staff_id: str
    update_text: str

class AssistantToggleRequest(BaseModel):
    is_active: bool

class SignupRequest(BaseModel):
    name: str
    phone: str
    substation: str
    employee_id: str
    password: str
    cadre: str

class LoginRequest(BaseModel):
    employee_id: str
    password: str

class OutageStatusUpdateRequest(BaseModel):
    area: str
    status: str
    staff_name: Optional[str] = None

@router.get("/")
async def root():
    return {"message": "TGSPDCL AI Voice Call Assistant API"}

@router.post("/voice-update/")
async def process_voice_update(voice_update: VoiceUpdateRequest):
    """Process voice update from field staff"""
    try:
        # Prevent database updates if the area is unrecognized, empty, or "Unknown"
        area = voice_update.area
        if not area or area.lower() == "unknown" or area.lower() == "none" or area.strip() == "":
            raise HTTPException(status_code=400, detail="Cannot update outage for unrecognized or empty area.")

        # Store the outage information in database
        db.update_outage_info(
            area=area,
            issue=voice_update.issue,
            eta=voice_update.eta,
            status=voice_update.status,
            staff_name=voice_update.staff_name
        )

        return {
            "message": "Voice update processed successfully",
            "data": voice_update.dict()
        }
    except HTTPException as he:
        raise he
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice update: {str(e)}")

@router.get("/outage-info/{area}")
async def get_outage_info(area: str):
    """Get outage information for a specific area"""
    outage_info = db.get_outage_info(area)
    if outage_info:
        return outage_info
    else:
        raise HTTPException(status_code=404, detail="Area not found")

@router.get("/all-outages/")
async def get_all_outages():
    """Get all outage information"""
    outages = db.get_all_outages()
    return {"outages": outages}

@router.get("/assistant-state/")
async def get_assistant_state():
    """Get the current AI assistant active state and settings"""
    return db.get_assistant_state()

@router.post("/assistant-state/toggle/")
async def toggle_assistant_state(req: AssistantToggleRequest):
    """Toggle the AI assistant active state"""
    db.set_assistant_state(req.is_active)
    return {"message": f"AI Assistant status updated to {req.is_active}", "is_active": req.is_active}

@router.post("/auth/signup/")
async def auth_signup(req: SignupRequest):
    """Register a new lineman account"""
    success = db.create_user(
        name=req.name,
        phone=req.phone,
        substation=req.substation,
        staff_id=req.employee_id,
        password=req.password,
        cadre=req.cadre
    )
    if success:
        return {"message": "Account created successfully", "employee_id": req.employee_id}
    else:
        raise HTTPException(status_code=400, detail="Employee ID or Phone Number already exists")

@router.post("/auth/login/")
async def auth_login(req: LoginRequest):
    """Verify lineman credentials"""
    user = db.verify_user(req.employee_id, req.password)
    if user:
        return {
            "message": "Login successful",
            "user": {
                "name": user["name"],
                "phone": user["phone"],
                "substation": user["substation"],
                "employee_id": user["staff_id"],
                "cadre": user["cadre"]
            }
        }
    else:
        raise HTTPException(status_code=401, detail="Invalid Employee ID or Password")

@router.post("/outage/status/")
async def update_outage_status(req: OutageStatusUpdateRequest):
    """Update outage status and record the exact time of changed status"""
    try:
        # Fetch current outage details
        info = db.get_outage_info(req.area)
        if not info:
            raise HTTPException(status_code=404, detail="Outage area not found")
        
        # Update status in SQLite and record the current time in last_updated
        db.update_outage_info(
            area=req.area,
            issue=info["issue"],
            eta=info["eta"],
            status=req.status,
            staff_name=req.staff_name or info["staff_name"]
        )
        
        # Retrieve fresh updated info to get the actual recorded timestamp
        updated_info = db.get_outage_info(req.area)
        return {
            "message": "Outage status updated successfully",
            "area": req.area,
            "status": req.status,
            "last_updated": updated_info.get("last_updated", datetime.now().isoformat())
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error updating outage status: {str(e)}")

@router.post("/consumer-query/")
async def process_consumer_query(query: ConsumerQueryRequest):
    """Process consumer query and generate response"""
    # Check if the AI Call Assistant permission toggle is active!
    settings = db.get_assistant_state()
    if not settings["is_active"]:
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text, 
            "outage_info": None, 
            "forwarded": True, 
            "lineman_phone": settings['lineman_phone']
        }

    # Check if the query is a complex "more question" or emergency
    lower_query = query.query.lower()
    complex_keywords = [
        "spark", "smoke", "poga", "wire", "sound", "noise", "transformer", 
        "evareyna", "operator", "officer", "office", "lineman", "lm", "alm",
        "pole", "pol", "shock", "fire", "manta", "nippu", "meter", "bill",
        "complaint", "substation", "evaru", "who", "why"
    ]
    
    is_complex = False
    for kw in complex_keywords:
        if kw in lower_query:
            if kw == "why" and "current ledu" in lower_query:
                continue
            is_complex = True
            break

    # If it is a complex query, immediately reply and forward to operator!
    if is_complex:
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": settings['lineman_phone']
        }

    outage_info = db.get_outage_info(query.area)

    if outage_info:
        # Generate response based on status
        status = outage_info.get("status", "")
        if status.lower() in ["solved", "restored"]:
            response_text = f"Mee area {query.area} lo power supply issue already solved aypoyindi sir. Power clear aindi, oka sari check cheskondi sir. Cooperation ki dhanyavadalu."
        else:
            if voice_processor:
                response_text = voice_processor.generate_response(query.query, outage_info)
            else:
                response_text = f"{query.area} area lo {outage_info['issue']} undi sir. Staff clear chestunnaru. Approximately {outage_info['eta']} padutundi. Meeru cooperate cheyandi sir."

        # Log the query and response
        db.log_consumer_query(query.area, query.query, response_text)
        return {"response": response_text, "outage_info": outage_info, "forwarded": False, "lineman_phone": None}
    else:
        # No database outage entry exists for this area. Forward call!
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": settings['lineman_phone']
        }

@router.post("/voice-note/")
async def process_voice_note(file: UploadFile = File(...)):
    """Process voice note from field staff"""
    try:
        # Save the uploaded file
        file_path = f"temp_voice_notes/{file.filename}"
        os.makedirs("temp_voice_notes", exist_ok=True)

        with open(file_path, "wb") as buffer:
            content = await file.read()
            buffer.write(content)

        # Convert speech to text
        transcribed_text = voice_processor.convert_speech_to_text(file_path)

        # Process with NLP model
        processed_info = nlp_model.process_text(transcribed_text)

        # Update database with extracted information if area is valid and recognized (not "Unknown")
        area = processed_info["entities"].get("area")
        if area and area.lower() != "unknown" and area.lower() != "none" and area.strip() != "":
            db.update_outage_info(
                area=area,
                issue=processed_info["entities"].get("issue", "Unknown Issue"),
                eta=processed_info["entities"].get("eta", "Not Specified"),
                status="In Progress"
            )

        return {
            "message": "Voice note processed successfully",
            "transcribed_text": transcribed_text,
            "processed_info": processed_info
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice note: {str(e)}")

@router.post("/staff-voice-update/")
async def process_staff_voice_update(voice_update: VoiceNoteRequest):
    """Process staff voice update"""
    try:
        # Process with NLP model
        processed_info = nlp_model.process_text(voice_update.update_text)

        # If emergency detected, escalate immediately
        if processed_info["is_emergency"]:
            return {
                "message": "EMERGENCY DETECTED - Immediate escalation required",
                "is_emergency": True,
                "processed_info": processed_info
            }

        # Update database with extracted information if area is valid and recognized (not "Unknown")
        area = processed_info["entities"].get("area")
        if area and area.lower() != "unknown" and area.lower() != "none" and area.strip() != "":
            db.update_outage_info(
                area=area,
                issue=processed_info["entities"].get("issue", "Unknown Issue"),
                eta=processed_info["entities"].get("eta", "Not Specified"),
                status="In Progress",
                staff_name=voice_update.staff_id
            )

        return {
            "message": "Staff voice update processed successfully",
            "processed_info": processed_info
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing staff voice update: {str(e)}")