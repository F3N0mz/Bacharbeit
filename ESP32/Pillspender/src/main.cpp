// main.cpp - Suggested changes
#include <Arduino.h>
#include "motorcontrol.h"
#include "BTooth.h"
#include "timekeeping.h"
#include <Preferences.h> 
// Forward declaration to fix 'not declared in this scope' error
void bleSetup(Preferences &preferences); 
void bleNotifyDispenseComplete(Preferences &preferences);

Preferences preferences; 
void setup() {
    Serial.begin(115200);
    preferences.begin("pilldisp", false);

    timekeepingSetup(preferences);    
    motorSetup();
    bleSetup(preferences);          
}

void loop() {
    bleLoop();
    timekeepingLoop(); 
    MotorStatus motorStatus = motorLoop();
    if (motorStatus == MOTOR_JUST_COMPLETED) {
        bleNotifyDispenseComplete(preferences); 
    }

    delay(10);
}