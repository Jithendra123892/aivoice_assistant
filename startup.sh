#!/bin/bash
# startup.sh

# Install required packages
pip install -r requirements.txt

# Start the application server
python start_server.py