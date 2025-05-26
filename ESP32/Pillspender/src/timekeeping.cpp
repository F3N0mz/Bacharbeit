#include "timekeeping.h"
#include <sys/time.h> // For settimeofday, struct timeval
#include <stdio.h>    // For sscanf
#include <string.h>   // For strlen etc.
#include <Arduino.h>  // For Serial, String, atoll, etc.
#include "motorcontrol.h" 
// NVS key for storing the device time (as a Unix timestamp string)
const char* NVS_TIME_KEY = "devTimeNVS";
static std::vector<ParsedScheduleTime> s_parsedSchedule;
static int s_lastCheckedMinute = -1; // To ensure dispense check logic runs once per minute change

// --- START: Custom my_timegm implementation ---
// Helper: Check for leap year
static bool my_is_leap_year(int year_full) {
    // year_full is the full year (e.g., 2024)
    return (year_full % 4 == 0 && year_full % 100 != 0) || (year_full % 400 == 0);
}

// Days in month (0-indexed, Jan=0), non-leap year
static int my_days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

static void parseScheduleStringInternal(const String& scheduleStr, std::vector<ParsedScheduleTime>& outParsedTimes) {
    outParsedTimes.clear();
    int currentIndex = 0;
    String scheduleToParse = scheduleStr; // Make a mutable copy

    // Sanitize: remove "Schedule: " prefix if present from old NVS values or initial characteristic values
    if (scheduleToParse.startsWith("Schedule: ")) {
        scheduleToParse = scheduleToParse.substring(String("Schedule: ").length());
    }
    scheduleToParse.trim();


    if (scheduleToParse.isEmpty() || scheduleToParse.equalsIgnoreCase("Empty") || scheduleToParse.equalsIgnoreCase("None")) {
        Serial.println("Timekeeper: Schedule string is empty or 'Empty/None'. No times loaded.");
        return;
    }

    while (currentIndex < scheduleToParse.length()) {
        int semiColonIndex = scheduleToParse.indexOf(';', currentIndex);
        String item;
        if (semiColonIndex == -1) { // Last item or only item
            item = scheduleToParse.substring(currentIndex);
            currentIndex = scheduleToParse.length(); // Exit loop
        } else {
            item = scheduleToParse.substring(currentIndex, semiColonIndex);
            currentIndex = semiColonIndex + 1;
        }

        item.trim();
        if (item.length() == 5 && item.charAt(2) == ':') {
            int hour = item.substring(0, 2).toInt();
            int minute = item.substring(3, 5).toInt();

            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                outParsedTimes.push_back({hour, minute});
                Serial.printf("Timekeeper: Parsed and added schedule item: %02d:%02d\n", hour, minute);
            } else {
                Serial.printf("Timekeeper: Invalid HH or MM value in item: %s\n", item.c_str());
            }
        } else if (!item.isEmpty()) {
            Serial.printf("Timekeeper: Invalid format for schedule item (expected HH:MM): %s\n", item.c_str());
        }
    }

    if (outParsedTimes.empty() && !scheduleToParse.isEmpty() && scheduleToParse != "Schedule: Empty" && scheduleToParse != "Empty") {
         Serial.println("Timekeeper: Schedule string was not empty but resulted in no valid parseable times.");
    } else if (outParsedTimes.empty()) {
         Serial.println("Timekeeper: Parsed schedule is empty.");
    } else {
        // Optional: Sort the schedule for easier "next dispense" calculation
        std::sort(outParsedTimes.begin(), outParsedTimes.end(), [](const ParsedScheduleTime& a, const ParsedScheduleTime& b) {
            if (a.hour != b.hour) return a.hour < b.hour;
            return a.minute < b.minute;
        });
        Serial.printf("Timekeeper: Parsed schedule contains %d item(s).\n", outParsedTimes.size());
    }
}

// Portable implementation of timegm (converts UTC struct tm to UTC time_t)
time_t my_timegm(struct tm *tm_info) {
    if (tm_info == nullptr) return (time_t)-1;

    long year_full = (long)tm_info->tm_year + 1900; // tm_year is years since 1900
    int month = tm_info->tm_mon; // 0-11 (January is 0)
    int day_of_month = tm_info->tm_mday;  // 1-31

    long total_days = 0;
    const int epoch_year = 1970;

    if (year_full < epoch_year) {
        return (time_t)-1; // Error: year is before epoch
    }

    // Calculate days from epoch_year up to (but not including) the given year
    for (long y = epoch_year; y < year_full; ++y) {
        total_days += my_is_leap_year(y) ? 366 : 365;
    }

    // Add days for the months passed in the current year
    for (int m = 0; m < month; ++m) {
        total_days += my_days_in_month[m];
        // If it's February of a leap year, add the extra day
        if (m == 1 && my_is_leap_year(year_full)) {
            total_days += 1;
        }
    }

    // Add days in the current month (tm_mday is 1-indexed)
    total_days += (day_of_month - 1);

    // Convert total days to seconds
    time_t seconds_since_epoch = total_days * 86400L; // 86400 seconds in a day (24*60*60)

    // Add seconds for the time of day
    seconds_since_epoch += tm_info->tm_hour * 3600L;
    seconds_since_epoch += tm_info->tm_min * 60L;
    seconds_since_epoch += tm_info->tm_sec;

    return seconds_since_epoch;
}
// --- END: Custom my_timegm implementation ---


