package com.example.bruceir

import java.io.Serializable

data class Device(
    var name: String,
    val commands: MutableList<Command> = mutableListOf()
) : Serializable

data class Command(
    var name: String,
    var frequency: Int,
    var pattern: IntArray,
    var iconName: String? = null,
    var colorHex: String? = null,
    val type: String = "cmd"
) : Serializable

data class IrFolder(
    var name: String,
    val items: MutableList<Any> = mutableListOf(),
    val type: String = "folder"
) : Serializable