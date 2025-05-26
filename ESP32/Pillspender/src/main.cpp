// main.cpp - Suggested changes
#include <Arduino.h>
#include "motorcontrol.h"
#include "BTooth.h"
#include "timekeeping.h" // <-- NEW
#include <Preferences.h> // <-- NEW (for global object)

// Forward declaration to fix 'not declared in this scope' error
void bleSetup(Preferences &preferences); 
void bleNotifyDispenseComplete(Preferences &preferences);

Preferences preferences; // <-- NEW: Global preferences object

void setup() {
    Serial.begin(115200);
    preferences.begin("pilldisp", false); // <-- NEW: Initialize once

    timekeepingSetup(preferences);     // <-- NEW: Initialize timekeeping
    motorSetup();
    bleSetup(preferences);           // <-- MODIFIED: Pass preferences
}

void loop() {
    bleLoop();
    timekeepingLoop(); // <-- NEW: Allow timekeeping module to run
    MotorStatus motorStatus = motorLoop();
    if (motorStatus == MOTOR_JUST_COMPLETED) {
        bleNotifyDispenseComplete(preferences); // <-- MODIFIED: Pass preferences
    }

    delay(10);
}