// Helper function to parse "yyyy-MM-dd HH:mm:ss" UTC string to struct tm
// and then convert to time_t (UTC Unix timestamp).
bool parseUtcDateTimeStringToTimeT(const char* utcDateTimeStr, time_t& t_out) {
    if (utcDateTimeStr == nullptr) return false;

    struct tm tm_info = {0}; // Initialize all fields to zero

    int year, month_val, day, hour, minute, second;
    int items_scanned = sscanf(utcDateTimeStr, "%d-%d-%d %d:%d:%d",
                               &year, &month_val, &day,
                               &hour, &minute, &second);

    if (items_scanned != 6) {
        Serial.printf("Timekeeper: Invalid date-time string format: %s. Expected 6 items, got %d.\n", utcDateTimeStr, items_scanned);
        return false;
    }

    // Basic validation of ranges
    if (year < 1970 || year > 2038 || // Practical limits for time_t, adjust if needed
        month_val < 1 || month_val > 12 ||
        day < 1 || day > 31 ||
        hour < 0 || hour > 23 ||
        minute < 0 || minute > 59 ||
        second < 0 || second > 59) {
        Serial.printf("Timekeeper: Date-time values out of range: %s\n", utcDateTimeStr);
        return false;
    }

    tm_info.tm_year = year - 1900;
    tm_info.tm_mon  = month_val - 1; // struct tm month is 0-11
    tm_info.tm_mday = day;
    tm_info.tm_hour = hour;
    tm_info.tm_min  = minute;
    tm_info.tm_sec  = second;
    tm_info.tm_isdst = 0; // No daylight saving for UTC

    // Use our custom my_timegm function
    t_out = my_timegm(&tm_info);

    if (t_out == (time_t)-1) {
        Serial.printf("Timekeeper: my_timegm failed to convert parsed date-time: %s\n", utcDateTimeStr);
        return false;
    }

    return true;
}

// Helper function to format time_t (UTC Unix timestamp) to "yyyy-MM-dd HH:mm:ss" UTC string.
String formatTimeTToUtcDateTimeString(time_t timeValue) {
    struct tm timeinfo;
    gmtime_r(&timeValue, &timeinfo);

    char buffer[20];
    strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &timeinfo);
    return String(buffer);
}


void timekeepingSetup(Preferences& prefs) {
    String storedTimeUnixStr = prefs.getString(NVS_TIME_KEY, "0");
    long long storedUnixTime = atoll(storedTimeUnixStr.c_str());

    if (storedUnixTime > 0) {
        struct timeval tv;
        tv.tv_sec = (time_t)storedUnixTime;
        tv.tv_usec = 0;
        if (settimeofday(&tv, NULL) == 0) {
            Serial.println("Timekeeper: System time initialized from NVS.");
        } else {
            Serial.println("Timekeeper: Failed to set system time from NVS.");
        }
    } else {
        Serial.println("Timekeeper: No valid time found in NVS. System time not set from NVS.");
    }
    Serial.print("Timekeeper: Current time after setup: ");
    Serial.println(getCurrentDeviceUtcTimeString());

    // Load and parse the schedule from NVS
    updateAndParseSchedule(prefs);
}

