#include <Arduino.h>
#include <Stepper.h>

// BLE Libraries
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h> // For characteristic descriptors (optional but good practice)
#include <Preferences.h>

// Stepper motor configuration
const int stepsPerRevolution = 2048; // Using 2048 for a full rotation
const int OneChamber = stepsPerRevolution / 8;
const int in1Pin = 4; // In1
const int in2Pin = 5; // In2
const int in3Pin = 6; // In3
const int in4Pin = 7; // In4
Stepper myStepper(stepsPerRevolution, in1Pin, in2Pin, in3Pin, in4Pin);

Preferences preferences;
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
        Serial.print("Characteristic ");
        
        std::string uuid_str = pCharacteristic->getUUID().toString(); // Use a different name
        std::string value_str = pCharacteristic->getValue();          // Use a different name

        if (value_str.length() > 0) { // Use value_str
          Serial.print("New value: ");
          for (int i = 0; i < value_str.length(); i++) { // Use value_str
            Serial.print(value_str[i]);
          }
          Serial.println();
      }

        // Handle specific characteristic writes
        if (uuid_str == CHAR_SET_DEVICE_TIME_UUID) {
        Serial.println("Set_Device_Time received. Value: " + String(value_str.c_str()));
        preferences.putString("devTime", value_str.c_str()); // Save to NVS
        if (pGetDeviceTimeCharacteristic) {
            pGetDeviceTimeCharacteristic->setValue(std::string("Time Set ACK: ") + value_str);
            pGetDeviceTimeCharacteristic->notify();
        }
        Serial.println("Device time saved to NVS."); // Console log
         } else if (uuid_str == CHAR_SET_DISPENSE_SCHEDULE_UUID) {
        Serial.println("Set_Dispense_Schedule received. Value: " + String(value_str.c_str()));
        preferences.putString("schedule", value_str.c_str()); // Save to NVS
        // Update the readable characteristic as well
        if (pGetDispenseScheduleCharacteristic) {
            pGetDispenseScheduleCharacteristic->setValue(value_str); // Update with the new schedule
            pGetDispenseScheduleCharacteristic->notify();
        }
          Serial.println("Dispense schedule saved to NVS."); // Console log
        }  else if (uuid_str == CHAR_TRIGGER_MANUAL_DISPENSE_UUID) {
            Serial.println("Trigger_Manual_Dispense received. Value: " + String(value_str.c_str()));
            if (!isStepping) { // Only start if not already stepping
                startDispense(OneChamber); // Trigger the non-blocking stepper

                // Update characteristic to indicate dispensing started
                if(pGetLastDispenseInfoCharacteristic) {
                    // Using a more descriptive message including a timestamp for uniqueness
                    unsigned long currentTime = millis();
                    String progressMessage = "Dispensing... Started at: " + String(currentTime);
                    pGetLastDispenseInfoCharacteristic->setValue(progressMessage.c_str());
                    pGetLastDispenseInfoCharacteristic->notify();
                    preferences.putString("lastDisp", progressMessage.c_str());
                    Serial.println("Last dispense (in progress) saved to NVS.");
                }
            } else {
                Serial.println("Dispense command ignored, motor is busy.");
                // Notify the app that the command was ignored
                if(pGetLastDispenseInfoCharacteristic) {
                    pGetLastDispenseInfoCharacteristic->setValue("Dispense command ignored: busy");
                    pGetLastDispenseInfoCharacteristic->notify();
                    // No need to save "ignored" to preferences as "last dispense"
                }
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
    preferences.begin("pilldisp", false); // Initialize Preferences early

    // --- Stepper Motor Setup ---
    Serial.println("Initializing Stepper Motor...");
    myStepper.setSpeed(10);
    Serial.println("Stepper Motor Initialized.");

    // --- BLE Setup ---
    Serial.println("Initializing BLE...");
    BLEDevice::init("PillDispenserESP32"); 
    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    BLEService *pService = pServer->createService(SERVICE_UUID);

    Serial.println("Starting BLE Characteristic Setup...");
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
    Serial.println("Creating pGetDeviceTimeCharacteristic...");
    pGetDeviceTimeCharacteristic = pService->createCharacteristic(
                                    CHAR_GET_DEVICE_TIME_UUID,
                                    BLECharacteristic::PROPERTY_READ |
                                    BLECharacteristic::PROPERTY_NOTIFY);
    if (pGetDeviceTimeCharacteristic == nullptr) Serial.println("ERROR: pGetDeviceTimeCharacteristic is NULL!");
    else {
        pGetDeviceTimeCharacteristic->setValue("Device Time: Not Set");
        pGetDeviceTimeCharacteristic->addDescriptor(new BLE2902());
        Serial.println("pGetDeviceTimeCharacteristic created and descriptor added.");
    }

    Serial.println("Creating pGetDispenseScheduleCharacteristic...");
    pGetDispenseScheduleCharacteristic = pService->createCharacteristic(
                                            CHAR_GET_DISPENSE_SCHEDULE_UUID,
                                            BLECharacteristic::PROPERTY_READ |
                                            BLECharacteristic::PROPERTY_NOTIFY);
    if (pGetDispenseScheduleCharacteristic == nullptr) Serial.println("ERROR: pGetDispenseScheduleCharacteristic is NULL!");
    else {
        pGetDispenseScheduleCharacteristic->setValue("Schedule: Empty");
        pGetDispenseScheduleCharacteristic->addDescriptor(new BLE2902());
        Serial.println("pGetDispenseScheduleCharacteristic created and descriptor added.");
    }

    Serial.println("Creating pGetLastDispenseInfoCharacteristic...");
    pGetLastDispenseInfoCharacteristic = pService->createCharacteristic(
                                            CHAR_GET_LAST_DISPENSE_INFO_UUID,
                                            BLECharacteristic::PROPERTY_READ |
                                            BLECharacteristic::PROPERTY_NOTIFY);
    if (pGetLastDispenseInfoCharacteristic == nullptr) Serial.println("ERROR: pGetLastDispenseInfoCharacteristic is NULL!");
    else {
        pGetLastDispenseInfoCharacteristic->setValue("Last Dispense: None");
        pGetLastDispenseInfoCharacteristic->addDescriptor(new BLE2902());
        Serial.println("pGetLastDispenseInfoCharacteristic created and descriptor added.");
    }

    /*Serial.println("Creating pGetTimeUntilNextDispenseCharacteristic...");
    pGetTimeUntilNextDispenseCharacteristic = pService->createCharacteristic(
                                                CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID,
                                                BLECharacteristic::PROPERTY_READ |
                                                BLECharacteristic::PROPERTY_NOTIFY);
    if (pGetTimeUntilNextDispenseCharacteristic == nullptr) Serial.println("ERROR: pGetTimeUntilNextDispenseCharacteristic is NULL!");
    else {
        pGetTimeUntilNextDispenseCharacteristic->setValue("Next Dispense: Unknown");
        pGetTimeUntilNextDispenseCharacteristic->addDescriptor(new BLE2902());
        Serial.println("pGetTimeUntilNextDispenseCharacteristic created and descriptor added.");
    }

    Serial.println("Creating pGetDispenseLogCharacteristic...");
    pGetDispenseLogCharacteristic = pService->createCharacteristic(
                                        CHAR_GET_DISPENSE_LOG_UUID,
                                        BLECharacteristic::PROPERTY_READ |
                                        BLECharacteristic::PROPERTY_NOTIFY);
    if (pGetDispenseLogCharacteristic == nullptr) Serial.println("ERROR: pGetDispenseLogCharacteristic is NULL!");
    else {
        pGetDispenseLogCharacteristic->setValue("Log: Empty");
        pGetDispenseLogCharacteristic->addDescriptor(new BLE2902());
        Serial.println("pGetDispenseLogCharacteristic created and descriptor added.");
}*/

Serial.println("Finished BLE Characteristic Setup.");
    pGetDispenseLogCharacteristic = pService->createCharacteristic(
                                        CHAR_GET_DISPENSE_LOG_UUID,
                                        BLECharacteristic::PROPERTY_READ |
                                        BLECharacteristic::PROPERTY_NOTIFY
                                    );
    pGetDispenseLogCharacteristic->setValue("Log: Empty");
    pGetDispenseLogCharacteristic->addDescriptor(new BLE2902());

    String loadedTime = preferences.getString("devTime", "Device Time: Not Set");
    pGetDeviceTimeCharacteristic->setValue(loadedTime.c_str());
    Serial.print("Loaded device time: "); Serial.println(loadedTime);

    String loadedSchedule = preferences.getString("schedule", "Schedule: Empty");
    pGetDispenseScheduleCharacteristic->setValue(loadedSchedule.c_str());
    Serial.print("Loaded schedule: "); Serial.println(loadedSchedule);

    // You might also want to load a default/persisted value for LastDispenseInfo, etc.
    String lastDispense = preferences.getString("lastDisp", "Last Dispense: None");
    pGetLastDispenseInfoCharacteristic->setValue(lastDispense.c_str());
    Serial.print("Loaded last dispense: "); Serial.println(lastDispense);
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

    Serial.println("\n--- BLE Characteristics ---");
    Serial.println("Writable Characteristics:");
    Serial.println("Set Device Time:            " + String(CHAR_SET_DEVICE_TIME_UUID));
    Serial.println("Set Dispense Schedule:      " + String(CHAR_SET_DISPENSE_SCHEDULE_UUID));
    Serial.println("Trigger Manual Dispense:    " + String(CHAR_TRIGGER_MANUAL_DISPENSE_UUID));

    Serial.println("\nReadable Characteristics:");
    Serial.println("Get Device Time:            " + String(CHAR_GET_DEVICE_TIME_UUID));
    Serial.println("Get Dispense Schedule:      " + String(CHAR_GET_DISPENSE_SCHEDULE_UUID));
    Serial.println("Get Last Dispense Info:     " + String(CHAR_GET_LAST_DISPENSE_INFO_UUID));
    Serial.println("Get Time Until Next Disp.:  " + String(CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID));
    Serial.println("Get Dispense Log:           " + String(CHAR_GET_DISPENSE_LOG_UUID));
    Serial.println("-----------------------------\n");

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
        //pGetTimeUntilNextDispenseCharacteristic->setValue(timeVal.c_str());
        //pGetTimeUntilNextDispenseCharacteristic->notify(); // Send notification to subscribed client
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

                if(pGetLastDispenseInfoCharacteristic) {
                    unsigned long completeTime = millis();
                    String completionMessage = "Dispense complete. Finished at: " + String(completeTime);
                    pGetLastDispenseInfoCharacteristic->setValue(completionMessage.c_str());
                    pGetLastDispenseInfoCharacteristic->notify();
                    preferences.putString("lastDisp", completionMessage.c_str()); // Save the completion status
                    Serial.println("Last dispense info (complete) saved to NVS.");
                }
            }
        }
  delay(10);
}
}
