class EmergencyDetector:
    def __init__(self):
        self.emergency_keywords = [
            "smoke", "fire", "spark", "wire down", "electric shock",
            "pole fell", "emergency", "voltage fluctuation", "power failure"
        ]
        self.emergency_detected = []

    def detect_emergency(self, text):
        # Check the text for emergency keywords
        text_lower = text.lower()
        for keyword in self.emergency_keywords:
            if keyword in text_lower:
                self.emergency_detected.append(keyword)
        return self.emergency_detected