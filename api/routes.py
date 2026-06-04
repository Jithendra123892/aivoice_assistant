from fastapi import APIRouter, UploadFile, File, HTTPException, Form
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

try:
    import google.generativeai as genai
    if Config.GEMINI_API_KEY:
        genai.configure(api_key=Config.GEMINI_API_KEY)
except Exception as e:
    print(f"Error importing or configuring google-generativeai: {e}")
    genai = None

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
    reason: Optional[str] = None

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
            staff_name=req.staff_name or info["staff_name"],
            reason=req.reason
        )
        
        # Retrieve fresh updated info to get the actual recorded timestamp
        updated_info = db.get_outage_info(req.area)
        return {
            "message": "Outage status updated successfully",
            "area": req.area,
            "status": req.status,
            "reason": updated_info.get("reason"),
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

    # Fetch outage info from database
    outage_info = db.get_outage_info(query.area)

    # Use Gemini if key is provided and import succeeded
    if genai and Config.GEMINI_API_KEY:
        try:
            prompt = f"""
            You are the TGSPDCL AI Voice Call Assistant, a friendly and polite customer service assistant responding to a consumer on a phone call.
            The consumer's query is: "{query.query}"
            The consumer's area is: "{query.area}"
            
            Current Outage Status from the database for the area:
            {json.dumps(outage_info) if outage_info else "No active outage record found for this area."}
            
            Instructions:
            1. Generate a friendly, polite, conversational reply in Telugu (using Telugu script). Keep it natural for voice communication.
            2. If there is an active outage in the database (status is In Progress, Pending, etc.):
               - Politely inform the consumer about the outage status, the cause (issue), and the ETA (Estimated Time of Restoration) in Telugu. Keep it simple, clear, and reassuring. Ask them to cooperate (e.g., 'meeru dayachesi cooperate cheyandi sir').
            3. If the outage is marked as Solved, Completed, or Restored:
               - Inform them that power has been restored: say "current vachindi chudandi sir" (current came, check it) in Telugu.
               - Also, mention the reason why the power was interrupted if there is a reason for interruption recorded in the database (e.g., 'interruption ki karanam <reason>'). Thank them for their cooperation.
            4. If the consumer asks complex or unrelated questions (e.g. regarding billing, contact details or phone numbers, complaints, reporting sparks/fire emergency, asking to speak to a supervisor/officer/operator/lineman, or any query other than simple power outage checks), OR if no outage record exists for their area in the database:
               - Set "should_forward" to true in your JSON output.
               - In the "response" text, politely inform them that you are forwarding their call to the substation operator/lineman. For example: "Oka minute aagandi sir, mee call ni substation operator ki forward chestunnamu. Valle ki cheppandi mee samasya."
            5. If they just ask simple power status check questions (like 'current eppudu vastundi', 'current enduku poyindi') and we have the active database outage info, answer it and set "should_forward" to false.

            You MUST respond in JSON format with two fields:
            {{
              "response": "The Telugu text to speak to the caller",
              "should_forward": true/false
            }}
            """
            
            model_name = "gemini-1.5-flash"
            try:
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
            except Exception as model_err:
                print(f"Failed to use model {model_name}, trying gemini-2.5-flash: {model_err}")
                model_name = "gemini-2.5-flash"
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
            
            result = json.loads(response.text)
            response_text = result.get("response", "")
            should_forward = result.get("should_forward", False)
            
            db.log_consumer_query(query.area, query.query, response_text)
            return {
                "response": response_text,
                "outage_info": outage_info,
                "forwarded": should_forward,
                "lineman_phone": settings['lineman_phone'] if should_forward else None
            }
        except Exception as gemini_err:
            print(f"Gemini API execution error: {str(gemini_err)}. Falling back to rule-based logic.")

    # Fallback rule-based logic if Gemini is not available or fails
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

    if outage_info:
        # Generate response based on status
        status = outage_info.get("status", "")
        reason = outage_info.get("reason", "")
        if status.lower() in ["solved", "restored", "completed"]:
            reason_text = f" Interruption ki karanam {reason} sir." if reason else ""
            response_text = f"Mee area {query.area} lo current vachindi chudandi sir.{reason_text} Cooperation ki dhanyavadalu."
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

@router.get("/call-logs/")
async def get_call_logs():
    """Retrieve all call logs"""
    try:
        logs = db.get_all_call_logs()
        return {"logs": logs}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error retrieving call logs: {str(e)}")

@router.post("/call-logs/")
async def create_call_log(
    caller_number: str = Form(...),
    transcript: str = Form(...),
    status: str = Form(...),
    file: Optional[UploadFile] = File(None)
):
    """Create a new call log and save its recording file if uploaded"""
    try:
        audio_path = ""
        if file:
            project_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            recordings_dir = os.path.join(project_dir, "static", "recordings")
            os.makedirs(recordings_dir, exist_ok=True)
            
            # Save the file
            filename = f"rec_{int(datetime.now().timestamp())}_{file.filename}"
            file_dest = os.path.join(recordings_dir, filename)
            with open(file_dest, "wb") as buffer:
                content = await file.read()
                buffer.write(content)
            audio_path = f"/static/recordings/{filename}"
            
        inserted_id = db.save_call_log(
            caller_number=caller_number,
            transcript=transcript,
            audio_path=audio_path,
            status=status
        )
        return {
            "message": "Call log saved successfully",
            "id": inserted_id,
            "audio_path": audio_path
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error saving call log: {str(e)}")