bool setDeviceTimeFromUtcString(const char* utcTimeStr, Preferences& prefs) {
    if (utcTimeStr == nullptr || strlen(utcTimeStr) == 0) {
        Serial.println("Timekeeper: Received empty time string. Time not set.");
        return false;
    }

    time_t unixTime;
    if (!parseUtcDateTimeStringToTimeT(utcTimeStr, unixTime)) {
        Serial.printf("Timekeeper: Failed to parse UTC date-time string: '%s'. Time not set.\n", utcTimeStr);
        return false;
    }

    struct timeval tv;
    tv.tv_sec = unixTime;
    tv.tv_usec = 0;

    if (settimeofday(&tv, NULL) == 0) {
        Serial.printf("Timekeeper: System time set. Current time: %s\n", getCurrentDeviceUtcTimeString().c_str());
        
        String unixTimeStr = String((long long)unixTime);
        if (!prefs.putString(NVS_TIME_KEY, unixTimeStr.c_str())) {
             Serial.println("Timekeeper: Failed to save Unix time to NVS.");
        } else {
             Serial.println("Timekeeper: Unix time saved to NVS.");
        }
        // When time is set, reset lastCheckedMinute to force an immediate schedule check
        s_lastCheckedMinute = -1;
        return true;
    } else {
        Serial.println("Timekeeper: Failed to set system time using settimeofday.");
        return false;
    }
}

time_t getCurrentDeviceTime() {
    return time(NULL);
}

String getCurrentDeviceUtcTimeString() {
    time_t now_t = getCurrentDeviceTime();
    // Check if time is likely valid (e.g., after Jan 1, 2023 UTC)
    // 0 is epoch, but if system time is 0, it means not set.
    // A very small positive number also indicates not properly set after boot.
    if (now_t < 1672531200 && now_t != 0 ) { // 1672531200 is 2023-01-01 00:00:00 UTC
        struct tm timeinfo_check;
        gmtime_r(&now_t, &timeinfo_check); // gmtime_r is preferred
        // If the year is very low (e.g. 1970) or less than a reasonable recent year.
        if (timeinfo_check.tm_year < (2023 - 1900)) { // tm_year is years since 1900
             return String("Time not (yet) set"); // More accurate message
        }
    }
    if (now_t == 0 && millis() > 10000) { // If time is still 0 after 10s, likely not set
        return String("Time not (yet) set");
    }
    return formatTimeTToUtcDateTimeString(now_t);
}

void updateAndParseSchedule(Preferences& prefs) {
    String scheduleString = prefs.getString("schedule", ""); // Default to empty if not found
    Serial.printf("Timekeeper: Loading schedule from NVS: '%s'\n", scheduleString.c_str());
    parseScheduleStringInternal(scheduleString, s_parsedSchedule);
    s_lastCheckedMinute = -1; // Reset last checked minute to allow re-evaluation against new schedule
    Serial.printf("Timekeeper: Updated internal schedule. %d dispense times loaded.\n", s_parsedSchedule.size());
}

void checkScheduledDispenses() {
    if (s_parsedSchedule.empty()) {
        return; // No schedule to check
    }

    time_t now_t = getCurrentDeviceTime();
    // Ensure time is somewhat valid before trying to dispense
    if (now_t < 1672531200 && now_t != 0) { // Approx 2023-01-01 UTC
         struct tm temp_tm_check;
         gmtime_r(&now_t, &temp_tm_check);
         if (temp_tm_check.tm_year < (2023-1900)) {
            Serial.println("Timekeeper: System time appears invalid, skipping scheduled dispense check.");
            return;
         }
    }
     if (now_t == 0 && millis() > 10000) { // If time is still 0 after 10s, likely not set
        Serial.println("Timekeeper: System time is 0, skipping scheduled dispense check.");
        return;
    }


    struct tm timeinfo;
    gmtime_r(&now_t, &timeinfo); // Get current UTC time components

    int currentHour = timeinfo.tm_hour;
    int currentMinute = timeinfo.tm_min;

    // Only perform the detailed check if the minute has changed, or if s_lastCheckedMinute was reset
    if (currentMinute != s_lastCheckedMinute) {
        // Serial.printf("Timekeeper: Minute changed. Checking schedule. Current: %02d:%02d, LastCheckedMin: %d\n", currentHour, currentMinute, s_lastCheckedMinute);
        bool dispenseAttemptedThisMinute = false;

        for (const auto& scheduledTime : s_parsedSchedule) {
            if (scheduledTime.hour == currentHour && scheduledTime.minute == currentMinute) {
                Serial.printf("Timekeeper: Schedule MATCH! Time: %02d:%02d. Attempting dispense.\n", currentHour, currentMinute);
                if (!dispenseAttemptedThisMinute) { // Prevent multiple motor starts if schedule had duplicate times for same minute
                    if (motorStartDispense(OneChamber)) {
                        Serial.println("Timekeeper: Scheduled dispense initiated.");
                        // The main loop will call bleNotifyDispenseComplete when motor is done.
                    } else {
                        Serial.println("Timekeeper: Motor busy or failed to start for scheduled dispense.");
                    }
                    dispenseAttemptedThisMinute = true; // Mark that we've tried for this minute
                    // break; // If only one dispense per matched minute is allowed, even if multiple entries for same time.
                              // Current logic is fine as motorStartDispense won't run if busy.
                }
            }
        }
        s_lastCheckedMinute = currentMinute; // Update the last minute we performed this check for
    }
}

