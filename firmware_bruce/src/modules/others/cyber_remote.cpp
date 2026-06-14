#include <Arduino.hpp>
#include <WiFi.h>
#include <WiFiClient.h>
#include <lwip/sockets.h>
#include <lwip/netdb.h>
#include <lwip/etharp.h>
#include "esp_wps.h"
#include "core/settings.h"
#include "core/serial_commands/cli.h"
#include "modules/ir/ir_utils.h"
#include "modules/rf/rf_utils.h"
#include "modules/rf/rf_scan.h"
#include <IRsend.h>
#include <IRrecv.h>
#include <ELECHOUSE_CC1101_SRC_DRV.h>
#include <RCSwitch.h>

extern SerialCli serialCli;

// --- LIVE DATA BUFFERS ---
String lastRfCapture = "";
String lastIrCapture = "";
bool hasNewRf = false;
bool hasNewIr = false;

// --- WIFI CLIENTS / ARP TABLE ---
String getArpTableJson() {
    String json = "[";
    struct eth_addr *ethaddr;
    ip4_addr_t *ipaddr;

    for (int i = 0; i < ARP_TABLE_SIZE; i++) {
        if (etharp_get_entry(i, &ipaddr, &ethaddr)) {
            if (json != "[") json += ",";
            json += "{\"ip\":\"" + String(ip4addr_ntoa(ipaddr)) + "\",";
            char macStr[18];
            snprintf(macStr, sizeof(macStr), "%02x:%02x:%02x:%02x:%02x:%02x",
                     ethaddr->addr[0], ethaddr->addr[1], ethaddr->addr[2],
                     ethaddr->addr[3], ethaddr->addr[4], ethaddr->addr[5]);
            json += "\"mac\":\"" + String(macStr) + "\"}";
        }
    }
    json += "]";
    return json;
}

// --- RF SNIFFER (Background) ---
void backgroundSnifferTask(void *pvParameters) {
    RCSwitch mySwitch = RCSwitch();
    mySwitch.enableReceive(bruceConfigPins.rfRx);

    while(true) {
        if (mySwitch.available()) {
            lastRfCapture = "{\"proto\":" + String(mySwitch.getReceivedProtocol()) +
                            ",\"bits\":" + String(mySwitch.getReceivedBitlength()) +
                            ",\"code\":\"0x" + String((unsigned long)mySwitch.getReceivedValue(), HEX) + "\"}";
            hasNewRf = true;
            mySwitch.resetAvailable();
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

// --- SPECTRUM SAMPLING ---
void captureSpectrumSamples(int range, uint8_t* buffer) {
    float startFreq = (range == 0) ? 300.0 : (range == 1 ? 433.0 : 868.0);
    float step = (range == 0) ? 0.15 : 0.04;
    ELECHOUSE_cc1101.setRx();
    for (int i = 0; i < 256; i++) {
        ELECHOUSE_cc1101.setMHZ(startFreq + (i * step));
        delayMicroseconds(50);
        buffer[i] = ELECHOUSE_cc1101.getRssi();
    }
}

String getDeviceLiveTelemetry() {
    String json = "{";
    if (hasNewRf) { json += "\"rf\":" + lastRfCapture + ","; hasNewRf = false; }
    if (hasNewIr) { json += "\"ir\":" + lastIrCapture + ","; hasNewIr = false; }
    json += "\"status\":\"active\"}";
    return json;
}

// --- INITIALIZATION ---
void startCyberRemoteServices() {
    xTaskCreate(backgroundSnifferTask, "sniffer", 4096, NULL, 1, NULL);
}

// ... pozostałe funkcje (startRemoteManagement, emitIrSignal, initiateWPS) ...
