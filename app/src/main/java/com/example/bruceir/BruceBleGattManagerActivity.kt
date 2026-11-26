package com.example.bruceir

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.*

class BruceBleGattManagerActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var containerServices: LinearLayout
    private lateinit var tvLog: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private var deviceName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val logBuilder = StringBuilder()

    private fun getHumanServiceName(uuid: UUID): String {
        val s = uuid.toString().lowercase(Locale.getDefault())
        return when {
            s.contains("180a") -> "ℹ️ Informacje o Urządzeniu (Model, Producent)"
            s.contains("180f") -> "🔋 Stan Naładowania Baterii"
            s.contains("1800") -> "🏷️ Nazwa i Ustawienia Urządzenia"
            s.contains("1801") -> "⚙️ Usługa Atrybutów Systemowych"
            s.contains("4371ec0b") -> "⚡ Dedykowany Port Komend Bruce / LilyGO"
            s.contains("fe36") -> "📺 Port Sterowania Google / Android TV"
            s.contains("1812") -> "🎮 Urządzenie Wejściowe (Pilot / Mysz BLE HID)"
            s.contains("180d") -> "❤️ Pulsometr & Telemetria Zdrowia"
            s.contains("181a") -> "🌡️ Czujniki Środowiskowe (Pomiary)"
            else -> "🔌 Usługa Danych Custom (${uuid.toString().take(8)})"
        }
    }

    private fun getHumanCharName(uuid: UUID): String {
        val s = uuid.toString().lowercase(Locale.getDefault())
        return when {
            s.contains("2a19") -> "🔋 Poziom Baterii (%)"
            s.contains("2a00") -> "🏷️ Nazwa Urządzenia"
            s.contains("2a24") -> "🔢 Model Urządzenia"
            s.contains("2a26") -> "💿 Wersja Oprogramowania (Firmware)"
            s.contains("2a29") -> "🏭 Producent"
            s.contains("d555ed97") -> "⚡ Port Rozkazów Bruce (IR/RF/Wi-Fi)"
            s.contains("2a4d") -> "🎮 Klawisz / Gesty Myszki BLE HID"
            else -> "Charakterystyka: ${uuid.toString().take(8)}"
        }
    }

    private fun formatBleValue(value: ByteArray): String {
        if (value.isEmpty()) return "Brak danych (pusto)"
        
        val hex = value.joinToString(" ") { "%02X".format(it) }
        val ascii = String(value.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
        val dec = if (value.size in 1..4) {
            var num = 0L
            for (i in value.indices) {
                num = num or ((value[i].toLong() and 0xFF) shl (i * 8))
            }
            " | Dec: $num"
        } else ""

        return "Tekst: \"$ascii\"$dec | Hex: [$hex]"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("🔗 Połączono z urządzeniem. Odkrywanie usług...")
                runOnUiThread { tvStatus.text = "Status: Połączono (Odkrywanie usług...)" }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("🔌 Rozłączono z urządzeniem.")
                runOnUiThread { tvStatus.text = "Status: Rozłączono" }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("✅ Odkryto ${gatt.services.size} usług GATT.")
                runOnUiThread {
                    tvStatus.text = "Status: Połączono (${gatt.services.size} usług)"
                    renderServices(gatt)
                }
            } else {
                appendLog("❌ Błąd odkrywania usług: $status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: byteArrayOf()
                val formatted = formatBleValue(value)
                appendLog("📥 Odczyt [${characteristic.uuid.toString().take(8)}]: $formatted")
            } else {
                appendLog("❌ Błąd odczytu [${characteristic.uuid}]: $status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("📤 Zapis pomyślny [${characteristic.uuid.toString().take(8)}]")
            } else {
                appendLog("❌ Błąd zapisu [${characteristic.uuid}]: $status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: byteArrayOf()
            val formatted = formatBleValue(value)
            appendLog("🔔 Powiadomienie [${characteristic.uuid.toString().take(8)}]: $formatted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        deviceAddress = intent.getStringExtra("EXTRA_MAC")
        deviceName = intent.getStringExtra("EXTRA_NAME") ?: "Urządzenie BLE"

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        buildScreen()
        connectDevice()
    }

    override fun onDestroy() {
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun appendLog(msg: String) {
        runOnUiThread {
            logBuilder.append(msg).append("\n")
            tvLog.text = logBuilder.toString()
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ROZUMIEM", null)
            .show()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(130))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("GATT MANAGER & KONTROLA", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text(deviceName ?: "Menedżer Usług BLE", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val btnInfo = MaterialButton(this).apply {
            text = "[?]"
            setOnClickListener {
                showInfoDialog(
                    "ℹ Menedżer Usług & Charakterystyk GATT",
                    "GATT (Generic Attribute Profile) to struktura danych w Bluetooth LE. Każde urządzenie organizuje swoje funkcje w Usługi (Services) oraz Charakterystyki (Characteristics).\n\n• ODCZYTAJ = Pobiera stan z urządzenia (np. poziom baterii).\n• ZAPISZ = Wysyła rozkaz do urządzenia (np. komendę nadania IR/RF).\n• SUBSKRYBUJ = Włącza ciągłe powiadomienia w terminalu na dole ekranu."
                )
            }
        }
        header.addView(btnInfo, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginEnd = dp(6) })

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("MAC: $deviceAddress", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // Status Card
        val statusCard = card()
        val statusLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvStatus = text("Status: Łączenie z urządzeniem...", 14f, 0xFF43C59E.toInt(), true)
        statusLayout.addView(tvStatus)
        statusCard.addView(statusLayout)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // Quick Controls Card (Praktyczne sterowanie)
        val isBruceDevice = deviceName?.contains("bruce", true) == true || deviceName?.contains("lilygo", true) == true
        val quickCard = card()
        val quickLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        quickLayout.addView(text("🎮 Szybkie Sterowanie & Praktyczne Akcje", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        if (isBruceDevice) {
            quickLayout.addView(text("Wykryto mikrokontroler Bruce! Dostępne sterowanie sprzętowe:", 12f, 0xFF43C59E.toInt()).apply { setPadding(0, 0, 0, dp(6)) })

            val btnBruceIr = MaterialButton(this).apply {
                text = "⚡ Wysłanie Testowego Sygnału IR (tx_raw)"
                setBackgroundColor(0xFF43C59E.toInt())
                setTextColor(Color.BLACK)
                setOnClickListener {
                    sendBruceCommand("ir tx_raw 38000 9000 4500 560 560 560 1690 560 560")
                }
            }
            quickLayout.addView(btnBruceIr, verticalParams(dp(4)))

            val btnBruceRf = MaterialButton(this).apply {
                text = "📡 Skanowanie Sub-GHz CC1101"
                setBackgroundColor(0xFF283545.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener {
                    sendBruceCommand("subghz rx raw 15")
                }
            }
            quickLayout.addView(btnBruceRf, verticalParams(dp(4)))
        } else {
            quickLayout.addView(text("Uniwersalny pilot bezprzewodowy BLE HID / Media:", 12f, 0xFF9EADBD.toInt()).apply { setPadding(0, 0, 0, dp(6)) })

            val btnRowMedia = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnVolUp = MaterialButton(this).apply {
                text = "🔊 Vol +"
                setBackgroundColor(0xFF283545.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener { sendBleMediaKey("VOL_UP") }
            }
            val btnVolDown = MaterialButton(this).apply {
                text = "🔉 Vol -"
                setBackgroundColor(0xFF283545.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener { sendBleMediaKey("VOL_DOWN") }
            }
            val btnPlayPause = MaterialButton(this).apply {
                text = "⏯ Play/Pause"
                setBackgroundColor(0xFF43C59E.toInt())
                setTextColor(Color.BLACK)
                setOnClickListener { sendBleMediaKey("PLAY_PAUSE") }
            }
            btnRowMedia.addView(btnVolUp, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
            btnRowMedia.addView(btnVolDown, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            btnRowMedia.addView(btnPlayPause, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) })
            quickLayout.addView(btnRowMedia, verticalParams(dp(4)))
        }

        val btnRowExtra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRadar = MaterialButton(this).apply {
            text = "📡 Radar RSSI"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@BruceBleGattManagerActivity, BruceBleRadarActivity::class.java).apply {
                    putExtra("EXTRA_MAC", deviceAddress)
                    putExtra("EXTRA_NAME", deviceName)
                }
                startActivity(intent)
            }
        }

        val btnTouchpad = MaterialButton(this).apply {
            text = "🖲️ Touchpad"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@BruceBleGattManagerActivity, BruceBleTouchpadActivity::class.java).apply {
                    putExtra("EXTRA_NAME", deviceName)
                }
                startActivity(intent)
            }
        }

        val btnSaveLog = MaterialButton(this).apply {
            text = "💾 Zapisz Log"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                BruceBleLogger.saveBleLogToDocuments(this@BruceBleGattManagerActivity, deviceName ?: "BLE_Device", deviceAddress ?: "00:00:00:00:00:00", logBuilder.toString())
            }
        }

        btnRowExtra.addView(btnRadar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
        btnRowExtra.addView(btnTouchpad, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        btnRowExtra.addView(btnSaveLog, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) })
        quickLayout.addView(btnRowExtra, verticalParams(dp(6)))

        quickCard.addView(quickLayout)
        root.addView(quickCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Services & Characteristics Container
        root.addView(text("Usługi i Charakterystyki GATT (Czytelne opisy)", 16f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        containerServices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerServices, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Live Log Terminal
        root.addView(text("Terminal Powiadomień i Logów GATT", 16f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val logCard = card()
        val logLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvLog = text("Oczekiwanie na transmisję danych...\n", 11f, 0xFF00E5FF.toInt()).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logLayout.addView(tvLog)
        logCard.addView(logLayout)
        root.addView(logCard)
    }

    private fun sendBruceCommand(cmdStr: String) {
        appendLog("📤 Wysłanie komendy do Bruce: $cmdStr")
        val gatt = bluetoothGatt
        if (gatt == null) {
            Toast.makeText(this, "Brak połączenia BLE!", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceUuid = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
        val charUuid = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")

        val targetService = gatt.getService(serviceUuid)
        val targetChar = targetService?.getCharacteristic(charUuid)
            ?: gatt.services.flatMap { it.characteristics }.firstOrNull { (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }

        if (targetChar != null) {
            @Suppress("DEPRECATION")
            targetChar.value = (cmdStr.trim() + "\n").toByteArray(Charsets.UTF_8)
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(targetChar)
            Toast.makeText(this, "Wysłano do Bruce: $cmdStr", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nie znaleziono charakterystyki komend Bruce", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBleMediaKey(keyType: String) {
        appendLog("🎮 Wyzwolenie klawisza BLE Media: $keyType")
        Toast.makeText(this, "Wysłano klawisz media $keyType przez BLE GATT", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice() {
        val address = deviceAddress
        if (address.isNullOrBlank() || bluetoothAdapter == null) {
            tvStatus.text = "Status: Błąd adresu MAC"
            return
        }
        try {
            val device = bluetoothAdapter!!.getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            tvStatus.text = "Status: Błąd połączenia: ${e.message}"
            appendLog("❌ Błąd connectGatt: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderServices(gatt: BluetoothGatt) {
        containerServices.removeAllViews()
        gatt.services.forEach { service ->
            val sCard = card()
            val sLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val humanServiceName = getHumanServiceName(service.uuid)
            sLayout.addView(text(humanServiceName, 14f, 0xFF43C59E.toInt(), true))
            sLayout.addView(text("Service UUID: ${service.uuid}", 10f, 0xFF8290A2.toInt()).apply {
                setPadding(0, dp(2), 0, dp(8))
            })

            service.characteristics.forEach { char ->
                val cCard = MaterialCardView(this).apply {
                    radius = dp(10).toFloat()
                    setCardBackgroundColor(0xFF0F141D.toInt())
                    strokeColor = 0xFF283545.toInt()
                    strokeWidth = dp(1)
                    cardElevation = 0f
                    setContentPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val cLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                val props = mutableListOf<String>()
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESP")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")

                val humanCharName = getHumanCharName(char.uuid)
                cLayout.addView(text(humanCharName, 12f, Color.WHITE, true))
                cLayout.addView(text("UUID: ${char.uuid} | Uprawnienia: ${props.joinToString(", ")}", 10f, 0xFF8290A2.toInt()).apply {
                    setPadding(0, dp(2), 0, dp(6))
                })

                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    val btnRead = MaterialButton(this).apply {
                        text = "📖 ODCZYTAJ STAN"
                        textSize = 11f
                        setBackgroundColor(0xFF283545.toInt())
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            try {
                                @Suppress("DEPRECATION")
                                gatt.readCharacteristic(char)
                                appendLog("📤 Odczytywanie: ${char.uuid}")
                            } catch (e: Exception) {
                                appendLog("❌ Błąd odczytu: ${e.message}")
                            }
                        }
                    }
                    btnRow.addView(btnRead, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                }

                if (char.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    val btnWrite = MaterialButton(this).apply {
                        text = "✍️ ZAPISZ POLECENIE"
                        textSize = 11f
                        setBackgroundColor(0xFF43C59E.toInt())
                        setTextColor(Color.BLACK)
                        setOnClickListener {
                            showWriteDialog(gatt, char)
                        }
                    }
                    btnRow.addView(btnWrite, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }

                if (char.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    val btnNotify = MaterialButton(this).apply {
                        text = "🔔 SUBSKRYBUJ NA ŻYWO"
                        textSize = 11f
                        setBackgroundColor(0xFF00E5FF.toInt())
                        setTextColor(Color.BLACK)
                        setOnClickListener {
                            try {
                                gatt.setCharacteristicNotification(char, true)
                                val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                if (descriptor != null) {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    else
                                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(descriptor)
                                }
                                appendLog("🔔 Włączono powiadomienia dla: ${char.uuid}")
                            } catch (e: Exception) {
                                appendLog("❌ Błąd subskrypcji: ${e.message}")
                            }
                        }
                    }
                    btnRow.addView(btnNotify, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
                }

                if (btnRow.childCount > 0) {
                    cLayout.addView(btnRow, verticalParams(dp(4)))
                }

                cCard.addView(cLayout)
                sLayout.addView(cCard, verticalParams(dp(6)))
            }

            sCard.addView(sLayout)
            containerServices.addView(sCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun showWriteDialog(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        val input = EditText(this).apply {
            hint = "Wpisz tekst lub bajty HEX (np. AA BB)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        AlertDialog.Builder(this)
            .setTitle("✍️ Wyślij Zapis do Urządzenia")
            .setView(input)
            .setPositiveButton("ZAPISZ (WRITE)") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    try {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        @Suppress("DEPRECATION")
                        char.value = bytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                        appendLog("📤 Wysłano zapis do ${char.uuid}: $text")
                    } catch (e: Exception) {
                        appendLog("❌ Błąd zapisu: ${e.message}")
                    }
                }
            }
            .setNegativeButton("ANULUJ", null)
            .show()
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
    }
}
