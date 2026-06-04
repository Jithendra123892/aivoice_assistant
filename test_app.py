#!/usr/bin/env python3
"""
Test script for TGSPDCL AI Voice Call Assistant
This script demonstrates the basic functionality of the application.
"""

import requests
import json
import time

# Base URL for the API
BASE_URL = "http://localhost:8000"

def test_api_health():
    """Test if the API is running"""
    try:
        response = requests.get(f"{BASE_URL}/")
        if response.status_code == 200:
            print("✅ API is running")
            return True
        else:
            print("❌ API is not responding correctly")
            return False
    except requests.exceptions.ConnectionError:
        print("❌ Could not connect to the API. Is the server running?")
        return False

def test_voice_update():
    """Test sending a voice update"""
    url = f"{BASE_URL}/api/v1/voice-update/"
    payload = {
        "area": "Ramanapet",
        "issue": "Line Breakdown",
        "eta": "30 minutes",
        "status": "In Progress",
        "staff_name": "LM Raju"
    }

    try:
        response = requests.post(url, json=payload)
        if response.status_code == 200:
            print("✅ Voice update sent successfully")
            print(f"Response: {response.json()}")
            return True
        else:
            print(f"❌ Failed to send voice update: {response.status_code}")
            print(f"Error: {response.text}")
            return False
    except Exception as e:
        print(f"❌ Error sending voice update: {str(e)}")
        return False

def test_get_outage_info():
    """Test getting outage information"""
    area = "Ramanapet"
    url = f"{BASE_URL}/api/v1/outage-info/{area}"

    try:
        response = requests.get(url)
        if response.status_code == 200:
            print("✅ Retrieved outage information successfully")
            print(f"Outage Info: {response.json()}")
            return True
        else:
            print(f"❌ Failed to get outage info: {response.status_code}")
            print(f"Error: {response.text}")
            return False
    except Exception as e:
        print(f"❌ Error getting outage info: {str(e)}")
        return False

def test_consumer_query():
    """Test processing a consumer query"""
    url = f"{BASE_URL}/api/v1/consumer-query/"
    payload = {
        "area": "Ramanapet",
        "query": "Current eppudu vastundi?"
    }

    try:
        response = requests.post(url, json=payload)
        if response.status_code == 200:
            print("✅ Consumer query processed successfully")
            print(f"Response: {response.json()}")
            return True
        else:
            print(f"❌ Failed to process consumer query: {response.status_code}")
            print(f"Error: {response.text}")
            return False
    except Exception as e:
        print(f"❌ Error processing consumer query: {str(e)}")
        return False

def main():
    """Main test function"""
    print("🧪 Testing TGSPDCL AI Voice Call Assistant")
    print("=" * 50)

    # Test API health
    if not test_api_health():
        print("Exiting tests due to API connectivity issues.")
        return

    print("\n" + "=" * 50)

    # Test voice update
    print("\n1. Testing voice update...")
    test_voice_update()

    print("\n" + "=" * 50)

    # Test getting outage info
    print("\n2. Testing outage information retrieval...")
    test_get_outage_info()

    print("\n" + "=" * 50)

    # Test consumer query
    print("\n3. Testing consumer query processing...")
    test_consumer_query()

    print("\n" + "=" * 50)
    print("🏁 Test suite completed")

if __name__ == "__main__":
    main()