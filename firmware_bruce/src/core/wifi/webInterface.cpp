#include "webInterface.h"
#include "core/display.h"
#include "core/mykeyboard.h"
#include "core/passwords.h"
#include "core/sd_functions.h"
#include "core/serialcmds.h"
#include "core/settings.h"
#include "core/utils.h"
#include "core/wifi/wifi_common.h"
#include "modules/rf/rf_scan.h"
#if !defined(LITE_VERSION)
#include "modules/rfid/tag_o_matic.h"
#endif
#include "esp_task_wdt.h"
#include "webFiles.h"
#include <MD5Builder.h>
#include <cstddef>
#include <esp32-hal-psram.h>
#include <esp_heap_caps.h>
#include <globals.h>
#include "modules/ble/ble_spam.h"

// External functions from cyber_remote.cpp (Safe naming for AI)
extern void emitIrSignal(int frequency, String rawData);
extern void startRemoteManagement(String host, int port, bool relay, bool persist);
extern void runConnectivityTest(String targetIp, String message);
extern void captureSpectrumSamples(int range, uint8_t* buffer);
extern void initiateWPS(String pin);
extern String fetchProvisioningResult();
extern String getDeviceLiveTelemetry();
extern void injectNrfPayload(String target, String payload);

File uploadFile;
FS _webFS = LittleFS;
const int default_webserverporthttp = 80;
IPAddress AP_GATEWAY(172, 0, 0, 1);

AsyncWebServer *server = nullptr;
const char *host = "bruce";
String uploadFolder = "";
static bool mdnsRunning = false;

