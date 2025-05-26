#include "BTooth.h"
#include "motorcontrol.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include "timekeeping.h"
#include <string> 

// BLE Service + Characteristic UUIDs (unchanged)
#define SERVICE_UUID                           "03339647-3f4e-43df-abff-fac54287cf1a"
#define CHAR_SET_DEVICE_TIME_UUID              "65232f1d-618a-4268-9050-0548142a4536"
#define CHAR_SET_DISPENSE_SCHEDULE_UUID        "999c584e-06c0-49a1-995a-66b7c802ac1b"
#define CHAR_TRIGGER_MANUAL_DISPENSE_UUID      "36bb95f2-e57e-4db9-b9aa-fb6541ee784e"
#define CHAR_GET_DEVICE_TIME_UUID              "272ee276-e37e-4d78-8c5e-bb7225d35074"
#define CHAR_GET_DISPENSE_SCHEDULE_UUID        "b53c2ed4-ae26-476d-8414-011a025dddfc"
#define CHAR_GET_LAST_DISPENSE_INFO_UUID       "40d3b5d8-5480-4b7b-a115-5fe86bf17d7d"
#define CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID "4b14acc4-768a-43e1-9d6c-0d97307e2666"
#define CHAR_GET_DISPENSE_LOG_UUID             "6f182da7-c5a8-40ab-a637-f97ed6b5777b"

static NimBLECharacteristic *pSetDeviceTimeCharacteristic         = nullptr;
static NimBLECharacteristic *pSetDispenseScheduleCharacteristic    = nullptr;
static NimBLECharacteristic *pTriggerManualDispenseCharacteristic = nullptr;
static NimBLECharacteristic *pGetDeviceTimeCharacteristic          = nullptr;
static NimBLECharacteristic *pGetDispenseScheduleCharacteristic    = nullptr;
static NimBLECharacteristic *pGetLastDispenseInfoCharacteristic    = nullptr;
static NimBLECharacteristic *pGetTimeUntilNextDispenseCharacteristic = nullptr;
static NimBLECharacteristic *pGetDispenseLogCharacteristic         = nullptr;


static bool deviceConnected    = false;

//--------------------------------------------------------------------
class MyCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    private:
        Preferences& _prefs;
    public:
    MyCharacteristicCallbacks(Preferences& prefs) : _prefs(prefs) {}

    void onWrite(NimBLECharacteristic *pCharacteristic) override {
        std::string uuid_str  = pCharacteristic->getUUID().toString();
        std::string value_from_client_str = pCharacteristic->getValue();

        if (uuid_str == CHAR_SET_DEVICE_TIME_UUID) {
           if (setDeviceTimeFromUtcString(value_from_client_str.c_str(), _prefs)) {
               if (pGetDeviceTimeCharacteristic) {
                   String arduinoAckMsg = "Time Set ACK: " + getCurrentDeviceUtcTimeString();
                   pGetDeviceTimeCharacteristic->setValue(std::string(arduinoAckMsg.c_str()));
                   pGetDeviceTimeCharacteristic->notify();
               }
           } else {
               if (pGetDeviceTimeCharacteristic) {
                   String arduinoErrMsg = "Time Set FAILED. Current: " + getCurrentDeviceUtcTimeString();
                   pGetDeviceTimeCharacteristic->setValue(std::string(arduinoErrMsg.c_str()));
                   pGetDeviceTimeCharacteristic->notify();
               }
           }
       }  else if (uuid_str == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
            if (_prefs.putString("schedule", value_from_client_str.c_str())) {
                Serial.printf("BTooth: New schedule string saved to NVS: %s\n", value_from_client_str.c_str());
                updateAndParseSchedule(_prefs); // <-- NEW: Tell timekeeping to re-parse the schedule
                
                if (pGetDispenseScheduleCharacteristic) {
                    // Notify back the received schedule (or the freshly parsed one if you prefer more validation)
                    pGetDispenseScheduleCharacteristic->setValue(value_from_client_str);
                    pGetDispenseScheduleCharacteristic->notify();
                }
            } else {
                Serial.printf("BTooth: FAILED to save schedule to NVS: %s\n", value_from_client_str.c_str());
                // Optionally notify client of failure
                if (pGetDispenseScheduleCharacteristic) {
                    String errMsg = "Schedule set FAILED to save. Current: " + _prefs.getString("schedule", "Empty");
                    pGetDispenseScheduleCharacteristic->setValue(std::string(errMsg.c_str()));
                    pGetDispenseScheduleCharacteristic->notify();
                }
            }
        } else if (uuid_str == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
            // ... (manual dispense logic remains the same) ...
        }
    }
};


class MyServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*) override { deviceConnected = true; }
    void onDisconnect(NimBLEServer*) override {
        deviceConnected = false;
        NimBLEDevice::startAdvertising();
    }
};

