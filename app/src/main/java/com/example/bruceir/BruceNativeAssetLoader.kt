package com.example.bruceir

import android.content.Context
import android.util.Log

object BruceNativeAssetLoader {

    fun loadFlipperFolder(context: Context): IrFolder {
        val rootFolder = IrFolder("🐬 Flipper IRDB")
        try {
            val basePath = "Flipper-IRDB-main/Flipper-IRDB-main"
            val topEntries = context.assets.list(basePath) ?: emptyArray()
            for (entry in topEntries) {
                if (entry.startsWith(".")) continue
                val fullPath = "$basePath/$entry"
                val subFolder = scanAssetDirectory(context, fullPath, entry)
                if (subFolder.items.isNotEmpty()) {
                    rootFolder.items.add(subFolder)
                }
            }
        } catch (e: Exception) {
            Log.e("NativeLoader", "Error loading asset Flipper DB: ${e.message}", e)
        }
        return rootFolder
    }

    private fun scanAssetDirectory(context: Context, assetDir: String, folderName: String): IrFolder {
        val currentFolder = IrFolder(folderName.replace("_", " "))
        try {
            val entries = context.assets.list(assetDir) ?: emptyArray()
            for (entry in entries) {
                if (entry.startsWith(".")) continue
                val childPath = "$assetDir/$entry"

                if (entry.contains(".")) {
                    if (entry.endsWith(".ir", true)) {
                        try {
                            val content = context.assets.open(childPath).bufferedReader().use { it.readText() }
                            val commands = BruceUtils.parseIrContent(content)
                            if (commands.isNotEmpty()) {
                                val remoteName = entry.substringBeforeLast(".").replace("_", " ")
                                val remoteFolder = IrFolder(remoteName, commands.toMutableList<Any>())
                                currentFolder.items.add(remoteFolder)
                            }
                        } catch (e: Exception) {
                            Log.e("NativeLoader", "Error reading .ir file $childPath: ${e.message}")
                        }
                    }
                } else {
                    val subFolder = scanAssetDirectory(context, childPath, entry)
                    if (subFolder.items.isNotEmpty()) {
                        currentFolder.items.add(subFolder)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NativeLoader", "Error scanning dir $assetDir: ${e.message}")
        }
        return currentFolder
    }
}
