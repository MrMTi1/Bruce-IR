package com.example.bruceir

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class SystemRecoveryGuidesActivity : AppCompatActivity() {

    private lateinit var tvSelectedIso: TextView
    private var selectedIsoUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedIsoUri = uri
            val path = uri.path ?: "tinycore.iso"
            tvSelectedIso.text = "Wybrany obraz / ISO: $path"
            Toast.makeText(this, "Wybrano system do strumieniowania!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(100))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("SYSTEM RECOVERY & BAD USB BOOT", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("TinyCore ISO & Single User Mode", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Wbudowany system TinyCore w APK, sieć ISO oraz automatyka Bad USB (po kablu USB).", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // 0. TINYCORE ISO & NETWORK BOOT CARD
        val netBootCard = card()
        val netBootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        netBootLayout.addView(text("🐧 Wbudowany TinyCore Linux & Network Boot", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        netBootLayout.addView(text(
            "W aplikacji wbudowano domyślny obraz ratunkowy TinyCore Linux (ok. 16 MB).\n" +
                    "Możesz go użyć w trybie sieciowym (HTTP Boot) lub wskazać własny plik .iso z pamięci telefonu.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        tvSelectedIso = text("Domyślny system: Wbudowany TinyCore Linux (16MB)", 12f, 0xFF43C59E.toInt())
        netBootLayout.addView(tvSelectedIso, verticalParams(dp(4)))

        val btnPickIso = MaterialButton(this).apply {
            text = "📁 Wybierz inny plik ISO / Obraz"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { filePickerLauncher.launch("*/*") }
        }
        netBootLayout.addView(btnPickIso, verticalParams(dp(6)))

        netBootCard.addView(netBootLayout)
        root.addView(netBootCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 1. LINUX SINGLE USER MODE & NET USERS CARD (BAD USB)
        val linuxCard = card()
        val linuxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linuxLayout.addView(text("🐧 Linux Single User Mode & Net Users (Bad USB)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        linuxLayout.addView(text(
            "Ważne: Komendy są wysyłane przez kabel USB (Bad USB / HID), ponieważ komputer w fazie rozruchu nie obsługuje Bluetooth.\n" +
                    "1. Zrestartuj komputer z Linuxem i w menu GRUB naciśnij 'e'.\n" +
                    "2. Dopisz na końcu linii 'linux': init=/bin/bash i uruchom (Ctrl+X).",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        val btnDumpUsers = MaterialButton(this).apply {
            text = "📋 Bad USB: Zrzut Użytkowników (cat /etc/passwd)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "bu string \"cat /etc/passwd\"\nbu enter")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano skrypt Bad USB do LilyGO", Toast.LENGTH_SHORT).show()
            }
        }
        linuxLayout.addView(btnDumpUsers, verticalParams(dp(4)))

        val btnSendLinux = MaterialButton(this).apply {
            text = "🚀 Bad USB: Wejdź w Single User Mode (mount + passwd)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "bu string \"mount -o remount,rw / && passwd root\"\nbu enter")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano skrypt Single User przez Bad USB", Toast.LENGTH_SHORT).show()
            }
        }
        linuxLayout.addView(btnSendLinux, verticalParams(dp(6)))
        linuxCard.addView(linuxLayout)
        root.addView(linuxCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. WINDOWS UTILMAN HACK CARD (BAD USB)
        val winCard = card()
        val winLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        winLayout.addView(text("🪟 Windows Utilman / Net Users (Bad USB)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        winLayout.addView(text(
            "Instrukcja krok po kroku:\n" +
                    "1. Podmień utilman.exe na cmd.exe z poziomu nośnika ratunkowego.\n" +
                    "2. Na ekranie logowania Windows uruchom skrypt Bad USB.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        val btnDumpWinUsers = MaterialButton(this).apply {
            text = "📋 Bad USB: Lista Użytkowników Windows (net user)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "bu string \"net user\"\nbu enter")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano net user przez Bad USB", Toast.LENGTH_SHORT).show()
            }
        }
        winLayout.addView(btnDumpWinUsers, verticalParams(dp(4)))

        val btnSendWin = MaterialButton(this).apply {
            text = "🚀 Bad USB: Zmiana Hasła Administratora Windows"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "bu string \"net user Administrator Hack123!\"\nbu enter")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano skrypt zmiany hasła admina przez Bad USB", Toast.LENGTH_SHORT).show()
            }
        }
        winLayout.addView(btnSendWin, verticalParams(dp(6)))
        winCard.addView(winLayout)
        root.addView(winCard)
    }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
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
}
