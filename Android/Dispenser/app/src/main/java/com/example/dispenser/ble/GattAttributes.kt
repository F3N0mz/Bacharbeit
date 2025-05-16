// GattAttributes.kt
package com.example.dispenser.ble

import java.util.UUID

object GattAttributes {
    // Service UUID
    val SERVICE_UUID: UUID = UUID.fromString("03339647-3f4e-43df-abff-fac54287cf1a")

    // Writable Characteristics
    val CHAR_SET_DEVICE_TIME_UUID: UUID = UUID.fromString("65232f1d-618a-4268-9050-0548142a4536")
    val CHAR_SET_DISPENSE_SCHEDULE_UUID: UUID = UUID.fromString("999c584e-06c0-49a1-995a-66b7c802ac1b")
    val CHAR_TRIGGER_MANUAL_DISPENSE_UUID: UUID = UUID.fromString("36bb95f2-e57e-4db9-b9aa-fb6541ee784e")

    // Readable Characteristics
    val CHAR_GET_DEVICE_TIME_UUID: UUID = UUID.fromString("272ee276-e37e-4d78-8c5e-bb7225d35074")
    val CHAR_GET_DISPENSE_SCHEDULE_UUID: UUID = UUID.fromString("b53c2ed4-ae26-476d-8414-011a025dddfc")
    val CHAR_GET_LAST_DISPENSE_INFO_UUID: UUID = UUID.fromString("40d3b5d8-5480-4b7b-a115-5fe86bf17d7d")
    val CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID: UUID = UUID.fromString("4b14acc4-768a-43e1-9d6c-0d97307e2666")
    val CHAR_GET_DISPENSE_LOG_UUID: UUID = UUID.fromString("6f182da7-c5a8-40ab-a637-f97ed6b5777b")

    // Descriptor for enabling notifications/indications
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}