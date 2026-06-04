# TGSPDCL AI Voice Call Assistant - Documentation

## Project Overview

The TGSPDCL AI Voice Call Assistant is an innovative solution designed to help electricity department staff handle the overwhelming number of calls during power outages in rural Telangana areas. This system allows field staff to communicate outage information through simple voice messages, which are then used to automatically respond to consumer inquiries with natural Telugu responses.

## Key Features

### Voice-Based Staff Updates
Field staff can simply send voice messages instead of typing detailed reports:
> "Ramanapet lo incoming current poyindi. 30 nimishalu padutundi."

The system processes these voice updates and extracts:
- Area information
- Type of issue
- Estimated time of restoration
- Current status

### Automatic Consumer Response System
When consumers call asking about power outages, they receive automatic responses in natural Telugu:
> "Ramanapet lo line breakdown undi sir. Staff work chestunnaru. Approximately 30 minutes padutundi."

### Smart Call Routing
The system intelligently routes complex queries:
- Simple queries: "Current eppudu vastundi?" → Automatic response
- Complex queries: "Transformer smoke vastundi" → Escalation to supervisor

## Technical Architecture

### System Components

1. **Voice Processing Module**
   - Speech-to-text conversion using OpenAI Whisper
   - Natural language processing for information extraction
   - Response generation in Telugu

2. **Database Management**
   - Outage information storage
   - Consumer query logging
   - Staff update tracking

3. **API Services**
   - Staff voice update endpoint
   - Consumer query processing endpoint
   - Outage information retrieval

### Data Flow

1. Staff Member → Voice Update → Speech Processing → Information Extraction → Database Storage
2. Consumer Call → Query Processing → AI Response → Audio Generation → Call Response

## API Endpoints

### POST /api/v1/voice-update/
Staff can send voice updates through this endpoint:
```json
{
  "area": "Ramanapet",
  "issue": "Line breakdown",
  "eta": "30 minutes",
  "status": "In Progress"
}
```

### GET /api/v1/outage-info/{area}
Retrieve outage information for a specific area:
```json
{
  "area": "Cherlapally",
  "issue": "Line breakdown",
  "eta": "1 hour",
  "status": "Repair in progress"
}
```

### POST /api/v1/consumer-query/
Process consumer queries:
```json
{
  "query": "Current eppudu vastundi?",
  "area": "Cherlapally"
}
```

## Future Enhancements

1. **Voice Cloning**
   - Personalized responses using staff's own voice
   - Custom voice for different regions/dialects

2. **Emergency Response Integration**
   - Automatic escalation for critical issues
   - Real-time outage mapping

3. **Mobile Application**
   - Staff mobile app for voice updates
   - Real-time location tracking
   - Offline capabilities