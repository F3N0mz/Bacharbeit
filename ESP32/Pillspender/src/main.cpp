#include <Arduino.h>
#include <Stepper.h>

// BLE Libraries
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h> // For characteristic descriptors (optional but good practice)

// Stepper motor configuration
const int stepsPerRevolution = 2048; // Using 2048 for a full rotation
const int OneChamber = stepsPerRevolution / 8;
const int in1Pin = 4; // In1
const int in2Pin = 5; // In2
const int in3Pin = 6; // In3
const int in4Pin = 7; // In4
Stepper myStepper(stepsPerRevolution, in1Pin, in2Pin, in3Pin, in4Pin);

bool isStepping = false;
int stepsRemaining = 0;
unsigned long lastStepTime = 0;
const unsigned long stepInterval = 2; // Milliseconds between steps (adjust for speed)
                                    // 2048 steps / (desired_RPM / 60) / 1000 = stepInterval_ms
                                    // e.g., 10 RPM: 2048 / (10/60) / 1000 = 2048 / 0.166 / 1000 = 12288 / 1000 = ~12ms (too slow)
                                    // The Stepper library's setSpeed(RPM) translates to a delay.
                                    // For setSpeed(10), delay is 60*1000/stepsPerRevolution/10 = 60000/2048/10 = ~2.9ms.
                                    // Let's aim for something similar, e.g., 3ms.

void deenergizeStepper() {
    digitalWrite(in1Pin, LOW);
    digitalWrite(in2Pin, LOW);
    digitalWrite(in3Pin, LOW);
    digitalWrite(in4Pin, LOW);
    Serial.println("Stepper coils de-energized.");
}

void startDispense(int numSteps) {
    if (!isStepping) { // Prevent starting a new move if one is in progress
        Serial.print("Starting dispense of ");
        Serial.print(numSteps);
        Serial.println(" steps.");
        stepsRemaining = numSteps;
        isStepping = true;
        // No need to energize here, first step will do it.
        // myStepper.setSpeed(10); // Ensure speed is set if it could change
    }
}


// BLE Service and Characteristic UUIDs
// You can generate your own unique UUIDs using a tool like https://www.uuidgenerator.net/
#define SERVICE_UUID           "03339647-3f4e-43df-abff-fac54287cf1a" // Main service UUID

// Writable Characteristics
#define CHAR_SET_DEVICE_TIME_UUID         "65232f1d-618a-4268-9050-0548142a4536"
#define CHAR_SET_DISPENSE_SCHEDULE_UUID   "999c584e-06c0-49a1-995a-66b7c802ac1b"
#define CHAR_TRIGGER_MANUAL_DISPENSE_UUID "36bb95f2-e57e-4db9-b9aa-fb6541ee784e"

// Readable Characteristics
#define CHAR_GET_DEVICE_TIME_UUID             "272ee276-e37e-4d78-8c5e-bb7225d35074"
#define CHAR_GET_DISPENSE_SCHEDULE_UUID       "b53c2ed4-ae26-476d-8414-011a025dddfc"
#define CHAR_GET_LAST_DISPENSE_INFO_UUID    "40d3b5d8-5480-4b7b-a115-5fe86bf17d7d"
#define CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID "4b14acc4-768a-43e1-9d6c-0d97307e2666"
#define CHAR_GET_DISPENSE_LOG_UUID            "6f182da7-c5a8-40ab-a637-f97ed6b5777b"
// You might also want a characteristic for battery level if it's battery powered
// #define CHAR_BATTERY_LEVEL_UUID "..."

// Pointers to BLE Characteristics (so we can update them later)
BLECharacteristic *pSetDeviceTimeCharacteristic;
BLECharacteristic *pSetDispenseScheduleCharacteristic;
BLECharacteristic *pTriggerManualDispenseCharacteristic;
BLECharacteristic *pGetDeviceTimeCharacteristic;
BLECharacteristic *pGetDispenseScheduleCharacteristic;
BLECharacteristic *pGetLastDispenseInfoCharacteristic;
BLECharacteristic *pGetTimeUntilNextDispenseCharacteristic;
BLECharacteristic *pGetDispenseLogCharacteristic;
// BLECharacteristic *pBatteryLevelCharacteristic;

bool deviceConnected = false;
bool oldDeviceConnected = false;

