package com.example.bruceir

import java.io.Serializable

data class Command(
    var name: String,
    var frequency: Int,
    var pattern: IntArray,
    val type: String = "cmd"
) : Serializable

data class IrFolder(
    var name: String,
    val items: MutableList<Any> = mutableListOf(),
    val type: String = "folder"
) : Serializable