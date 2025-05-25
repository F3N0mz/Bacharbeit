#include "BTooth.h"
#include "motorcontrol.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>

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

static Preferences preferences;

static bool deviceConnected    = false;
static bool oldDeviceConnected = false;

//--------------------------------------------------------------------
class MyCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic) override {
        std::string uuid_str  = pCharacteristic->getUUID().toString();
        std::string value_str = pCharacteristic->getValue();

        if (uuid_str == CHAR_SET_DEVICE_TIME_UUID) {
            preferences.putString("devTime", value_str.c_str());
            if (pGetDeviceTimeCharacteristic) {
                pGetDeviceTimeCharacteristic->setValue("Time Set ACK: " + value_str);
                pGetDeviceTimeCharacteristic->notify();
            }
        } else if (uuid_str == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
            preferences.putString("schedule", value_str.c_str());
            if (pGetDispenseScheduleCharacteristic) {
                pGetDispenseScheduleCharacteristic->setValue(value_str);
                pGetDispenseScheduleCharacteristic->notify();
            }
        } else if (uuid_str == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
            // Attempt to start the motor; returns false if busy
            if (motorStartDispense(OneChamber)) {
                if (pGetLastDispenseInfoCharacteristic) {
                    unsigned long now = millis();
                    String msg = "Dispensing... Started at: " + String(now);
                    pGetLastDispenseInfoCharacteristic->setValue(msg.c_str());
                    pGetLastDispenseInfoCharacteristic->notify();
                    preferences.putString("lastDisp", msg.c_str());
                }
            } else {
                if (pGetLastDispenseInfoCharacteristic) {
                    pGetLastDispenseInfoCharacteristic->setValue("Dispense command ignored: busy");
                    pGetLastDispenseInfoCharacteristic->notify();
                }
            }
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

//--------------------------------------------------------------------
void bleSetup() {
    preferences.begin("pilldisp", false);

    NimBLEDevice::init("PillDispenserESP32");
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    NimBLEService *pService = pServer->createService(SERVICE_UUID);

    pSetDeviceTimeCharacteristic = pService->createCharacteristic(
        CHAR_SET_DEVICE_TIME_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pSetDeviceTimeCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    pSetDispenseScheduleCharacteristic = pService->createCharacteristic(
        CHAR_SET_DISPENSE_SCHEDULE_UUID, NIMBLE_PROPERTY::WRITE);
    pSetDispenseScheduleCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    pTriggerManualDispenseCharacteristic = pService->createCharacteristic(
        CHAR_TRIGGER_MANUAL_DISPENSE_UUID, NIMBLE_PROPERTY::WRITE);
    pTriggerManualDispenseCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    pGetDeviceTimeCharacteristic = pService->createCharacteristic(
        CHAR_GET_DEVICE_TIME_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    pGetDeviceTimeCharacteristic->setValue("Device Time: Not Set");

    pGetDispenseScheduleCharacteristic = pService->createCharacteristic(
        CHAR_GET_DISPENSE_SCHEDULE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    pGetDispenseScheduleCharacteristic->setValue("Schedule: Empty");

    pGetLastDispenseInfoCharacteristic = pService->createCharacteristic(
        CHAR_GET_LAST_DISPENSE_INFO_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    pGetLastDispenseInfoCharacteristic->setValue("Last Dispense: None");

    pGetTimeUntilNextDispenseCharacteristic = pService->createCharacteristic(
        CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    pGetTimeUntilNextDispenseCharacteristic->setValue("Next Dispense: Unknown");

    pGetDispenseLogCharacteristic = pService->createCharacteristic(
        CHAR_GET_DISPENSE_LOG_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    pGetDispenseLogCharacteristic->setValue("Log: Empty");

    // Restore persisted values -----------------------------
    String loadedTime = preferences.getString("devTime", "Device Time: Not Set");
    pGetDeviceTimeCharacteristic->setValue(loadedTime.c_str());

    String loadedSchedule = preferences.getString("schedule", "Schedule: Empty");
    pGetDispenseScheduleCharacteristic->setValue(loadedSchedule.c_str());

    String lastDispense = preferences.getString("lastDisp", "Last Dispense: None");
    pGetLastDispenseInfoCharacteristic->setValue(lastDispense.c_str());

    pService->start();

    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    NimBLEDevice::startAdvertising();
}

void bleLoop() {
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
    } else if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
    }

    // Update "time until next dispense" characteristic every 5 s
    static unsigned long lastUpdateTime = 0;
    if (deviceConnected && (millis() - lastUpdateTime > 5000)) {
        lastUpdateTime = millis();
        String timeVal = "Next: " + String(millis() / 1000) + "s (simulated)";
        pGetTimeUntilNextDispenseCharacteristic->setValue(timeVal.c_str());
        pGetTimeUntilNextDispenseCharacteristic->notify();
    }
}
