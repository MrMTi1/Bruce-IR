package com.example.bruceir

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class SystemRecoveryGuidesActivity : AppCompatActivity() {

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
        titleStack.addView(text("SYSTEM RECOVERY & PASSWORD BYPASS", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Linux Single User & Windows Utilman", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Przewodniki administratora i gotowe skrypty Bad USB / Bad BLE do odzyskiwania dostępu.", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // 1. LINUX SINGLE USER MODE CARD
        val linuxCard = card()
        val linuxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linuxLayout.addView(text("🐧 Linux Single User Mode (Reset Hasła Root)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        linuxLayout.addView(text(
            "1. Zrestartuj komputer z Linuxem i w menu GRUB naciśnij klawisz 'e' (edycja wpisu).\n" +
                    "2. Znajdź linijkę zaczynającą się od 'linux' lub 'linuxefi'.\n" +
                    "3. Przejdź na sam koniec tej linii i dopisz: init=/bin/bash (lub rw init=/bin/bash).\n" +
                    "4. Naciśnij Ctrl+X lub F10, aby uruchomić system w trybie powłoki root.\n" +
                    "5. Wpisz komendy:\n" +
                    "   mount -o remount,rw /\n" +
                    "   passwd root\n" +
                    "   reboot",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        val btnSendLinux = MaterialButton(this).apply {
            text = "🚀 Wyślij komendy powłoki przez Bad BLE (LilyGO)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING mount -o remount,rw /")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano komendę mount do LilyGO", Toast.LENGTH_SHORT).show()
            }
        }
        linuxLayout.addView(btnSendLinux, verticalParams(dp(4)))
        linuxCard.addView(linuxLayout)
        root.addView(linuxCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. WINDOWS UTILMAN HACK CARD
        val winCard = card()
        val winLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        winLayout.addView(text("🪟 Windows Utilman / Sticky Keys Hack (Ominięcie Hasła)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        winLayout.addView(text(
            "1. Uruchom komputer z Nośnika Instalacyjnego Windows (Instalator > Napraw komputer > Wiersz polecenia) lub z Live USB.\n" +
                    "2. Przejdź do partycji systemowej (zazwyczaj C:) i wpisz:\n" +
                    "   cd /d C:\\Windows\\System32\n" +
                    "   ren utilman.exe utilman.bak\n" +
                    "   copy cmd.exe utilman.exe\n" +
                    "3. Uruchom komputer ponownie do normalnego ekranu logowania.\n" +
                    "4. Na ekranie logowania kliknij ikonę Ułatwień Dostępu (Utilman w prawym dolnym rogu) lub naciśnij Win+U.\n" +
                    "5. Otworzy się konsola administratora z uprawnieniami SYSTEM! Wpisz:\n" +
                    "   net user Administrator NoweHaslo123!\n" +
                    "   lub net user TwojaNazwa Uzytkownika NoweHaslo123!",
            13f, Color.WHITE
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        val btnSendWin = MaterialButton(this).apply {
            text = "🚀 Wyślij komendę net user przez Bad BLE (LilyGO)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "STRING net user Administrator Hack123!")
                BruceBleIrLink.sendCustomCommand(this@SystemRecoveryGuidesActivity, "ENTER")
                Toast.makeText(this@SystemRecoveryGuidesActivity, "Wysłano komendę net user do LilyGO", Toast.LENGTH_SHORT).show()
            }
        }
        winLayout.addView(btnSendWin, verticalParams(dp(4)))
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
