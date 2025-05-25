#include "motorcontrol.h"
#include <Stepper.h>

const int stepsPerRevolution = 2048;
const int OneChamber          = stepsPerRevolution / 8;

const int in1Pin = 4;
const int in2Pin = 5;
const int in3Pin = 6;
const int in4Pin = 7;

static Stepper myStepper(stepsPerRevolution, in1Pin, in2Pin, in3Pin, in4Pin);

static bool          isStepping     = false;
static int           stepsRemaining = 0;
static unsigned long lastStepTime   = 0;
static const unsigned long stepInterval = 2; // ms

static void deenergizeStepper() {
    digitalWrite(in1Pin, LOW);
    digitalWrite(in2Pin, LOW);
    digitalWrite(in3Pin, LOW);
    digitalWrite(in4Pin, LOW);
    Serial.println("Stepper coils de‑energized.");
}

void motorSetup() {
    Serial.println("Initializing Stepper Motor...");
    myStepper.setSpeed(10);
    Serial.println("Stepper Motor Initialized.");
}

bool motorStartDispense(int numSteps) {
    if (!isStepping) {
        Serial.printf("Starting dispense of %d steps.\n", numSteps);
        stepsRemaining = numSteps;
        isStepping    = true;
        return true;
    }
    return false;
}

void motorLoop() {
    if (isStepping && (millis() - lastStepTime >= stepInterval)) {
        if (stepsRemaining > 0) {
            myStepper.step(1);
            stepsRemaining--;
            lastStepTime = millis();
        } else {
            isStepping = false;
            deenergizeStepper();
            Serial.println("Dispense complete.");
        }
    }
}
