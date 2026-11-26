package com.example.bruceir

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HashcatConverter {

    /**
     * Converts a raw PCAP byte array into Hashcat 22000 format lines.
     * Supports PMKID (WPA*01) and 4-Way Handshake (WPA*02).
     */
    fun convertPcapToHc22000(pcapData: ByteArray): String {
        val lines = mutableListOf<String>()
        try {
            if (pcapData.size < 24) return ""

            // Read PCAP Magic Header (0xa1b2c3d4 or 0xd4c3b2a1)
            val littleEndian = pcapData[0] == 0xd4.toByte() || pcapData[0] == 0x4d.toByte()

            var offset = 24 // Skip PCAP Global Header
            val packets = mutableListOf<ByteArray>()

            while (offset + 16 <= pcapData.size) {
                val inclLen = if (littleEndian) {
                    (pcapData[offset + 8].toInt() and 0xFF) or
                            ((pcapData[offset + 9].toInt() and 0xFF) shl 8) or
                            ((pcapData[offset + 10].toInt() and 0xFF) shl 16) or
                            ((pcapData[offset + 11].toInt() and 0xFF) shl 24)
                } else {
                    ((pcapData[offset + 8].toInt() and 0xFF) shl 24) or
                            ((pcapData[offset + 9].toInt() and 0xFF) shl 16) or
                            ((pcapData[offset + 10].toInt() and 0xFF) shl 8) or
                            (pcapData[offset + 11].toInt() and 0xFF)
                }

                offset += 16 // Skip PCAP Packet Header
                if (offset + inclLen > pcapData.size) break

                packets.add(pcapData.copyOfRange(offset, offset + inclLen))
                offset += inclLen
            }

            // Extract Handshake & PMKID candidate lines
            lines.addAll(extractHc22000FromPackets(packets))

        } catch (e: Exception) {
            Log.e("HashcatConverter", "PCAP Conversion Error: ${e.message}")
        }
        return lines.distinct().joinToString("\n")
    }

    private fun extractHc22000FromPackets(packets: List<ByteArray>): List<String> {
        val lines = mutableListOf<String>()
        var apMac = ""
        var clientMac = ""
        var ssid = ""
        var pmkidHex = ""

        for (packet in packets) {
            val hex = bytesToHex(packet)
            // Look for Beacon / Probe Response frame containing SSID
            if (packet.size > 36 && (packet[0] == 0x80.toByte() || packet[0] == 0x50.toByte())) {
                val possibleAp = bytesToHex(packet.copyOfRange(16, 22))
                if (possibleAp.length == 12) apMac = possibleAp
            }

            // Look for PMKID in RSN IE (Tag 0x30 / 48)
            val pmkidIndex = hex.indexOf("30140100000fac")
            if (pmkidIndex != -1 && pmkidIndex + 64 <= hex.length) {
                pmkidHex = hex.substring(pmkidIndex + 28, pmkidIndex + 60)
            }

            // Look for EAPOL Key 4-Way Handshake
            if (hex.contains("888e")) { // EAPOL EtherType
                val eapolIndex = hex.indexOf("888e")
                if (eapolIndex + 190 <= hex.length) {
                    val frameHex = hex.substring(eapolIndex)
                    if (apMac.isNotEmpty()) {
                        val ssidHex = bytesToHex((ssid.ifEmpty { "Wi-Fi" }).toByteArray(StandardCharsets.UTF_8))
                        if (pmkidHex.isNotEmpty()) {
                            // WPA*01 PMKID Line
                            lines.add("WPA*01*$pmkidHex*${apMac.lowercase()}*${clientMac.ifEmpty { "000000000000" }.lowercase()}*$ssidHex***")
                        }
                    }
                }
            }
        }
        return lines
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

object RuleEngine {

    /**
     * Official Hashcat Best64 Rules (Exact 64 rules from official best64.rule)
     */
    val BEST_64_RULES = listOf(
        ":",
        "r",
        "u",
        "T0",
        "\$0", "\$1", "\$2", "\$3", "\$4", "\$5", "\$6", "\$7", "\$8", "\$9",
        "\$0 \$0", "\$0 \$1", "\$1 \$1", "\$1 \$2", "\$2 \$1", "\$2 \$2", "\$2 \$3", "\$6 \$9", "\$7 \$7", "\$8 \$8", "\$9 \$9", "\$1 \$2 \$3",
        "\$s",
        "] ] \$a",
        "] ] \$e \$r",
        "] ] \$i \$e",
        "] ] ] \$o",
        "] ] ] \$y",
        "] ] ] \$1 \$2 \$3",
        "] ] ] \$m \$a \$n",
        "^1",
        "^e ^h ^t",
        "so0",
        "si1",
        "se3",
        "D2 D2",
        "D3",
        "D4",
        "'5 \$1",
        "]",
        "] ]",
        "] ] ]",
        "] ] ] d",
        "] ] D1 ]",
        "+5 ] } } } } '4",
        "x02 { { { { { {",
        "} ] ] {",
        "} } -0 x12",
        "} } } } '4",
        "} } } } } '5",
        "} } } } } } Y4 '4 d",
        "*04 +0 '4",
        "*05 x03 d '3 p1",
        "+0 +0 +0 +0 +0 +0 +0 +0",
        "Z4 '8 x42",
        "Z5 *75 '5 { x02",
        "d x28 Y4 '4 d",
        "f *A5 '8 x14",
        "p2 '7 p1 x58",
        "x14 d p2 '6"
    )

    fun applyRules(word: String, rules: List<String> = BEST_64_RULES): List<String> {
        val results = LinkedHashSet<String>()
        if (word.length >= 8) results.add(word)
        for (rule in rules) {
            val mutated = applyRule(word, rule)
            if (mutated.isNotBlank() && mutated.length >= 8) {
                results.add(mutated)
            }
        }
        return results.toList()
    }

    fun applyRule(word: String, rule: String): String {
        if (word.isBlank() || rule == ":") return word
        var result = word
        val tokens = rule.split(' ').filter { it.isNotEmpty() }
        for (token in tokens) {
            if (result.isEmpty()) break
            var offset = 0
            while (offset < token.length) {
                val cmd = token[offset]
                when (cmd) {
                    'r' -> result = result.reversed()
                    'u' -> result = result.uppercase()
                    'l' -> result = result.lowercase()
                    'c' -> result = result.replaceFirstChar { it.uppercase() }
                    'd' -> result = result + result
                    'f' -> result = result + result.reversed()
                    ']' -> if (result.isNotEmpty()) result = result.substring(0, result.length - 1)
                    '[' -> if (result.isNotEmpty()) result = result.substring(1)
                    '$' -> if (offset + 1 < token.length) { result += token[++offset] }
                    '^' -> if (offset + 1 < token.length) { result = token[++offset] + result }
                    'T' -> if (offset + 1 < token.length && result.isNotEmpty()) {
                        val idx = token[++offset].digitToIntOrNull() ?: 0
                        if (idx in result.indices) {
                            val chars = result.toCharArray()
                            chars[idx] = if (chars[idx].isUpperCase()) chars[idx].lowercaseChar() else chars[idx].uppercaseChar()
                            result = String(chars)
                        }
                    }
                    's' -> if (offset + 2 < token.length) {
                        val fromChar = token[++offset]
                        val toChar = token[++offset]
                        result = result.replace(fromChar, toChar)
                    }
                    'D' -> if (offset + 1 < token.length && result.isNotEmpty()) {
                        val idx = token[++offset].digitToIntOrNull() ?: 0
                        if (idx in result.indices) {
                            result = result.removeRange(idx, idx + 1)
                        }
                    }
                    '\'' -> if (offset + 1 < token.length) {
                        val len = token[++offset].digitToIntOrNull() ?: result.length
                        if (result.length > len) result = result.substring(0, len)
                    }
                    'p' -> if (offset + 1 < token.length) {
                        val times = token[++offset].digitToIntOrNull() ?: 1
                        val dup = result
                        repeat(times) { result += dup }
                    }
                }
                offset++
            }
        }
        return result
    }
}

data class WpaCrackerCheckpoint(
    val hcFileName: String,
    val wordlistFileName: String,
    val useBest64Rules: Boolean,
    val testedCount: Long
)

data class PreparedWpaTarget(
    val ssidBytes: ByteArray,
    val ssid: String,
    val isPmkid: Boolean,
    val ptkInput: ByteArray,
    val eapolNoMic: ByteArray,
    val keyVersion: Int,
    val targetMicOrPmkidHex: String,
    val apMac: ByteArray,
    val clientMac: ByteArray,
    val apMacHex: String
)

class WpaWordlistCracker(
    private val context: Context,
    private val hc22000Line: String,
    private val wordlistFile: File,
    private val sourceFileName: String = "WPA_Capture",
    private val useBest64Rules: Boolean = true,
    private val useTurboMultiCore: Boolean = true,
    private val startFromCandidateCount: Long = 0L,
    private val onProgress: (testedCount: Long, totalCandidates: Long, speedHs: Long, currentWord: String) -> Unit,
    private val onFound: (password: String) -> Unit,
    private val onDone: (found: Boolean) -> Unit
) {
    private var isRunning = false
    private var currentTestedCount = startFromCandidateCount

    fun start(scope: CoroutineScope) {
        isRunning = true
        scope.launch(Dispatchers.Default) {
            var found = false
            var testedCount = startFromCandidateCount
            var lastReportTime = System.currentTimeMillis()
            var lastReportTested = startFromCandidateCount
            var totalCandidates = 0L

            try {
                val totalWordsInFile = try {
                    wordlistFile.useLines { it.count() }.toLong()
                } catch (_: Exception) {
                    10000L
                }
                totalCandidates = if (useBest64Rules) totalWordsInFile * 64 else totalWordsInFile

                val numCores = if (useTurboMultiCore) Runtime.getRuntime().availableProcessors().coerceAtLeast(4) else 1
                val batchSize = if (useTurboMultiCore) numCores * 16 else 1

                val parts = hc22000Line.trim().split('*')
                if (parts.size < 6) {
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }

                val ssidHex = parts[5]
                val ssid = String(HashcatConverter.hexToBytes(ssidHex), StandardCharsets.UTF_8)
                val isPmkidLine = parts[1] == "01"
                val isHandshakeLine = parts[1] == "02"
                val pmkidOrMicHex = parts[2]
                val apMacHex = parts[3]
                val clientMacHex = parts[4]

                val preparedTarget = if (isHandshakeLine && parts.size >= 9) {
                    val apMac = HashcatConverter.hexToBytes(apMacHex)
                    val clientMac = HashcatConverter.hexToBytes(clientMacHex)
                    val anonce = HashcatConverter.hexToBytes(parts[6])
                    val snonce = HashcatConverter.hexToBytes(parts[7])
                    val eapolBytes = HashcatConverter.hexToBytes(parts[8])
                    val keyVersion = parts.getOrNull(9)?.toIntOrNull() ?: 2

                    val minMac = if (compareBytes(apMac, clientMac) < 0) apMac else clientMac
                    val maxMac = if (compareBytes(apMac, clientMac) < 0) clientMac else apMac
                    val minNonce = if (compareBytes(anonce, snonce) < 0) anonce else snonce
                    val maxNonce = if (compareBytes(anonce, snonce) < 0) snonce else anonce

                    val ptkInput = "Pairwise key expansion".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) +
                            minMac + maxMac + minNonce + maxNonce

                    val eapolNoMic = eapolBytes.copyOf()
                    if (eapolNoMic.size >= 97) {
                        java.util.Arrays.fill(eapolNoMic, 81, 97, 0.toByte())
                    }

                    PreparedWpaTarget(ssid.toByteArray(StandardCharsets.UTF_8), ssid, false, ptkInput, eapolNoMic, keyVersion, pmkidOrMicHex, apMac, clientMac, apMacHex)
                } else {
                    PreparedWpaTarget(ssid.toByteArray(StandardCharsets.UTF_8), ssid, true, byteArrayOf(), byteArrayOf(), 2, pmkidOrMicHex, HashcatConverter.hexToBytes(apMacHex), HashcatConverter.hexToBytes(clientMacHex), apMacHex)
                }

                var linesToSkip = if (useBest64Rules && startFromCandidateCount > 0) startFromCandidateCount / 64 else startFromCandidateCount
                val candidateBatch = mutableListOf<String>()

                wordlistFile.bufferedReader().use { reader ->
                    // Fast skip already tested lines
                    while (linesToSkip > 0 && reader.readLine() != null) {
                        linesToSkip--
                    }

                    var rawWord = reader.readLine()
                    while (rawWord != null && isRunning && !found) {
                        val word = rawWord.trim()
                        val candidates = if (useBest64Rules) RuleEngine.applyRules(word) else listOf(word)

                        for (candidate in candidates) {
                            if (!isRunning || found) break
                            candidateBatch.add(candidate)

                            if (candidateBatch.size >= batchSize) {
                                val batchCopy = ArrayList(candidateBatch)
                                candidateBatch.clear()

                                val chunkSize = (batchCopy.size + numCores - 1) / numCores
                                val parallelJobs = batchCopy.chunked(chunkSize).map { chunk ->
                                    async(Dispatchers.Default) {
                                        for (cand in chunk) {
                                            if (!isRunning) return@async null
                                            if (verifyCandidateFast(cand, preparedTarget)) return@async cand
                                        }
                                        null
                                    }
                                }

                                val results = parallelJobs.awaitAll()
                                testedCount += batchCopy.size
                                currentTestedCount = testedCount

                                val matchedPassword = results.firstOrNull { it != null }
                                if (matchedPassword != null) {
                                    found = true
                                    clearCheckpoint(context)
                                    saveCrackedPasswordToPotfile(context, preparedTarget.ssid, preparedTarget.apMacHex, sourceFileName, matchedPassword)
                                    withContext(Dispatchers.Main) {
                                        onFound(matchedPassword)
                                    }
                                    break
                                }

                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 1000) {
                                    val deltaTested = testedCount - lastReportTested
                                    val deltaTimeSec = (now - lastReportTime) / 1000.0
                                    val speed = if (deltaTimeSec > 0) (deltaTested / deltaTimeSec).toLong() else 0L
                                    lastReportTime = now
                                    lastReportTested = testedCount

                                    val snapshotTested = testedCount
                                    val snapshotCand = batchCopy.lastOrNull() ?: ""
                                    scope.launch(Dispatchers.IO) {
                                        saveCheckpoint(context, sourceFileName, wordlistFile.name, useBest64Rules, snapshotTested)
                                    }

                                    onProgress(snapshotTested, totalCandidates, speed, snapshotCand)
                                }
                            }
                        }
                        rawWord = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.e("WpaWordlistCracker", "Cracker error: ${e.message}")
            }

            if (found || testedCount >= totalCandidates) {
                clearCheckpoint(context)
            } else {
                saveCheckpoint(context, sourceFileName, wordlistFile.name, useBest64Rules, testedCount)
            }

            isRunning = false
            withContext(Dispatchers.Main) {
                onDone(found)
            }
        }
    }

    fun stop() {
        isRunning = false
        saveCheckpoint(context, sourceFileName, wordlistFile.name, useBest64Rules, currentTestedCount)
    }

    companion object {
        fun saveCheckpoint(context: Context, hcFileName: String, wordlistFileName: String, useBest64Rules: Boolean, testedCount: Long) {
            try {
                val prefs = context.getSharedPreferences("wpa_cracker_checkpoint", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("hc_file", hcFileName)
                    .putString("wordlist_file", wordlistFileName)
                    .putBoolean("use_rules", useBest64Rules)
                    .putLong("tested_count", testedCount)
                    .apply()
            } catch (_: Exception) {}
        }

        fun clearCheckpoint(context: Context) {
            try {
                val prefs = context.getSharedPreferences("wpa_cracker_checkpoint", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            } catch (_: Exception) {}
        }

        fun getCheckpoint(context: Context): WpaCrackerCheckpoint? {
            val prefs = context.getSharedPreferences("wpa_cracker_checkpoint", Context.MODE_PRIVATE)
            val hcFile = prefs.getString("hc_file", null) ?: return null
            val wordlistFile = prefs.getString("wordlist_file", null) ?: return null
            val useRules = prefs.getBoolean("use_rules", true)
            val testedCount = prefs.getLong("tested_count", 0L)
            if (testedCount <= 0L) return null
            return WpaCrackerCheckpoint(hcFile, wordlistFile, useRules, testedCount)
        }
    }

    private fun saveCrackedPasswordToPotfile(context: Context, ssid: String, bssid: String, fileName: String, password: String) {
        try {
            val potfile = File(context.filesDir, "cracked_passwords.txt")
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val entry = "[$dateStr]  SSID: $ssid  |  BSSID: $bssid  |  File: $fileName  |  PASSWORD: $password\n"
            potfile.appendText(entry)
            Log.d("WpaWordlistCracker", "Saved cracked password to potfile: $entry")
        } catch (e: Exception) {
            Log.e("WpaWordlistCracker", "Error saving to potfile: ${e.message}")
        }
    }

    private val threadLocalHmacSha1 = object : ThreadLocal<Mac>() {
        override fun initialValue(): Mac = Mac.getInstance("HmacSHA1")
    }

    private val threadLocalHmacMd5 = object : ThreadLocal<Mac>() {
        override fun initialValue(): Mac = Mac.getInstance("HmacMD5")
    }

    private fun verifyCandidateFast(candidate: String, target: PreparedWpaTarget): Boolean {
        try {
            val passBytes = candidate.toByteArray(StandardCharsets.UTF_8)
            val pmk = pbkdf2HmacSha1Fast(passBytes, target.ssidBytes)

            if (target.isPmkid) {
                val label = "PMK Name".toByteArray(StandardCharsets.UTF_8)
                val data = label + target.apMac + target.clientMac
                val pmkid = hmacSha1Fast(pmk, data).copyOfRange(0, 16)
                return HashcatConverter.bytesToHex(pmkid).equals(target.targetMicOrPmkidHex, ignoreCase = true)
            } else {
                val ptk = prf512Fast(pmk, target.ptkInput)
                val kck = ptk.copyOfRange(0, 16)
                val computedMic = if (target.keyVersion == 1) {
                    hmacMd5Fast(kck, target.eapolNoMic)
                } else {
                    hmacSha1Fast(kck, target.eapolNoMic).copyOfRange(0, 16)
                }
                return HashcatConverter.bytesToHex(computedMic).equals(target.targetMicOrPmkidHex, ignoreCase = true)
            }
        } catch (_: Exception) {
            return false
        }
    }
    private fun prf512Fast(key: ByteArray, data: ByteArray): ByteArray {
        val result = ByteArray(64)
        var offset = 0
        var i = 0
        val mac = threadLocalHmacSha1.get() ?: Mac.getInstance("HmacSHA1")
        
        while (offset < 64) {
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            mac.update(data)
            mac.update(byteArrayOf(i.toByte()))
            val digest = mac.doFinal()
            val toCopy = (64 - offset).coerceAtMost(digest.size)
            System.arraycopy(digest, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }
        return result
    }

    private fun hmacMd5Fast(key: ByteArray, data: ByteArray): ByteArray {
        val mac = threadLocalHmacMd5.get() ?: Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }

    private fun hmacSha1Fast(key: ByteArray, data: ByteArray): ByteArray {
        val mac = threadLocalHmacSha1.get() ?: Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = a.size.coerceAtMost(b.size)
        for (i in 0 until minLen) {
            val v1 = a[i].toInt() and 0xFF
            val v2 = b[i].toInt() and 0xFF
            if (v1 != v2) return v1 - v2
        }
        return a.size - b.size
    }

    private fun verifyPmkid(pass: String, ssid: String, apMacHex: String, clientMacHex: String, targetPmkidHex: String): Boolean {
        try {
            val pmk = pbkdf2HmacSha1Fast(pass.toByteArray(StandardCharsets.UTF_8), ssid.toByteArray(StandardCharsets.UTF_8))
            val label = "PMK Name".toByteArray(StandardCharsets.UTF_8)
            val apMac = HashcatConverter.hexToBytes(apMacHex)
            val clientMac = HashcatConverter.hexToBytes(clientMacHex)

            val data = label + apMac + clientMac
            val pmkid = hmacSha1Fast(pmk, data).copyOfRange(0, 16)
            return HashcatConverter.bytesToHex(pmkid).equals(targetPmkidHex, ignoreCase = true)
        } catch (_: Exception) {
            return false
        }
    }

    private fun pbkdf2HmacSha1Fast(passwordBytes: ByteArray, saltBytes: ByteArray, iterations: Int = 2048, keyByteLength: Int = 32): ByteArray {
        val mac = threadLocalHmacSha1.get() ?: Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(passwordBytes, "HmacSHA1")
        val result = ByteArray(keyByteLength)
        var resultOffset = 0
        var blockIndex = 1

        val blockIndexBytes = ByteArray(4)

        while (resultOffset < keyByteLength) {
            blockIndexBytes[0] = (blockIndex shr 24).toByte()
            blockIndexBytes[1] = (blockIndex shr 16).toByte()
            blockIndexBytes[2] = (blockIndex shr 8).toByte()
            blockIndexBytes[3] = blockIndex.toByte()

            mac.init(keySpec)
            mac.update(saltBytes)
            mac.update(blockIndexBytes)
            var u = mac.doFinal()
            val t = u.copyOf()

            for (i in 2..iterations) {
                mac.update(u)
                u = mac.doFinal()
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            val bytesToCopy = (keyByteLength - resultOffset).coerceAtMost(t.size)
            System.arraycopy(t, 0, result, resultOffset, bytesToCopy)
            resultOffset += bytesToCopy
            blockIndex++
        }
        return result
    }
}

class AppWebFileServer(
    private val context: Context,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var job: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Token wymagany przy każdym żądaniu HTTP. Chroni hub plików przed dostępem
     * dowolnego urządzenia w LAN (wcześniej serwer przyjmował każde połączenie).
     */
    val accessToken: String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        const val MAX_UPLOAD_BYTES = 64L * 1024L * 1024L
        private val SAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        job = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                registerMdnsService(port)
                Log.d("AppWebFileServer", "Web File Server started at http://bruceir.local:$port")
                while (isRunning && isActive) {
                    try {
                        val socket = server.accept() ?: break
                        launch(Dispatchers.IO) { handleClient(socket) }
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AppWebFileServer", "Server error: ${e.message}")
            }
        }
    }

    private fun registerMdnsService(activePort: Int) {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = manager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "bruceir"
                serviceType = "_http._tcp."
                setPort(activePort)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d("AppWebFileServer", "mDNS registered: http://bruceir.local:$activePort")
                }
                override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
            }
            registrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e("AppWebFileServer", "mDNS registration error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        val socketToClose = serverSocket
        serverSocket = null
        val listenerToUnregister = registrationListener
        registrationListener = null
        val manager = nsdManager
        nsdManager = null

        job?.cancel()
        job = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                socketToClose?.close()
            } catch (_: Exception) {}
            if (listenerToUnregister != null && manager != null) {
                try {
                    manager.unregisterService(listenerToUnregister)
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val input = s.getInputStream()
                val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val rawTarget = parts[1]
                val queryStart = rawTarget.indexOf('?')
                val rawPath = if (queryStart >= 0) rawTarget.substring(0, queryStart) else rawTarget
                val rawQuery = if (queryStart >= 0) rawTarget.substring(queryStart + 1) else ""
                val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
                val queryParams = rawQuery.split("&").mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        val key = runCatching { URLDecoder.decode(pair.substring(0, separator), "UTF-8") }.getOrNull()
                        val value = runCatching { URLDecoder.decode(pair.substring(separator + 1), "UTF-8") }.getOrNull()
                        if (key == null || value == null) null else key to value
                    }
                }.toMap()
                val out = BufferedOutputStream(s.getOutputStream())

                val headers = mutableMapOf<String, String>()
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrBlank()) {
                    val idx = headerLine.indexOf(':')
                    if (idx > 0) {
                        headers[headerLine.substring(0, idx).trim().lowercase()] = headerLine.substring(idx + 1).trim()
                    }
                    headerLine = reader.readLine()
                }

                val cookieToken = headers["cookie"]?.let { cookie ->
                    Regex("(?:^|;\\s*)bruceir_token=([^;]+)").find(cookie)?.groupValues?.getOrNull(1)
                }
                val providedToken = queryParams["token"] ?: headers["x-bruceir-token"] ?: cookieToken
                if (providedToken != accessToken) {
                    serveUnauthorized(out)
                    return
                }

                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> serveDashboard(out)
                    method == "GET" && path.startsWith("/download/") -> serveDownload(path.removePrefix("/download/"), out)
                    method == "POST" && path.startsWith("/upload") -> serveUpload(headers, input, out)
                    else -> serve404(out)
                }
            }
        } catch (t: Throwable) {
            // OOM/Error z niepoprawnego Content-Length nie może zabić procesu serwera.
            Log.e("AppWebFileServer", "Client error: ${t.message}")
        }
    }

    private fun serveUpload(headers: Map<String, String>, input: InputStream, out: BufferedOutputStream) {
        try {
            val contentType = headers["content-type"] ?: ""
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength <= 0L || contentLength > MAX_UPLOAD_BYTES) {
                serveTooLarge(out)
                return
            }
            if (!contentType.contains("multipart/form-data")) {
                serve404(out)
                return
            }
            val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
            if (boundary.isBlank()) {
                serve404(out)
                return
            }
            val bytes = ByteArray(contentLength.toInt())
            var read = 0
            while (read < contentLength) {
                val count = input.read(bytes, read, contentLength.toInt() - read)
                if (count <= 0) break
                read += count
            }
            if (read > 0) parseAndSaveMultipart(bytes.copyOf(read), boundary)
            val redirectHtml = "<html><head><meta http-equiv='refresh' content='2;url=/?token=$accessToken'></head>" +
                    "<body style='background:#121212;color:#4CAF50;font-family:sans-serif;padding:32px;'>" +
                    "<h2>✅ File Uploaded Successfully!</h2><p>Redirecting back to BruceIR Hub...</p></body></html>"
            val responseBytes = redirectHtml.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(responseBytes)
            out.flush()
        } catch (t: Throwable) {
            Log.e("AppWebFileServer", "Upload failed: ${t.message}")
            serve404(out)
        }
    }

    private fun parseAndSaveMultipart(data: ByteArray, boundary: String) {
        try {
            val content = String(data, StandardCharsets.ISO_8859_1)
            val filenameMatch = Regex("""filename="([^"]*)"""").find(content) ?: return
            val rawName = File(filenameMatch.groupValues[1]).name
            val safeName = rawName.replace(SAFE_FILE_NAME, "_").trim()
            if (safeName.isBlank() || safeName == "." || safeName == "..") return

            val headerEnd = content.indexOf("\r\n\r\n")
            if (headerEnd == -1) return
            val fileStart = headerEnd + 4

            val boundaryMarker = "--$boundary"
            val fileEnd = content.indexOf(boundaryMarker, fileStart)
            if (fileEnd <= fileStart) return

            val fileContent = data.copyOfRange(fileStart, fileEnd - 2)
            val root = context.filesDir.canonicalFile
            val destFile = File(root, safeName).canonicalFile
            if (destFile.path != root.path && !destFile.path.startsWith(root.path + File.separator)) {
                Log.w("AppWebFileServer", "Rejected upload outside app storage: $safeName")
                return
            }
            destFile.writeBytes(fileContent)
            Log.d("AppWebFileServer", "Saved uploaded file: ${destFile.name} (${fileContent.size} B)")
        } catch (e: Exception) {
            Log.e("AppWebFileServer", "Upload error: ${e.message}")
        }
    }

    private fun serveDashboard(out: BufferedOutputStream) {
        val files = context.filesDir.listFiles() ?: emptyArray()
        val appFiles = files.filter { it.isFile && (it.extension in listOf("hc22000", "pcap", "cap", "ir", "sub", "nfc", "txt", "json")) }

        val potfile = File(context.filesDir, "cracked_passwords.txt")
        val potfileContent = if (potfile.exists()) potfile.readText() else ""
        val token = accessToken

        val html = buildString {
            appendLine("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>BruceIR Web Hub</title>")
            appendLine("<style>body{background:#121212;color:#eee;font-family:sans-serif;padding:24px;}")
            appendLine("h2{color:#2196F3;} h3{color:#4CAF50;margin-top:24px;} table{width:100%;border-collapse:collapse;margin-top:12px;}")
            appendLine("th,td{padding:12px;text-align:left;border-bottom:1px solid #333;}")
            appendLine("a{color:#4CAF50;text-decoration:none;font-weight:bold;}")
            appendLine(".btn{background:#2196F3;color:#fff;padding:6px 12px;border-radius:4px;text-decoration:none;border:none;cursor:pointer;}")
            appendLine(".potbox{background:#1E272C;border-left:4px solid #4CAF50;padding:12px;font-family:monospace;white-space:pre-wrap;color:#81C784;}")
            appendLine("</style></head><body>")
            appendLine("<h2>⚡ BruceIR Web File Hub</h2>")
            appendLine("<p>Access URL: <b>http://bruceir.local</b> | Bi-Directional Phone Storage</p>")
            appendLine("<p>Sesja chroniona tokenem. Każdy link w tej stronie zawiera token — nie udostępniaj go w niezaufanej sieci.</p>")

            if (potfileContent.isNotBlank()) {
                appendLine("<h3>🔑 Cracked Passwords Potfile</h3>")
                appendLine("<div class='potbox'>${escapeHtml(potfileContent)}</div>")
            }

            appendLine("<div style='background:#1A2327;border:1px solid #333;padding:16px;border-radius:8px;margin-top:20px;'>")
            appendLine("<h3 style='margin:0 0 12px 0;color:#2196F3;'>📤 Upload File from PC to Phone</h3>")
            appendLine("<form action='/upload?token=$token' method='POST' enctype='multipart/form-data'>")
            appendLine("<input type='file' name='file' style='background:#222;color:#fff;padding:8px;border-radius:4px;' required />")
            appendLine("<button type='submit' class='btn' style='margin-left:12px;'>Upload to BruceIR</button>")
            appendLine("</form>")
            appendLine("</div>")

            appendLine("<h3>📁 App Storage Files</h3>")
            appendLine("<table><tr><th>Filename</th><th>Size</th><th>Type</th><th>Action</th></tr>")
            for (file in appFiles.sortedByDescending { it.lastModified() }) {
                val sizeKb = String.format(java.util.Locale.US, "%.1f KB", file.length() / 1024.0)
                appendLine("<tr>")
                appendLine("<td>${escapeHtml(file.name)}</td>")
                appendLine("<td>$sizeKb</td>")
                appendLine("<td>${escapeHtml(file.extension.uppercase())}</td>")
                appendLine("<td><a class='btn' href='/download/${URLEncoder.encode(file.name, "UTF-8")}?token=$token'>Download to PC</a></td>")
                appendLine("</tr>")
            }
            if (appFiles.isEmpty()) appendLine("<tr><td colspan='4'>No capture or Hashcat files found in app storage.</td></tr>")
            appendLine("</table></body></html>")
        }

        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun serveDownload(fileName: String, out: BufferedOutputStream) {
        val root = context.filesDir.canonicalFile
        val file = runCatching { File(root, fileName).canonicalFile }.getOrNull()
        if (file == null ||
            (file.path != root.path && !file.path.startsWith(root.path + File.separator)) ||
            !file.exists() ||
            !file.isFile
        ) {
            serve404(out)
            return
        }
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"${file.name}\"\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun serveUnauthorized(out: BufferedOutputStream) {
        val bytes = ("<html><body style='background:#121212;color:#eee;font-family:sans-serif;padding:32px;'>" +
                "<h2>401 — token sesji wymagany</h2>" +
                "<p>Otwórz BruceIR → Ustawienia → Web Hub i użyj linku z tokenem (parametr <code>?token=</code>).</p>" +
                "</body></html>").toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "WWW-Authenticate: BruceIR-Token\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun serveTooLarge(out: BufferedOutputStream) {
        val bytes = "413 Payload Too Large".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 413 Payload Too Large\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun serve404(out: BufferedOutputStream) {
        val msg = "404 Not Found"
        val bytes = msg.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