// Callback class for characteristic events (writes)
class MyCharacteristicCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        std::string uuid = pCharacteristic->getUUID().toString();
        std::string value = pCharacteristic->getValue();
        Serial.print("Characteristic ");
        Serial.print(uuid.c_str());
        Serial.print(" written: ");
        
        if (value.length() > 0) {
            Serial.print("New value: ");
            for (int i = 0; i < value.length(); i++) {
                Serial.print(value[i]);
            }
            Serial.println();
        }

        // Handle specific characteristic writes
        if (uuid == CHAR_SET_DEVICE_TIME_UUID) {
            Serial.println("Set_Device_Time received. Value: " + String(value.c_str()));
            // TODO: Implement time setting logic
            // For now, just acknowledge by updating the readable characteristic if it exists
            if (pGetDeviceTimeCharacteristic) {
                pGetDeviceTimeCharacteristic->setValue(std::string("Time Set ACK: ") + value);
                pGetDeviceTimeCharacteristic->notify(); // If notifications are enabled
            }
        } else if (uuid == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
            Serial.println("Set_Dispense_Schedule received. Value: " + String(value.c_str()));
            // TODO: Implement schedule setting logic
        } else if (uuid == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
              Serial.println("Trigger_Manual_Dispense received. Value: " + String(value.c_str()));
              if (!isStepping) { // Only start if not already stepping
                  startDispense(OneChamber); // Trigger the non-blocking stepper
              }
              if(pGetLastDispenseInfoCharacteristic) {
                  pGetLastDispenseInfoCharacteristic->setValue("Manual dispense initiated");
                  pGetLastDispenseInfoCharacteristic->notify();
           }
}
    }

    void onRead(BLECharacteristic *pCharacteristic) override {
        std::string uuid = pCharacteristic->getUUID().toString();
        Serial.print("Characteristic ");
        Serial.print(uuid.c_str());
        Serial.println(" read.");

        // You can dynamically set values here if needed before a read,
        // though for simpler cases, just setting the value elsewhere and letting BLE stack handle it is fine.
        // Example: if (uuid == CHAR_GET_DEVICE_TIME_UUID) { pCharacteristic->setValue(getCurrentTimeAsString()); }
    }
};

// Callback class for server events (connect/disconnect)
class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("Device connected");
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("Device disconnected");
        // It's good practice to restart advertising on disconnect if you want it to be discoverable again
        BLEDevice::startAdvertising();
        Serial.println("Restarting advertising...");
    }
};


