package com.example.bruceir

import java.io.Serializable

interface IrItem : Serializable {
    var name: String
    val type: String
}

data class Device(
    var name: String,
    val commands: MutableList<Command> = mutableListOf()
) : Serializable

data class Command(
    override var name: String,
    var frequency: Int,
    var pattern: IntArray,
    var iconName: String? = null,
    var colorHex: String? = null,
    var protocol: String? = null,
    var address: String? = null,
    var command: String? = null,
    override val type: String = "cmd"
) : IrItem {
    var sourceRemotePath: List<String>? = null

    fun getEffectivePattern(): IntArray {
        if (pattern.isNotEmpty()) return pattern
        val prot = protocol ?: return intArrayOf(100, 100)
        val addr = address ?: "0x00"
        val cmd = command ?: "0x00"
        return IrProtocolEncoder.encodeToRawPattern(prot, addr, cmd)
    }
}

object IrCommandOrigin {
    data class Match(val command: Command, val remotePath: List<String>)

    fun searchCommands(root: IrFolder): List<Match> {
        val matches = mutableListOf<Match>()
        fun search(folder: IrFolder, path: List<String>) {
            folder.items.forEach { item ->
                when (item) {
                    is Command -> if (item.frequency > 0 && (item.pattern.isNotEmpty() || item.protocol != null)) {
                        matches.add(Match(item, path))
                    }
                    is IrFolder -> search(item, path + item.name)
                }
            }
        }
        search(root, emptyList())
        return matches
    }

    fun findCommandPath(root: IrFolder, target: Command): List<String>? {
        fun search(folder: IrFolder, path: List<String>): List<String>? {
            folder.items.forEach { item ->
                when (item) {
                    is Command -> if (
                        item.name == target.name &&
                        item.frequency == target.frequency &&
                        item.pattern.contentEquals(target.pattern)
                    ) return path
                    is IrFolder -> search(item, path + item.name)?.let { return it }
                }
            }
            return null
        }

        return search(root, emptyList())
    }

    fun findFolderPath(root: IrFolder, target: IrFolder): List<String>? {
        fun search(folder: IrFolder, path: List<String>): List<String>? {
            if (folder === target) return path
            folder.items.forEach { item ->
                if (item is IrFolder) search(item, path + item.name)?.let { return it }
            }
            return null
        }

        return search(root, emptyList())
    }
}

data class IrFolder(
    override var name: String,
    val items: MutableList<Any> = mutableListOf(),
    var iconName: String? = null,
    var colorHex: String? = null,
    var isListView: Boolean = false,
    var isDpadOrganized: Boolean = false,
    var autoDpadOrganize: Boolean? = null,
    override val type: String = "folder"
) : IrItem

data class MacroSet(
    var name: String,
    val commands: MutableList<Command> = mutableListOf(),
    var delayMs: Long = 600,
    var isLooping: Boolean = false
) : Serializable