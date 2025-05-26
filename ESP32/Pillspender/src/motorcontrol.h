#ifndef MOTORCONTROL_H
#define MOTORCONTROL_H

#include <Arduino.h>

extern const int OneChamber;          // 1/8 revolution, exported for BLE layer
enum MotorStatus { MOTOR_IDLE, MOTOR_RUNNING, MOTOR_JUST_COMPLETED };
void motorSetup();                    // call once from setup()
bool motorStartDispense(int numSteps); // returns true if motor accepted the job

MotorStatus motorLoop();

#endif // MOTORCONTROL_H
