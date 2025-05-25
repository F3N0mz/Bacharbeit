#ifndef MOTORCONTROL_H
#define MOTORCONTROL_H

#include <Arduino.h>

extern const int OneChamber;          // 1/8 revolution, exported for BLE layer

void motorSetup();                    // call once from setup()
bool motorStartDispense(int numSteps); // returns true if motor accepted the job
void motorLoop();                     // call each loop()

#endif // MOTORCONTROL_H