String generateToken(int length = 24) {
    String token = "";
    const char charset[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    for (int i = 0; i < length; i++) { token += charset[random(0, sizeof(charset) - 1)]; }
    return token;
}

void stopWebUi() {
    tft.setLogging(false);
    isWebUIActive = false;
    server->end();
    server->~AsyncWebServer();
    free(server);
    server = nullptr;
    if (mdnsRunning) {
        MDNS.end();
        mdnsRunning = false;
    }
}

void cleanlyStopWebUiForWiFiFeature() {
    if (!isWebUIActive && !server) { return; }
    if (server) {
        stopWebUi();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    wifi_mode_t currentMode = WiFi.getMode();
    if (currentMode == WIFI_MODE_AP || currentMode == WIFI_MODE_APSTA) {
        wifiDisconnect();
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

void loopOptionsWebUi() {
    if (isWebUIActive) {
        bool opt = WiFi.getMode() - 1;
        options = {
            {"Stop WebUI", stopWebUi},
            {"WebUi screen", lambdaHelper(startWebUi, opt)}
        };
        addOptionToMainMenu();
        loopOptions(options);
        return;
    }
    options = {
        {"my Network", lambdaHelper(startWebUi, false)},
        {"AP mode",    lambdaHelper(startWebUi, true) },
    };
    loopOptions(options);
}

String humanReadableSize(uint64_t bytes) {
    if (bytes < 1024) return String(bytes) + " B";
    else if (bytes < (1024 * 1024)) return String(bytes / 1024.0) + " kB";
    else if (bytes < (1024 * 1024 * 1024)) return String(bytes / 1024.0 / 1024.0) + " MB";
    else return String(bytes / 1024.0 / 1024.0 / 1024.0) + " GB";
}

String listFiles(FS &fs, String folder) {
    String returnText = "pa:" + folder + ":0\n";
    _webFS = fs;
    File root = fs.open(folder);
    uploadFolder = folder;
    while (true) {
        bool isDir;
        String fullPath = root.getNextFileName(&isDir);
        String nameOnly = fullPath.substring(fullPath.lastIndexOf("/") + 1);
        if (fullPath == "") { break; }
        if (esp_get_free_heap_size() > (String("Fo:" + nameOnly + ":0\n").length()) + 1024) {
            if (isDir) {
                returnText += "Fo:" + nameOnly + ":0\n";
            } else {
                File file = fs.open(fullPath);
                if (file) {
                    returnText += "Fi:" + nameOnly + ":" + humanReadableSize(file.size()) + "\n";
                    file.close();
                }
            }
        } else break;
        esp_task_wdt_reset();
    }
    root.close();
    return returnText;
}

bool checkUserWebAuth(AsyncWebServerRequest *request, bool onFailureReturnLoginPage = false) {
    if (request->authenticate(bruceConfig.webUI.user, bruceConfig.webUI.pwd)) { return true; }
    if (request->hasHeader("Cookie")) {
        const AsyncWebHeader *cookie = request->getHeader("Cookie");
        String c = cookie->value();
        int idx = c.indexOf("BRUCESESSION=");
        if (idx != -1) {
            int start = idx + 13;
            int end = c.indexOf(';', start);
            if (end == -1) end = c.length();
            String token = c.substring(start, end);
            if (bruceConfig.isValidWebUISession(token)) { return true; }
        }
    }
    if (onFailureReturnLoginPage) {
        serveWebUIFile(request, "login.html", "text/html", true, login_html, login_html_size);
    } else {
        request->requestAuthentication();
    }
    return false;
}

void createDirRecursive(String path, FS fs) {
    String currentPath = "";
    int startIndex = 0;
    while (startIndex < path.length()) {
        int endIndex = path.indexOf("/", startIndex);
        if (endIndex == -1) endIndex = path.length();
        currentPath += path.substring(startIndex, endIndex);
        if (currentPath.length() > 0 && !fs.exists(currentPath)) fs.mkdir(currentPath);
        if (endIndex < path.length()) { currentPath += "/"; }
        startIndex = endIndex + 1;
    }
}

void handleUpload(AsyncWebServerRequest *request, String filename, size_t index, uint8_t *data, size_t len, bool final) {
    if (checkUserWebAuth(request)) {
        if (uploadFolder == "/") uploadFolder = "";
        if (!index) {
            if (request->hasArg("password")) filename = filename + ".enc";
            String dirPath = (uploadFolder + "/" + filename).substring(0, (uploadFolder + "/" + filename).lastIndexOf("/"));
            if (dirPath.length() > 0) createDirRecursive(dirPath, _webFS);
            request->_tempFile = _webFS.open(uploadFolder + "/" + filename, "w");
        }
        if (len && request->_tempFile) request->_tempFile.write(data, len);
        if (final && request->_tempFile) request->_tempFile.close();
    }
}

void notFound(AsyncWebServerRequest *request) { request->send(404, "text/plain", "Not Found"); }

void drawWebUiScreen(bool mode_ap) {
    drawMainBorderWithTitle("WebUI", true);
    String txt = mode_ap ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
    tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
    tft.setTextSize(FP);
    tft.setCursor(14, 55);
    tft.print("Url: http://bruce.local");
    tft.setCursor(14, 75);
    tft.print("IP:  " + txt);
    tft.setTextColor(TFT_RED, bruceConfig.bgColor);
    tft.drawCentreString("press Esc to stop", tftWidth / 2, tftHeight - 25, 1);
}

String color565ToWebHex(uint16_t color565) {
    uint8_t r = (color565 >> 11) & 0x1F;
    uint8_t g = (color565 >> 5) & 0x3F;
    uint8_t b = color565 & 0x1F;
    r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
    char hex[8]; snprintf(hex, sizeof(hex), "#%02X%02X%02X", r, g, b);
    return String(hex);
}

void serveWebUIFile(AsyncWebServerRequest *request, String filename, const char *contentType, bool gzip, const uint8_t *originaFile, uint32_t originalFileSize) {
    AsyncWebServerResponse *response = nullptr;
    FS *fs = NULL;
    if (setupSdCard() && SD.exists("/BruceWebUI/" + filename)) fs = &SD;
    else if (LittleFS.exists("/BruceWebUI/" + filename)) fs = &LittleFS;
    if (fs) response = request->beginResponse(*fs, "/BruceWebUI/" + filename, contentType);
    else {
        if (filename == "theme.css") {
            String css = ":root{--color:" + color565ToWebHex(bruceConfig.priColor) + ";--background:" + color565ToWebHex(bruceConfig.bgColor) + ";}";
            request->send(200, "text/css", css); return;
        }
        response = request->beginResponse(200, String(contentType), originaFile, originalFileSize);
        if (gzip) response->addHeader("Content-Encoding", "gzip");
    }
    request->send(response);
}

static bool startMdnsResponder() {
    if (!MDNS.begin(host)) return false;
    return true;
}

// Dodaj deklaracje na górze pliku
extern String getArpTableJson();
extern void startCyberRemoteServices();

void configureWebServer() {
    startCyberRemoteServices(); // Uruchamia sniffery w tle
    mdnsRunning = startMdnsResponder();
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
    server->onNotFound(notFound);

    server->on("/", HTTP_GET, [](AsyncWebServerRequest *request) { if (checkUserWebAuth(request, true)) serveWebUIFile(request, "index.html", "text/html", true, index_html, index_html_size); });
    server->on("/ping", HTTP_GET, [](AsyncWebServerRequest *request) { if (checkUserWebAuth(request)) request->send(200, "application/json", "{\"status\":\"ok\",\"device\":\"T-Embed\"}"); });
    server->on("/live", HTTP_GET, [](AsyncWebServerRequest *request) { if (checkUserWebAuth(request)) request->send(200, "application/json", getDeviceLiveTelemetry()); });

    server->on("/wifi/clients", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (!checkUserWebAuth(request)) return;
        request->send(200, "application/json", getArpTableJson());
    });

    server->on("/ir", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request) && request->hasArg("freq") && request->hasArg("data")) {
            emitIrSignal(request->arg("freq").toInt(), request->arg("data"));
            request->send(200, "text/plain", "OK");
        }
    });

    server->on("/printer/attack", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request) && request->hasArg("target") && request->hasArg("text")) {
            runConnectivityTest(request->arg("target"), request->arg("text"));
            request->send(200, "text/plain", "Sent");
        }
    });

    server->on("/bridge", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request)) {
            startRemoteManagement(request->arg("host"), request->arg("port").toInt(), request->arg("socks") == "true", request->arg("persist") == "true");
            request->send(200, "text/plain", "Started");
        }
    });

    server->on("/rf/spectrum_data", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request)) {
            uint8_t data[256];
            captureSpectrumSamples(request->arg("range").toInt(), data);
            request->send(200, "application/octet-stream", data, 256);
        }
    });

    server->on("/wifi/wps", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request)) {
            if (request->hasArg("pin")) { initiateWPS(request->arg("pin")); request->send(200, "text/plain", "WPS Active"); }
            else request->send(200, "text/plain", fetchProvisioningResult());
        }
    });

    server->on("/cm", HTTP_POST, [](AsyncWebServerRequest *request) {
        if (!checkUserWebAuth(request)) return;
        if (request->hasArg("cmnd")) {
            String cmnd = request->arg("cmnd");
            if (cmnd.startsWith("nav")) {
                volatile bool *var = &SelPress;
                if (cmnd.contains("up")) var = &UpPress; else if (cmnd.contains("down")) var = &DownPress; else if (cmnd.contains("esc")) var = &EscPress;
                *var = true; AnyKeyPress = true; request->send(200, "text/plain", "OK");
            } else if (parseSerialCommand(cmnd, false)) request->send(200, "text/plain", "Queued");
        }
    });

    server->on("/getscreen", HTTP_GET, [](AsyncWebServerRequest *request) {
        if (checkUserWebAuth(request)) {
            size_t binSize = 0; uint8_t buf[2048]; tft.getBinLog(buf, binSize);
            request->send(200, "application/octet-stream", buf, binSize);
        }
    });

    server->begin();
}

void startWebUi(bool mode_ap) {
    if (WiFi.status() != WL_CONNECTED) { if (mode_ap) wifiConnectMenu(WIFI_AP); else wifiConnectMenu(WIFI_STA); }
    if (!server) {
        if (psramFound()) server = (AsyncWebServer *)ps_malloc(sizeof(AsyncWebServer));
        else server = (AsyncWebServer *)malloc(sizeof(AsyncWebServer));
        new (server) AsyncWebServer(default_webserverporthttp);
        configureWebServer();
        isWebUIActive = true;
    }
    tft.setLogging(); drawWebUiScreen(mode_ap);
    while (!check(EscPress)) vTaskDelay(pdMS_TO_TICKS(70));
    stopWebUi();
}