// --- NEW: Function to get "Time Until Next Dispense" string ---
String getTimeUntilNextDispenseString() {
    String currentTimeStr = getCurrentDeviceUtcTimeString(); // Get current time once

    if (s_parsedSchedule.empty()) {
        return "Next: No schedule. Now: " + currentTimeStr;
    }

    time_t now_t = getCurrentDeviceTime();
    if ((now_t < 1672531200 && now_t != 0) || (now_t == 0 && millis() > 10000) ) { // Check for valid time
         struct tm temp_tm_check;
         gmtime_r(&now_t, &temp_tm_check);
         if (temp_tm_check.tm_year < (2023-1900) || now_t == 0) {
            return "Next: Time not set. Now: " + currentTimeStr;
         }
    }


    struct tm current_tm_utc;
    gmtime_r(&now_t, &current_tm_utc);   // correct


    time_t next_target_epoch_utc = -1;
    ParsedScheduleTime next_dispense_details = {-1, -1};

    // Find next dispense time for "today"
    for (const auto& sched : s_parsedSchedule) {
        if (sched.hour > current_tm_utc.tm_hour || (sched.hour == current_tm_utc.tm_hour && sched.minute > current_tm_utc.tm_min)) {
            struct tm target_tm_today = current_tm_utc; // Base on current date
            target_tm_today.tm_hour = sched.hour;
            target_tm_today.tm_min = sched.minute;
            target_tm_today.tm_sec = 0;
            time_t t_epoch = my_timegm(&target_tm_today);

            if (t_epoch != (time_t)-1) {
                if (next_target_epoch_utc == -1 || t_epoch < next_target_epoch_utc) {
                    next_target_epoch_utc = t_epoch;
                    next_dispense_details = sched;
                }
            }
        }
    }

    // If no dispense found for today, find the earliest for "tomorrow"
    // (This uses the first item of the sorted s_parsedSchedule)
    if (next_target_epoch_utc == -1 && !s_parsedSchedule.empty()) {
        const auto& earliest_sched_overall = s_parsedSchedule[0]; // Assumes s_parsedSchedule is sorted by time

        // Calculate start of "tomorrow" based on current UTC day
        time_t start_of_today_t = now_t - (current_tm_utc.tm_hour * 3600L) - (current_tm_utc.tm_min * 60L) - current_tm_utc.tm_sec;
        time_t start_of_tomorrow_t = start_of_today_t + (24L * 3600L);

        struct tm target_tm_tomorrow;
        gmtime_r(&start_of_tomorrow_t, &target_tm_tomorrow); // Get date parts for tomorrow

        target_tm_tomorrow.tm_hour = earliest_sched_overall.hour;
        target_tm_tomorrow.tm_min = earliest_sched_overall.minute;
        target_tm_tomorrow.tm_sec = 0;
        
        time_t t_epoch = my_timegm(&target_tm_tomorrow);
        if (t_epoch != (time_t)-1) {
            next_target_epoch_utc = t_epoch;
            next_dispense_details = earliest_sched_overall;
        }
    }

    if (next_target_epoch_utc != -1 && next_dispense_details.hour != -1) {
        long diff_seconds = next_target_epoch_utc - now_t;
        if (diff_seconds < 0) diff_seconds = 0; // Safety net

        long hours_rem = diff_seconds / 3600L;
        long mins_rem = (diff_seconds % 3600L) / 60L;
        long secs_rem = diff_seconds % 60L;

        String target_dispense_time_str = formatTimeTToUtcDateTimeString(next_target_epoch_utc);

        char buffer[180]; // Increased buffer size
        snprintf(buffer, sizeof(buffer), "Next: %s (in %02ldh %02ldm %02lds). Now: %s",
                 target_dispense_time_str.c_str(),
                 hours_rem, mins_rem, secs_rem,
                 currentTimeStr.c_str()); // Use the initially fetched currentTimeStr
        return String(buffer);
    } else {
        return "Next: No upcoming. Now: " + currentTimeStr;
    }
}

void timekeepingLoop() {
    static unsigned long lastTimeLogMillis = 0;
    if (millis() - lastTimeLogMillis > 5000) { // Log current time every 5s
        lastTimeLogMillis = millis();
        Serial.print("Timekeeper (loop): Current system time (UTC): ");
        Serial.println(getCurrentDeviceUtcTimeString());
    }
    checkScheduledDispenses(); // Check for scheduled dispenses
}