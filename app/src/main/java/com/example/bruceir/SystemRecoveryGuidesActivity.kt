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
            val path = uri.path ?: "wybrany_obraz.iso"
            tvSelectedIso.text = "Wybrany obraz / ISO: $path"
            Toast.makeText(this, "Wybrano plik obrazu do strumieniowania!", Toast.LENGTH_SHORT).show()
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
        titleStack.addView(text("SYSTEM RECOVERY & NETWORK BOOT", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Instrukcja Krok po Kroku & Narzędzia", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Kompleksowe przewodniki, menedżer plików ISO i automatyka Bad BLE / Net Users.", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // 0. NETWORK BOOT & ISO STREAMING CARD (STEP BY STEP)
        val netBootCard = card()
        val netBootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        netBootLayout.addView(text("🌐 Network Boot & ISO Streaming (Krok po Kroku)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        netBootLayout.addView(text(
            "Krok 1: Wgraj mały system ratunkowy (np. Tiny Core Linux .iso) do pamięci telefonu.\n" +
                    "Krok 2: Kliknij poniższy przycisk 'Wybierz plik ISO', aby wskazać obraz w telefonie.\n" +
                    "Krok 3: Uruchom wbudowany serwer HTTP w telefonie (Web File Hub).\n" +
                    "Krok 4: Wepnij LilyGO z GRUB-em do komputera. LilyGO pobierze obraz przez USB/Wi-Fi z telefonu i uruchomi go w RAM komputera.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        tvSelectedIso = text("Wybrany obraz / ISO: Brak (Wskaź plik)", 12f, 0xFF9EADBD.toInt())
        netBootLayout.addView(tvSelectedIso, verticalParams(dp(4)))

        val btnPickIso = MaterialButton(this).apply {
            text = "📁 Wybierz plik ISO / Obraz z pamięci telefonu"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { filePickerLauncher.launch("*/*") }
        }
        netBootLayout.addView(btnPickIso, verticalParams(dp(6)))

        netBootCard.addView(netBootLayout)
        root.addView(netBootCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 1. LINUX SINGLE USER MODE & NET USERS CARD
        val linuxCard = card()
        val linuxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linuxLayout.addView(text("🐧 Linux Single User Mode & Net Users Dump", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        linuxLayout.addView(text(
            "Instrukcja krok po kroku:\n" +
                    "1. Zrestartuj komputer z Linuxem i w menu GRUB naciśnij 'e'.\n" +
                    "2. Dopisz na końcu linii 'linux': init=/bin/bash\n" +
                    "3. Uruchom system (Ctrl+X) i wykonaj poniższe skrypty.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        val btnDumpUsers = MaterialButton(this).apply {
            text = "📋 Wyślij skrypt: Zrzut Użytkowników (cat /etc/passwd)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING cat /etc/passwd")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano zrzut /etc/passwd przez Bad BLE", Toast.LENGTH_SHORT).show()
            }
        }
        linuxLayout.addView(btnDumpUsers, verticalParams(dp(4)))

        val btnSendLinux = MaterialButton(this).apply {
            text = "🚀 Wejdź w Single User Mode (mount + passwd root)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING mount -o remount,rw / && passwd root")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano skrypt Single User do LilyGO", Toast.LENGTH_SHORT).show()
            }
        }
        linuxLayout.addView(btnSendLinux, verticalParams(dp(6)))
        linuxCard.addView(linuxLayout)
        root.addView(linuxCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. WINDOWS UTILMAN HACK CARD
        val winCard = card()
        val winLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        winLayout.addView(text("🪟 Windows Utilman / Net Users Dump", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })
        winLayout.addView(text(
            "Instrukcja krok po kroku:\n" +
                    "1. Uruchom konsolę z nośnika instalacyjnego Windows.\n" +
                    "2. Podmień utilman.exe na cmd.exe na partycji systemowej.\n" +
                    "3. Na ekranie logowania otwórz konsolę i użyj poniższych skryptów.",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(8)) })

        val btnDumpWinUsers = MaterialButton(this).apply {
            text = "📋 Wyślij skrypt: Lista Użytkowników Windows (net user)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING net user")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano net user przez Bad BLE", Toast.LENGTH_SHORT).show()
            }
        }
        winLayout.addView(btnDumpWinUsers, verticalParams(dp(4)))

        val btnSendWin = MaterialButton(this).apply {
            text = "🚀 Wyślij skrypt: Zmiana Hasła Administratora Windows"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING net user Administrator Hack123!")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano skrypt zmiany hasła admina przez Bad BLE", Toast.LENGTH_SHORT).show()
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