void bleNotifyDispenseComplete(Preferences& prefs) {
    if(pGetLastDispenseInfoCharacteristic) {
        unsigned long completeTime_ms = millis();
        String arduinoCompletionMessage = "Dispense complete. Event at (ms): " + String(completeTime_ms) +
                                   ", Time: " + getCurrentDeviceUtcTimeString();
        pGetLastDispenseInfoCharacteristic->setValue(std::string(arduinoCompletionMessage.c_str()));
        pGetLastDispenseInfoCharacteristic->notify();
        // Storing in prefs with c_str from a local String that's about to go out of scope is fine
        // because putString copies the data.
        prefs.putString("lastDisp", arduinoCompletionMessage.c_str());
        Serial.println("BTooth: Last dispense info (complete) saved to NVS.");
    }
}

//--------------------------------------------------------------------
void bleSetup(Preferences& prefs) {
    NimBLEDevice::init("PillDispenserESP32");
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    NimBLEService *pService = pServer->createService(SERVICE_UUID);
    MyCharacteristicCallbacks* charCallbacks = new MyCharacteristicCallbacks(prefs);

    pSetDeviceTimeCharacteristic = pService->createCharacteristic(
        CHAR_SET_DEVICE_TIME_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pSetDeviceTimeCharacteristic->setCallbacks(charCallbacks);

    pSetDispenseScheduleCharacteristic = pService->createCharacteristic(
        CHAR_SET_DISPENSE_SCHEDULE_UUID, NIMBLE_PROPERTY::WRITE);
    pSetDispenseScheduleCharacteristic->setCallbacks(charCallbacks);

    pTriggerManualDispenseCharacteristic = pService->createCharacteristic(
        CHAR_TRIGGER_MANUAL_DISPENSE_UUID, NIMBLE_PROPERTY::WRITE);
    pTriggerManualDispenseCharacteristic->setCallbacks(charCallbacks);

    pGetDeviceTimeCharacteristic = pService->createCharacteristic(
        CHAR_GET_DEVICE_TIME_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    // Initial value setting
    String initialTimeArduinoStr = getCurrentDeviceUtcTimeString();
    pGetDeviceTimeCharacteristic->setValue(std::string(initialTimeArduinoStr.c_str()));

    pGetDispenseScheduleCharacteristic = pService->createCharacteristic(
        CHAR_GET_DISPENSE_SCHEDULE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    // This setValue uses a string literal, which is safe and has static storage duration.
    pGetDispenseScheduleCharacteristic->setValue("Schedule: Empty");


    pGetLastDispenseInfoCharacteristic = pService->createCharacteristic(
        CHAR_GET_LAST_DISPENSE_INFO_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    // String literal is safe
    pGetLastDispenseInfoCharacteristic->setValue("Last Dispense: None");


    pGetTimeUntilNextDispenseCharacteristic = pService->createCharacteristic(
        CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    // String literal is safe
    pGetTimeUntilNextDispenseCharacteristic->setValue("Next Dispense: Unknown");

    pGetDispenseLogCharacteristic = pService->createCharacteristic(
        CHAR_GET_DISPENSE_LOG_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    // String literal is safe
    pGetDispenseLogCharacteristic->setValue("Log: Empty");

    // Restore persisted values -----------------------------
    // For Arduino String:
    String loadedScheduleArduinoStr = prefs.getString("schedule", "Schedule: Empty");
    // Check if it's not the default "Schedule: Empty" before overwriting if you prefer,
    // or just set it. The characteristic already has "Schedule: Empty"
    if (loadedScheduleArduinoStr != "Schedule: Empty") {
         pGetDispenseScheduleCharacteristic->setValue(std::string(loadedScheduleArduinoStr.c_str()));
    }


    String lastDispenseArduinoStr = prefs.getString("lastDisp", "Last Dispense: None");
    if (lastDispenseArduinoStr != "Last Dispense: None") {
        pGetLastDispenseInfoCharacteristic->setValue(std::string(lastDispenseArduinoStr.c_str()));
    }

    pService->start();

    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    NimBLEDevice::startAdvertising();
    Serial.println("BLE Setup Complete. Advertising started.");
}

void bleLoop() {
    static unsigned long lastUpdateTime = 0;
    const unsigned long updateInterval = 1000; // Update BLE chars every 1 second

    if (deviceConnected && (millis() - lastUpdateTime > updateInterval)) {
        lastUpdateTime = millis();

        String currentUtcTimeStr = getCurrentDeviceUtcTimeString();

        if (pGetDeviceTimeCharacteristic) {
            pGetDeviceTimeCharacteristic->setValue(std::string(currentUtcTimeStr.c_str()));
            pGetDeviceTimeCharacteristic->notify();
        }

        String nextDispenseStatusStr = getTimeUntilNextDispenseString();
        // *** ADD THIS DEBUG LINE ***
        Serial.printf("BTooth DEBUG: Full nextDispenseStatusStr for BLE: '%s' (len %d)\n", nextDispenseStatusStr.c_str(), nextDispenseStatusStr.length());
        
        if (pGetTimeUntilNextDispenseCharacteristic) {
          pGetTimeUntilNextDispenseCharacteristic->setValue(std::string(nextDispenseStatusStr.c_str()));
          pGetTimeUntilNextDispenseCharacteristic->notify();
        }
    }
}
