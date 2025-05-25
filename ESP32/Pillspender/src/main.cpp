#include <Arduino.h>
#include "motorcontrol.h"
#include "BTooth.h"

void setup() {
    Serial.begin(115200);

    motorSetup();     // stepper + pins
    bleSetup();       // BLE GATT server
}

void loop() {
    bleLoop();        // BLE housekeeping + notifications
    motorLoop();      // runs the stepper one step at a time
    delay(10);        // small yield
}
