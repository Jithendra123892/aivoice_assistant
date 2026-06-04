# Setup Guide - TGSPDCL AI Voice Call Assistant

## Prerequisites

Before setting up the project, ensure you have the following installed:
- Python 3.8 or higher
- pip (Python package manager)
- Git (for version control)

## Installation Steps

### 1. Clone the Repository
```bash
git clone <repository-url>
cd aivoice_assistant
```

### 2. Create Virtual Environment (Recommended)
```bash
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
```

### 3. Install Dependencies
```bash
pip install -r requirements.txt
```

### 4. Configure Environment Variables
Copy the example environment file and update with your credentials:
```bash
cp .env.example .env
```

Edit the `.env` file with your actual API keys:
- OpenAI API key
- Twilio credentials (for production deployment)
- ElevenLabs API key (for voice synthesis)

### 5. Database Setup
The application uses SQLite by default. No additional setup is required for basic functionality.

### 6. Run the Application
```bash
python start_server.py
```

Or use the provided startup script:
```bash
./startup.sh
```

## Testing the Application

### Using curl commands:

1. Send a voice update:
```bash
curl -X POST "http://localhost:8000/api/v1/voice-update/" \
     -H "Content-Type: application/json" \
     -d '{"area": "Ramanapet", "issue": "Line Breakdown", "eta": "30 minutes", "status": "In Progress"}'
```

2. Query outage information:
```bash
curl -X GET "http://localhost:8000/api/v1/outage-info/Ramanapet"
```

3. Process consumer query:
```bash
curl -X POST "http://localhost:8000/api/v1/consumer-query/" \
     -H "Content-Type: application/json" \
     -d '{"area": "Ramanapet", "query": "Current eppudu vastundi?"}'
```

## Development Guidelines

### Project Structure
- `api/`: API routes and endpoints
- `models/`: Database models and managers
- `voice_processing/`: Voice processing modules
- `config/`: Configuration files
- `utils/`: Utility functions
- `tests/`: Test files (to be implemented)

### Adding New Features

1. Create a new branch for your feature:
```bash
git checkout -b feature/new-feature-name
```

2. Implement your changes
3. Test thoroughly
4. Commit and push your changes
5. Create a pull request

### API Documentation

The API documentation is available at:
- Swagger UI: `http://localhost:8000/docs`
- ReDoc: `http://localhost:8000/redoc`

## Troubleshooting

### Common Issues

1. **Module not found errors**
   - Ensure all dependencies are installed: `pip install -r requirements.txt`
   - Check that you're in the correct virtual environment

2. **Database connection errors**
   - Verify that the database file has proper permissions
   - Check the DATABASE_URL in your .env file

3. **API key errors**
   - Verify that all required API keys are set in the .env file
   - Check that your API keys are valid and have proper permissions

### Logging

The application logs information to the console. For more detailed logging, you can adjust the LOG_LEVEL in your .env file.

## Deployment

For production deployment, consider:

1. Using a production-grade server like Gunicorn
2. Setting up a proper database (PostgreSQL recommended)
3. Configuring SSL certificates
4. Setting up proper error handling and monitoring
5. Implementing authentication for API endpoints if needed

## Contributing

1. Fork the repository
2. Create your feature branch
3. Commit your changes
4. Push to the branch
5. Open a pull request

Please ensure your code follows the existing style and includes appropriate tests.