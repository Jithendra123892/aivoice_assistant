#!/bin/bash
# test_app.sh - Script to run the test application

echo "🧪 Running TGSPDCL AI Voice Call Assistant Tests"
echo "================================================"

# Check if the server is running
echo "Checking if server is running..."
curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/ | grep -q "200"
if [ $? -eq 0 ]; then
    echo "✅ Server is running"
else
    echo "⚠️  Server doesn't appear to be running. Please start the server with:"
    echo "   python start_server.py"
    echo "   or"
    echo "   ./startup.sh"
    echo ""
    read -p "Would you like to start the server now? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Starting server in background..."
        python start_server.py &
        SERVER_PID=$!
        echo "Server started with PID: $SERVER_PID"
        echo "Waiting 5 seconds for server to initialize..."
        sleep 5
    else
        echo "Please start the server manually and run this script again."
        exit 1
    fi
fi

echo ""
echo "Running tests..."
echo "================"

# Run the test script
python test_app.py

echo ""
echo "Test execution completed."