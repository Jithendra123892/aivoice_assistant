# TGSPDCL Smart Voice Assistant - Complete Application

This is a complete implementation of the TGSPDCL Smart Voice Assistant for electricity outage management.

## Features

1. Voice-based staff updates
2. Automated consumer call handling
3. Custom NLP for intent classification and entity extraction
4. Multi-language support (Telugu, Hindi, English)
5. Emergency detection and escalation
6. Twilio integration for phone calls
7. Database management for outage information

## Installation

1. Install the required packages:
   ```
   pip install -r requirements.txt
   ```

2. Set up environment variables in `.env`:
   ```
   TWILIO_ACCOUNT_SID=your_account_sid
   TWILIO_AUTH_TOKEN=your_auth_token
   OPENAI_API_KEY=your_openai_api_key
   ```

3. Run the application:
   ```
   uvicorn main:app --reload
   ```

## API Endpoints

- POST /api/v1/voice-update/ - Process voice update from field staff
- GET /api/v1/outage-info/{area} - Get outage information for a specific area
- GET /api/v1/all-outages/ - Get all outage information
- POST /api/v1/consumer-query/ - Process consumer query and generate response
- POST /api/v1/voice-note/ - Process voice note from field staff
- POST /api/v1/staff-voice-update/ - Process staff voice update
- POST /api/v1/twilio/voice - Handle incoming voice calls from consumers
- POST /api/v1/twilio/gather - Handle gathered input from caller

## Directory Structure

- `api/` - API routes and models
- `config/` - Configuration files
- `models/` - Database models and NLP models
- `voice_processing/` - Voice processing utilities
- `main.py` - Main application file
- `requirements.txt` - Required packages