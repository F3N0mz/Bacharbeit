#ifndef TIMEKEEPING_H
#define TIMEKEEPING_H

#include <time.h>
#include <Arduino.h>
#include <Preferences.h>
#include <vector> // <-- NEW: For storing parsed schedule times

// Structure to hold a single parsed scheduled time
struct ParsedScheduleTime {
    int hour;
    int minute;
    // No 'dispensedToday' flag needed if we check based on minute change
};

// Initializes the timekeeping system.
void timekeepingSetup(Preferences& prefs);

// Sets the system time from a "yyyy-MM-dd HH:mm:ss" UTC string.
bool setDeviceTimeFromUtcString(const char* utcTimeStr, Preferences& prefs);

// Gets the current system time as time_t (Unix timestamp, UTC).
time_t getCurrentDeviceTime();

// Gets the current system time formatted as "yyyy-MM-dd HH:mm:ss" (UTC).
String getCurrentDeviceUtcTimeString();

// Helper function to parse a "yyyy-MM-dd HH:mm:ss" UTC string into a time_t value.
bool parseUtcDateTimeStringToTimeT(const char* utcDateTimeStr, time_t& t_out);

// Helper function to format a time_t (UTC Unix timestamp) into a "yyyy-MM-dd HH:mm:ss" UTC string.
String formatTimeTToUtcDateTimeString(time_t timeValue);

// This function can be called in the main loop.
void timekeepingLoop();

// --- NEW FUNCTIONS FOR SCHEDULING ---

// Called by BTooth.cpp when the schedule string is updated via BLE.
// Also called during timekeepingSetup to load initial schedule.
void updateAndParseSchedule(Preferences& prefs);

// Function to calculate and return the string for "Time Until Next Dispense".
String getTimeUntilNextDispenseString();

// Function to check current time against schedule and trigger dispense.
// This will be called internally by timekeepingLoop.
void checkScheduledDispenses();


#endif // TIMEKEEPING_H