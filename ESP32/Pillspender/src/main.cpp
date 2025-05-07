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

// BLE Service and Characteristic UUIDs
// You can generate your own unique UUIDs using a tool like https://www.uuidgenerator.net/
#define SERVICE_UUID           "4fafc201-1fb5-459e-8fcc-c5c9c331914b" // Main service UUID

// Writable Characteristics
#define CHAR_SET_DEVICE_TIME_UUID         "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_SET_DISPENSE_SCHEDULE_UUID   "c8c5b01a-3158-4259-8797-77159a0a7f7e"
#define CHAR_TRIGGER_MANUAL_DISPENSE_UUID "d1d0a9f0-42f0-4965-ae54-968c78f8279a"

// Readable Characteristics
#define CHAR_GET_DEVICE_TIME_UUID             "a0d1e33c-8f86-4f3b-9075-8ea28f783b5e"
#define CHAR_GET_DISPENSE_SCHEDULE_UUID       "b3e9a2f8-1c90-4d9a-9381-8245cd789b9c"
#define CHAR_GET_LAST_DISPENSE_INFO_UUID    "e7f3b5d0-2e76-4a8c-9a0f-9f8e7d6c5b4a"
#define CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID "f0a9d8c4-3b12-4e6d-8c5b-1a09f8e7d6c5"
#define CHAR_GET_DISPENSE_LOG_UUID            "0c8b7a6e-4d5f-499a-8b3c-2e1d0f9a8b7c"
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
                pGetDeviceTimeCharacteristic->setValue("Time Set ACK: " + String(value.c_str()));
                pGetDeviceTimeCharacteristic->notify(); // If notifications are enabled
            }
        } else if (uuid == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
            Serial.println("Set_Dispense_Schedule received. Value: " + String(value.c_str()));
            // TODO: Implement schedule setting logic
        } else if (uuid == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
            Serial.println("Trigger_Manual_Dispense received. Value: " + String(value.c_str()));
            // TODO: Implement manual dispense logic (e.g., call myStepper.step(OneChamber))
            // For now, simulate and update last dispense info
            if(pGetLastDispenseInfoCharacteristic) {
                pGetLastDispenseInfoCharacteristic->setValue("Manual dispense triggered now");
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


    delay(10); // Small delay to yield to other tasks (like BLE stack)
}