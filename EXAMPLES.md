# TGSPDCL AI Voice Call Assistant - Example Usage

This document provides example usage scenarios for the TGSPDCL AI Voice Call Assistant.

## Example 1: Staff Voice Update

A field staff member sends a voice update about an outage:

**Voice Message**: "Ramanapet lo incoming current poyindi. Oka 30 nimishalu padutundi. Panel daggara staff unnaru."

### System Processing:
1. Speech-to-text conversion using OpenAI Whisper
2. Information extraction from the voice message
3. Automatic update of outage information in the database

### Expected AI Response:
"Ramanapet lo current issue undi sir. Approximately 30 minutes padutundi."

## Example 2: Consumer Interaction

### Consumer Call:
"Current eppudu vastundi?"

### System Response:
"Ramanapet lo incoming current issue undi sir. Staff work chestunnaru. Approximately 30 minutes padutundi."

## Example 3: Escalation Process

For complex queries that indicate emergency situations:
* "Wire spark avutundi" (Wire is sparking)
* "Transformer smoke vastundi" (Transformer is smoking)

The system should recognize these as emergency situations and escalate to appropriate personnel.

## Example 4: Staff Update Process

1. Staff Member: "Cherlapally ki one hour padutundi"
2. System processes voice note
3. Database updated with:
   - Area: Cherlapally
   - Issue: Line breakdown (assumed from context)
   - ETA: 1 hour
   - Status: In progress

## Example 5: Consumer Query Process

Consumer calls with: "Current eppudu vastundi?"

System automatically responds:
"Cherlapally area lo line breakdown undi sir. Staff work chestunnaru. Approximately one hour padutundi."

## Technical Implementation Examples

### API Endpoints Usage:

#### 1. Send Voice Update
```
POST /api/v1/voice-update/
{
  "area": "Ramanapet",
  "issue": "Line Breakdown",
  "eta": "30 minutes",
  "status": "In Progress"
}
```

#### 2. Consumer Query
```
POST /api/v1/consumer-query/
{
  "area": "Ramanapet",
  "query": "Current eppudu vastundi?"
}
```

### Response:
```
{
  "response": "Ramanapet area lo line breakdown undi sir. Staff work chestunnaru. Approximately 30 minutes padutundi.",
  "outage_info": {
    "area": "Ramanapet",
    "issue": "Line Breakdown",
    "eta": "30 minutes",
    "status": "In Progress"
  }
}
```

## Benefits

This system helps:
* Reduce repeated calls during power outages
* Save LM/ALM time
* Improve consumer communication
* Allow field staff to focus on repair work instead of answering repeated questions