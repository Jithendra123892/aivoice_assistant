from fastapi import APIRouter, Request
from twilio.twiml.voice_response import VoiceResponse
from fastapi import Response
from typing import Optional
import os
import sys

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Initialize the database manager
from models.database import DatabaseManager

# Twilio webhook endpoint for handling incoming calls
router = APIRouter(prefix="/api/v1", tags=["twilio"])

@router.post("/twilio/voice")
async def twilio_voice_handler(request: Request):
# Handle incoming voice calls from consumers
# Create a TwiML response
resp = VoiceResponse()

# Get caller information
form_data = await request.form()
caller_number = form_data.get('From', 'Unknown')

# For demonstration, we'll provide a generic response
# In a real implementation, this would use our outage database
# to provide relevant information to the caller

# Gather input from caller
resp.gather(
action="/api/v1/twilio/gather",
method="POST",
num_digits=1,
timeout=5
)

resp.say("Thank you for calling TGSPDCL. Please wait while we connect you to our automated system.", voice='Polly.Emma')

# Return the TwiML response
return Response(content=str(resp), media_type="text/xml")

@router.post("/twilio/gather")
async def twilio_gather_handler(request: Request):
# Handle gathered input from caller
# Create a TwiML response
resp = VoiceResponse()

# Get gathered input
form_data = await request.form()
digits = form_data.get('Digits', '')

# Process the gathered input
if digits == '1':
resp.say("You have selected to report an outage. Please hold while we connect you to our staff.")
# In a real implementation, this would transfer the call to staff
elif digits == '2':
resp.say("You have selected to check outage status. Please hold.")
# In a real implementation, this would check outage status
else:
resp.say("Invalid input. Please try again.")

# Return the TwiML response
return Response(content=str(resp), media_type="text/xml")