void setup() {
    Serial.begin(115200);
    Serial.println("Pill Dispenser Starting...");

    // --- Stepper Motor Setup ---
    Serial.println("Initializing Stepper Motor...");
    myStepper.setSpeed(10); // Set speed, e.g., 10 RPM
    Serial.println("Stepper Motor Initialized.");

    // --- BLE Setup ---
    Serial.println("Initializing BLE...");
    BLEDevice::init("PillDispenserESP32"); // Set device name

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks()); // Set server callbacks

    BLEService *pService = pServer->createService(SERVICE_UUID);

    // --- Writable Characteristics ---
    pSetDeviceTimeCharacteristic = pService->createCharacteristic(
                                       CHAR_SET_DEVICE_TIME_UUID,
                                       BLECharacteristic::PROPERTY_WRITE |
                                       BLECharacteristic::PROPERTY_WRITE_NR // Allow write without response for speed
                                   );
    pSetDeviceTimeCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pSetDeviceTimeCharacteristic->addDescriptor(new BLE2902()); // Standard descriptor for notifications/indications

    pSetDispenseScheduleCharacteristic = pService->createCharacteristic(
                                             CHAR_SET_DISPENSE_SCHEDULE_UUID,
                                             BLECharacteristic::PROPERTY_WRITE
                                         );
    pSetDispenseScheduleCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pSetDispenseScheduleCharacteristic->addDescriptor(new BLE2902());

    pTriggerManualDispenseCharacteristic = pService->createCharacteristic(
                                               CHAR_TRIGGER_MANUAL_DISPENSE_UUID,
                                               BLECharacteristic::PROPERTY_WRITE
                                           );
    pTriggerManualDispenseCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pTriggerManualDispenseCharacteristic->addDescriptor(new BLE2902());

    // --- Readable Characteristics ---
    pGetDeviceTimeCharacteristic = pService->createCharacteristic(
                                       CHAR_GET_DEVICE_TIME_UUID,
                                       BLECharacteristic::PROPERTY_READ |
                                       BLECharacteristic::PROPERTY_NOTIFY // Allow notifications
                                   );
    pGetDeviceTimeCharacteristic->setValue("Device Time: Not Set");
    pGetDeviceTimeCharacteristic->addDescriptor(new BLE2902()); // Needed for notifications

    pGetDispenseScheduleCharacteristic = pService->createCharacteristic(
                                             CHAR_GET_DISPENSE_SCHEDULE_UUID,
                                             BLECharacteristic::PROPERTY_READ |
                                             BLECharacteristic::PROPERTY_NOTIFY
                                         );
    pGetDispenseScheduleCharacteristic->setValue("Schedule: Empty");
    pGetDispenseScheduleCharacteristic->addDescriptor(new BLE2902());

    pGetLastDispenseInfoCharacteristic = pService->createCharacteristic(
                                             CHAR_GET_LAST_DISPENSE_INFO_UUID,
                                             BLECharacteristic::PROPERTY_READ |
                                             BLECharacteristic::PROPERTY_NOTIFY
                                         );
    pGetLastDispenseInfoCharacteristic->setValue("Last Dispense: None");
    pGetLastDispenseInfoCharacteristic->addDescriptor(new BLE2902());

    pGetTimeUntilNextDispenseCharacteristic = pService->createCharacteristic(
                                                  CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID,
                                                  BLECharacteristic::PROPERTY_READ |
                                                  BLECharacteristic::PROPERTY_NOTIFY
                                              );
    pGetTimeUntilNextDispenseCharacteristic->setValue("Next Dispense: Unknown");
    pGetTimeUntilNextDispenseCharacteristic->addDescriptor(new BLE2902());

    pGetDispenseLogCharacteristic = pService->createCharacteristic(
                                        CHAR_GET_DISPENSE_LOG_UUID,
                                        BLECharacteristic::PROPERTY_READ |
                                        BLECharacteristic::PROPERTY_NOTIFY
                                    );
    pGetDispenseLogCharacteristic->setValue("Log: Empty");
    pGetDispenseLogCharacteristic->addDescriptor(new BLE2902());

    // --- Start BLE Service and Advertising ---
    pService->start();
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); // Helps with iOS connection parameters
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();
    Serial.println("BLE GATT Server started, advertising.");
    Serial.println("Device Name: PillDispenserESP32");
    Serial.print("Service UUID: "); Serial.println(SERVICE_UUID);
}

void loop() {
    // Your original stepper logic can go here, or be triggered by BLE commands
    // For now, let's keep it simple and only operate stepper based on BLE or other logic
    // sleep(5); // Using delay() or sleep() in loop can interfere with BLE responsiveness
    // Serial.println("Rotating One chamber...");
    // myStepper.step(OneChamber); // Rotate one full Chamber clockwise
    // Serial.println("Rotation complete.");

    // Handle connection state changes (e.g., for visual feedback or power saving)
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
        Serial.println("Now connected.");
        // Potentially stop advertising if you only want one client
        // BLEDevice::getAdvertising()->stop();
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
        Serial.println("Now disconnected.");
        // Advertising is restarted in onDisconnect callback
    }

    // Example: periodically update a characteristic (e.g., a simulated sensor reading or time)
    // This is just for demonstration. Real updates would be event-driven or based on actual data.
    static unsigned long lastUpdateTime = 0;
    if (deviceConnected && (millis() - lastUpdateTime > 5000)) { // Every 5 seconds
        lastUpdateTime = millis();
        // Example: Simulate updating time until next dispense
        String timeVal = "Next: " + String(millis()/1000) + "s (simulated)";
        pGetTimeUntilNextDispenseCharacteristic->setValue(timeVal.c_str());
        pGetTimeUntilNextDispenseCharacteristic->notify(); // Send notification to subscribed client
        Serial.println("Notified: " + timeVal);
    }
    if (isStepping) {
        if (millis() - lastStepTime >= stepInterval) {
            if (stepsRemaining > 0) {
                myStepper.step(1); // Step one step in the current direction
                stepsRemaining--;
                lastStepTime = millis();
            } else {
                isStepping = false;
                deenergizeStepper(); // De-energize coils after movement
                Serial.println("Dispense complete.");
                // Optionally, update a BLE characteristic here to indicate completion
                if(pGetLastDispenseInfoCharacteristic) {
                    pGetLastDispenseInfoCharacteristic->setValue("Manual dispense completed");
                    pGetLastDispenseInfoCharacteristic->notify();
                }
            }
        }

    delay(10); // Small delay to yield to other tasks (like BLE stack)
}