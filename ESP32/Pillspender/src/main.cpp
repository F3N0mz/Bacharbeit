#include <Arduino.h>
#include <Stepper.h>

// BLE Libraries - NimBLE specific
#include <NimBLEDevice.h>
#include <NimBLEServer.h>
#include <NimBLEUtils.h>
// #include <NimBLE2902.h> // REMOVED THIS - Not needed for standard CCCD with NimBLE
#include <Preferences.h>

// Stepper motor configuration
const int stepsPerRevolution = 2048;
const int OneChamber = stepsPerRevolution / 8;
const int in1Pin = 4;
const int in2Pin = 5;
const int in3Pin = 6;
const int in4Pin = 7;
Stepper myStepper(stepsPerRevolution, in1Pin, in2Pin, in3Pin, in4Pin);

Preferences preferences;
bool isStepping = false;
int stepsRemaining = 0;
unsigned long lastStepTime = 0;
const unsigned long stepInterval = 2;

void deenergizeStepper() {
    digitalWrite(in1Pin, LOW);
    digitalWrite(in2Pin, LOW);
    digitalWrite(in3Pin, LOW);
    digitalWrite(in4Pin, LOW);
    Serial.println("Stepper coils de-energized.");
}

void startDispense(int numSteps) {
    if (!isStepping) {
        Serial.print("Starting dispense of ");
        Serial.print(numSteps);
        Serial.println(" steps.");
        stepsRemaining = numSteps;
        isStepping = true;
    }
}

// BLE Service and Characteristic UUIDs
#define SERVICE_UUID           "03339647-3f4e-43df-abff-fac54287cf1a"
#define CHAR_SET_DEVICE_TIME_UUID         "65232f1d-618a-4268-9050-0548142a4536"
#define CHAR_SET_DISPENSE_SCHEDULE_UUID   "999c584e-06c0-49a1-995a-66b7c802ac1b"
#define CHAR_TRIGGER_MANUAL_DISPENSE_UUID "36bb95f2-e57e-4db9-b9aa-fb6541ee784e"
#define CHAR_GET_DEVICE_TIME_UUID             "272ee276-e37e-4d78-8c5e-bb7225d35074"
#define CHAR_GET_DISPENSE_SCHEDULE_UUID       "b53c2ed4-ae26-476d-8414-011a025dddfc"
#define CHAR_GET_LAST_DISPENSE_INFO_UUID    "40d3b5d8-5480-4b7b-a115-5fe86bf17d7d"
#define CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID "4b14acc4-768a-43e1-9d6c-0d97307e2666"
#define CHAR_GET_DISPENSE_LOG_UUID            "6f182da7-c5a8-40ab-a637-f97ed6b5777b"

NimBLECharacteristic *pSetDeviceTimeCharacteristic;
NimBLECharacteristic *pSetDispenseScheduleCharacteristic;
NimBLECharacteristic *pTriggerManualDispenseCharacteristic;
NimBLECharacteristic *pGetDeviceTimeCharacteristic;
NimBLECharacteristic *pGetDispenseScheduleCharacteristic;
NimBLECharacteristic *pGetLastDispenseInfoCharacteristic;
NimBLECharacteristic *pGetTimeUntilNextDispenseCharacteristic;
NimBLECharacteristic *pGetDispenseLogCharacteristic;

bool deviceConnected = false;
bool oldDeviceConnected = false;

class MyCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic) override {
        Serial.print("Characteristic ");
        std::string uuid_str = pCharacteristic->getUUID().toString();
        std::string value_str = pCharacteristic->getValue();
        if (value_str.length() > 0) {
          Serial.print("New value: ");
          for (size_t i = 0; i < value_str.length(); i++) {
            Serial.print(value_str[i]);
          }
          Serial.println();
        }
        if (uuid_str == CHAR_SET_DEVICE_TIME_UUID) {
            Serial.println("Set_Device_Time received. Value: " + String(value_str.c_str()));
            preferences.putString("devTime", value_str.c_str());
            if (pGetDeviceTimeCharacteristic) {
                pGetDeviceTimeCharacteristic->setValue(std::string("Time Set ACK: ") + value_str);
                pGetDeviceTimeCharacteristic->notify();
            }
            Serial.println("Device time saved to NVS.");
         } else if (uuid_str == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
            Serial.println("Set_Dispense_Schedule received. Value: " + String(value_str.c_str()));
            preferences.putString("schedule", value_str.c_str());
            if (pGetDispenseScheduleCharacteristic) {
                pGetDispenseScheduleCharacteristic->setValue(value_str);
                pGetDispenseScheduleCharacteristic->notify();
            }
            Serial.println("Dispense schedule saved to NVS.");
        }  else if (uuid_str == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
            Serial.println("Trigger_Manual_Dispense received. Value: " + String(value_str.c_str()));
            if (!isStepping) {
                startDispense(OneChamber);
                if(pGetLastDispenseInfoCharacteristic) {
                    unsigned long currentTime = millis();
                    String progressMessage = "Dispensing... Started at: " + String(currentTime);
                    pGetLastDispenseInfoCharacteristic->setValue(progressMessage.c_str());
                    pGetLastDispenseInfoCharacteristic->notify();
                    preferences.putString("lastDisp", progressMessage.c_str());
                    Serial.println("Last dispense (in progress) saved to NVS.");
                }
            } else {
                Serial.println("Dispense command ignored, motor is busy.");
                if(pGetLastDispenseInfoCharacteristic) {
                    pGetLastDispenseInfoCharacteristic->setValue("Dispense command ignored: busy");
                    pGetLastDispenseInfoCharacteristic->notify();
                }
            }
        }
    }
    void onRead(NimBLECharacteristic *pCharacteristic) override {
        std::string uuid = pCharacteristic->getUUID().toString();
        Serial.print("Characteristic ");
        Serial.print(uuid.c_str());
        Serial.println(" read.");
    }
};

class MyServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer) {
        deviceConnected = true;
        Serial.println("Device connected");
    }
    void onDisconnect(NimBLEServer* pServer) {
        deviceConnected = false;
        Serial.println("Device disconnected");
        NimBLEDevice::startAdvertising();
        Serial.println("Restarting advertising...");
    }
};

void setup() {
    Serial.begin(115200);
    preferences.begin("pilldisp", false);
    Serial.println("Initializing Stepper Motor...");
    myStepper.setSpeed(10);
    Serial.println("Stepper Motor Initialized.");
    Serial.println("Initializing BLE (NimBLE)...");
    NimBLEDevice::init("PillDispenserESP32");
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    NimBLEService *pService = pServer->createService(SERVICE_UUID);
    Serial.println("Starting BLE Characteristic Setup...");

    pSetDeviceTimeCharacteristic = pService->createCharacteristic(
                                       CHAR_SET_DEVICE_TIME_UUID,
                                       NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    if(pSetDeviceTimeCharacteristic) pSetDeviceTimeCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    pSetDispenseScheduleCharacteristic = pService->createCharacteristic(
                                             CHAR_SET_DISPENSE_SCHEDULE_UUID, NIMBLE_PROPERTY::WRITE);
    if(pSetDispenseScheduleCharacteristic) pSetDispenseScheduleCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    pTriggerManualDispenseCharacteristic = pService->createCharacteristic(
                                               CHAR_TRIGGER_MANUAL_DISPENSE_UUID, NIMBLE_PROPERTY::WRITE);
    if(pTriggerManualDispenseCharacteristic) pTriggerManualDispenseCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

    Serial.println("Creating pGetDeviceTimeCharacteristic...");
    pGetDeviceTimeCharacteristic = pService->createCharacteristic(
                                    CHAR_GET_DEVICE_TIME_UUID,
                                    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (pGetDeviceTimeCharacteristic == nullptr) Serial.println("ERROR: pGetDeviceTimeCharacteristic is NULL!");
    else {
        pGetDeviceTimeCharacteristic->setValue("Device Time: Not Set");
        // CCCD (0x2902) is automatically added by NimBLE for NOTIFY/INDICATE properties
        Serial.println("pGetDeviceTimeCharacteristic created.");
    }

    Serial.println("Creating pGetDispenseScheduleCharacteristic...");
    pGetDispenseScheduleCharacteristic = pService->createCharacteristic(
                                            CHAR_GET_DISPENSE_SCHEDULE_UUID,
                                            NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (pGetDispenseScheduleCharacteristic == nullptr) Serial.println("ERROR: pGetDispenseScheduleCharacteristic is NULL!");
    else {
        pGetDispenseScheduleCharacteristic->setValue("Schedule: Empty");
        Serial.println("pGetDispenseScheduleCharacteristic created.");
    }

    Serial.println("Creating pGetLastDispenseInfoCharacteristic...");
    pGetLastDispenseInfoCharacteristic = pService->createCharacteristic(
                                            CHAR_GET_LAST_DISPENSE_INFO_UUID,
                                            NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (pGetLastDispenseInfoCharacteristic == nullptr) Serial.println("ERROR: pGetLastDispenseInfoCharacteristic is NULL!");
    else {
        pGetLastDispenseInfoCharacteristic->setValue("Last Dispense: None");
        Serial.println("pGetLastDispenseInfoCharacteristic created.");
    }

    Serial.println("Creating pGetTimeUntilNextDispenseCharacteristic...");
    pGetTimeUntilNextDispenseCharacteristic = pService->createCharacteristic(
                                                CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID,
                                                NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (pGetTimeUntilNextDispenseCharacteristic == nullptr) Serial.println("ERROR: pGetTimeUntilNextDispenseCharacteristic is NULL!");
    else {
        pGetTimeUntilNextDispenseCharacteristic->setValue("Next Dispense: Unknown");
        Serial.println("pGetTimeUntilNextDispenseCharacteristic created.");
    }

    Serial.println("Creating pGetDispenseLogCharacteristic...");
    pGetDispenseLogCharacteristic = pService->createCharacteristic(
                                        CHAR_GET_DISPENSE_LOG_UUID,
                                        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (pGetDispenseLogCharacteristic == nullptr) Serial.println("ERROR: pGetDispenseLogCharacteristic is NULL!");
    else {
        pGetDispenseLogCharacteristic->setValue("Log: Empty");
        Serial.println("pGetDispenseLogCharacteristic created.");
    }

    Serial.println("Finished BLE Characteristic Setup.");

    String loadedTime = preferences.getString("devTime", "Device Time: Not Set");
    if(pGetDeviceTimeCharacteristic) pGetDeviceTimeCharacteristic->setValue(loadedTime.c_str());
    Serial.print("Loaded device time: "); Serial.println(loadedTime);

    String loadedSchedule = preferences.getString("schedule", "Schedule: Empty");
    if(pGetDispenseScheduleCharacteristic) pGetDispenseScheduleCharacteristic->setValue(loadedSchedule.c_str());
    Serial.print("Loaded schedule: "); Serial.println(loadedSchedule);

    String lastDispense = preferences.getString("lastDisp", "Last Dispense: None");
    if(pGetLastDispenseInfoCharacteristic) pGetLastDispenseInfoCharacteristic->setValue(lastDispense.c_str());
    Serial.print("Loaded last dispense: "); Serial.println(lastDispense);

    pService->start();
    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    NimBLEDevice::startAdvertising();
    Serial.println("BLE GATT Server (NimBLE) started, advertising.");
    Serial.println("Device Name: PillDispenserESP32");
    Serial.print("Service UUID: "); Serial.println(SERVICE_UUID);

    Serial.println("\n--- BLE Characteristics (Actual UUIDs) ---");
    Serial.println("Writable Characteristics:");
    if (pSetDeviceTimeCharacteristic) Serial.println("Set Device Time:            " + String(pSetDeviceTimeCharacteristic->getUUID().toString().c_str()));
    if (pSetDispenseScheduleCharacteristic) Serial.println("Set Dispense Schedule:      " + String(pSetDispenseScheduleCharacteristic->getUUID().toString().c_str()));
    if (pTriggerManualDispenseCharacteristic) Serial.println("Trigger Manual Dispense:    " + String(pTriggerManualDispenseCharacteristic->getUUID().toString().c_str()));
    Serial.println("\nReadable Characteristics:");
    if (pGetDeviceTimeCharacteristic) Serial.println("Get Device Time:            " + String(pGetDeviceTimeCharacteristic->getUUID().toString().c_str()));
    if (pGetDispenseScheduleCharacteristic) Serial.println("Get Dispense Schedule:      " + String(pGetDispenseScheduleCharacteristic->getUUID().toString().c_str()));
    if (pGetLastDispenseInfoCharacteristic) Serial.println("Get Last Dispense Info:     " + String(pGetLastDispenseInfoCharacteristic->getUUID().toString().c_str()));
    if (pGetTimeUntilNextDispenseCharacteristic) Serial.println("Get Time Until Next Disp.:  " + String(pGetTimeUntilNextDispenseCharacteristic->getUUID().toString().c_str()));
    if (pGetDispenseLogCharacteristic) Serial.println("Get Dispense Log:           " + String(pGetDispenseLogCharacteristic->getUUID().toString().c_str()));
    Serial.println("------------------------------------------\n");
}

void loop() {
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
        Serial.println("Now connected.");
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
        Serial.println("Now disconnected.");
    }
    static unsigned long lastUpdateTime = 0;
    if (deviceConnected && (millis() - lastUpdateTime > 5000)) {
        lastUpdateTime = millis();
        String timeVal = "Next: " + String(millis()/1000) + "s (simulated)";
        if(pGetTimeUntilNextDispenseCharacteristic) {
            pGetTimeUntilNextDispenseCharacteristic->setValue(timeVal.c_str());
            pGetTimeUntilNextDispenseCharacteristic->notify();
            Serial.println("Notified: " + timeVal);
        }
    }
    if (isStepping) {
        if (millis() - lastStepTime >= stepInterval) {
            if (stepsRemaining > 0) {
                myStepper.step(1);
                stepsRemaining--;
                lastStepTime = millis();
            } else {
                isStepping = false;
                deenergizeStepper();
                Serial.println("Dispense complete.");
                if(pGetLastDispenseInfoCharacteristic) {
                    unsigned long completeTime = millis();
                    String completionMessage = "Dispense complete. Finished at: " + String(completeTime);
                    pGetLastDispenseInfoCharacteristic->setValue(completionMessage.c_str());
                    pGetLastDispenseInfoCharacteristic->notify();
                    preferences.putString("lastDisp", completionMessage.c_str());
                    Serial.println("Last dispense info (complete) saved to NVS.");
                }
            }
        }
    }
    delay(10);
}