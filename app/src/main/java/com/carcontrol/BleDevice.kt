package com.carcontrol

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isCar: Boolean
)
