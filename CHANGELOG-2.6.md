# BruceIR — zmiany

## 2.6.1 (19.11.2026) — wygląd menu „PILOTY” + naprawa importu Flipper IRDB

Wersja: `versionCode 4`, `versionName 2.6.1_Premium`

### Menu „IR remotes / PILOTY” wygląda teraz jak menu główne

- `CommandAdapter`: listy folderów (PILOTY, kategorie, marki z bazy Flipper) renderują się
  w stylu kart menu głównego: jednolite ciemne karty (`#141B25`, obrys `#283545`), ikona
  w kolumnie zieleń `#62CDB1`, tytuł + podtytuł z liczbą elementów, strzałka po prawej.
  Zniknęły kolorowe „folderowe” tła i podpis „Folder”.
- Nazwa folderu nie duplikuje już emoji (np. „🐬 Flipper IRDB” miało ikonę i emoji w tytule).
- Liczniki w liczbie pojedynczej („1 item” / „1 pozycja”) — nowy string `home_category_item_single`.

### Import Flipper IRDB — naprawa (przyczyna: zepsuty asset w APK + rozjazd ścieżek importu)

**Znalezione przyczyny:**
1. APK zawierał stary, **nieskompresowany `flipper_db.json` (68 MB)** zamiast
   `flipper_db.json.gz` — AGP przy budowaniu sam rozpakowuje assety z rozszerzeniem
   `.gz`, więc `assets.open("flipper_db.json.gz")` w `BruceFlipperCache` kończyło się
   `FileNotFoundException`. Efekt: klik w bazę Flipper pokazywał błąd „Błąd wczytywania”,
   a import z menu Workbench (który miał fallback na `.json`) działał inaczej.
2. W `assets` leżało **75 MB surowych plików `Flipper-IRDB-main/*.ir`**, których kod
   w ogóle nie używa (żaden loader ich nie czytał) — tylko puchł APK (102 MB).
3. Dwa różne importy (klik w folder vs „Import Flipper IR” w Workbench) tworzyły różne
   foldery (`🐬 Flipper IRDB` vs `FLIPPER-IRDB-MAIN`), a zapis biblioteki pomijał każdy
   folder, którego nazwa zawierała „Flipper” — czyli **folder użytkownika z taką nazwą
   nie był zapisywany, a przy starcie był usuwany** (cicha utrata danych).
4. Import z URL/pliku z pełną biblioteką **nadpisywał całą bibliotekę użytkownika** bez pytania.

**Naprawione:**
- Asset bazy przemianowany na `app/src/main/assets/flipper_irdb.bin` (w środku nadal gzip,
  auto-detekcja magic bytes) → APK ma teraz 3,8 MB bazy zamiast 68 MB. Fallbacki:
  `flipper_db.json.gz`, `flipper_db.json` (starsze buildy).
- `BruceFlipperCache` przepisany: jeden folder zarządzany `🐬 Flipper IRDB`, wspólny cache
  w pamięci procesu, jasne komunikaty błędów („brak pliku w APK — przebuduj”), import
  własnego pliku `.json/.json.gz` z pamięci telefonu.
- `MainActivity`: jeden wspólny przepływ importu (pointer w bazie, menu Workbench i klik
  w folder robią to samo), progress dialog z możliwością anulowania, retry i wybór pliku
  przy błędzie, nawigacja do bazy po wczytaniu. Brak duplikatów `FLIPPER-IRDB-MAIN (2)`.
- `BruceUtils`: na zapisie/starcie pomijany jest **tylko** folder zarządzany bazy
  (`isManagedFolderName`), a nie każdy z „Flipper” w nazwie → brak utraty danych.
- Import JSON z pełną biblioteką **dopisuje ją jako nowy folder** zamiast podmieniać
  `allData`; replace pozostaje tylko w jawnej akcji „przywróć backup”.
- Import pliku: obsługa `.gz`, limit 96 MB, komunikat zamiast OOM.
- Surowe `Flipper-IRDB-main/*.ir` przeniesione z `assets` do `ir_db_raw/` (katalog
  roboczy poza APK; służy do generowania `flipper_irdb.bin`).
- APK: **102 MB → 83,9 MB**.

### Weryfikacja (emulator Android 17 / API 37)

- Lista PILOTY: jednolite karty jak w menu głównym (zrzut 06).
- Klik w bazę: wczytanie kategorii ACs (96), Air_Purifiers (10), Blu-Ray (18), CCTV (4)… (zrzut 07).
- Ścieżka Workbench → „Import Flipper IR (.ir / DB)” → „Wbudowana baza” także wczytuje bazę (zrzut 08).
- Wejście w ACs → Admiral → Admiral AC pokazuje 9 komend (POWER, Mode, Fan_*, TEMP±, Timer).
- Po restarcie aplikacji: brak crashy, użytkowa baza `bruce_db.json` nadal mały (~1,6 KB),
  baza Flipper wczytywana na żądanie (nie jest zapisywana do biblioteki — celowo, żeby
  każdy zapis nie kopiował dziesiątek MB).

## 2.6.0 (19.11.2026) — moduły radio/network + bramka PIN

Wersja: `versionCode 3`, `versionName 2.6.0_Premium`
APK: `BruceIR-2.6.1-debug.apk` (83,9 MB, debug podpisany kluczem debug — instalacja przez `adb install -r` lub „Instaluj z pliku”).

## 1. Naprawa bramki PIN / profilu (zgłoszony błąd)

Było: `AppProfileManager.applyCode()` dla każdego nieznanego kodu wpadał w `else -> ProfileMode.FULL`, więc dowolny ciąg (np. `9999`) dawał pełny dostęp.

Jest:

- jawne kody: `1111` elektryka, `3333` piloty IR, `2222` wszystko bez elektryki, `7777` pełny dostęp (znane też aliasy ELEC / IR / NOELEC / FULL / ADMIN);
- nieznany kod **nie ustawia profilu i nie odblokowuje ekranu** — dialog zostaje otwarty, pokazuje „Nieprawidłowy kod. Pozostałe próby: N.”;
- po 5 błędnych próbach bramka blokuje się na 60 s (licznik trwały, przeżywa restart aplikacji);
- ekran warsztatu pod bramką jest zastępczy („Warsztat jest zablokowany…”), back nie zamyka bramki;
- kody nie są już wypisane w UI (są w tym pliku i w README);
- profil można zmienić/resetować w **Ustawienia → PROFIL APLIKACJI**; reset wraca do bramki kodu;
- każdy ekran modułu dodatkowo sprawdza profil przy wejściu (`AppProfileManager.guardModule`), a menu główne filtruje pozycje po grupach (IR / WIRELESS / NETWORK / MACROS / ELECTRICIAN / CORE) i bramkuje też akcje z intentów oraz przyciski na ekranie głównym — nie da się obejść bramki innym menu.

## 2. Nowe moduły

### Agresywny skan Wi-Fi — `AggressiveWifiScanActivity.kt` (~1260 linii)

- ciągły, łańcuchowy skan (`SCAN_RESULTS_AVAILABLE_ACTION` → kolejny `startScan`), interwał 1/2/5/10 s, tryb ciągły + „skanuj raz”, liczniki skanów/wyników/throttle;
- agregacja per BSSID: historia 30 próbek RSSI (min/avg/max, trend, sparkline), liczba wykryć, pierwszy/ostatni raz, pasmo 2.4/5/6 GHz, kanał, zabezpieczenia, flagi ryzyka (OPEN, WEP, WPS, TKIP, ukryty SSID, duplikat SSID na wielu BSSID);
- obsada kanałów, filtry (wszystkie/otwarte/WEP/WPS/pasma), wyszukiwanie, sortowanie, szczegóły pełnego `ScanResult` (API-guarded), eksport CSV i JSON;
- uprawnienia: NEARBY_WIFI_DEVICES (13+) + lokalizacja, obsługa wyłączonego Wi-Fi i throttlingu Androida.

### BLE API Explorer — `BleApiExplorerActivity.kt` (~3020 linii)

- skan LOW_LATENCY, agregacja per MAC (RSSI min/max/avg + historia, address type, connectable, adv count, TX power, appearance);
- pełny parser ramek advertising: flags, nazwy, TX power, UUID 16/32/128, service data, manufacturer data (mapa vendorów: Apple/Samsung/Google/Xiaomi/…), nieznane typy jako hex + raw hex;
- **GATT klient**: usługi/charakterystyki/deskryptory z dekodowanymi właściwościami, odczyt (hex+tekst), zapis (tekst/hex, WRITE vs NO_RESPONSE), notify/indicate z CCCD, log powiadomień, MTU, rozłączenie;
- eksport JSON (snapshot skanu + drzewo GATT), mapowanie standardowych UUID (m.in. 180A/180F/180D/1812/FE36 i serwis Bruce `4371ec0b-…`).

### Zaawansowany skaner sieci LAN — `AdvancedNetworkScannerActivity.kt` (~1820 linii)

- wykrywanie sieci (Wi-Fi i Ethernet), tryby AUTO / CIDR / jeden IP, walidacja i limit hostów;
- discovery: TCP connect (Semaphore 64, timeout 200–1500 ms) + `/proc/net/arp`, profile portów (Szybki, Top-100, własny zakres np. `1-1024`), głęboki skan 1–1024;
- fingerprinting: banery HTTP/FTP/SSH/SMTP/Telnet/POP3/IMAP/RTSP, detekcja TLS, mapa ~100 portów→usług, latencja per port;
- wzbogacanie: 131 prefiksów OUI → vendor, heurystyka typu urządzenia (router/drukarka/kamera/NAS/…);
- UPnP/SSDP (M-SEARCH), filtry, sortowanie, eksport CSV i JSON.

## 3. Poprawki bezpieczeństwa i błędów (z audytu kodu)

- **Web File Hub** (`AppWebFileServer`): wymagany token sesji (`?token=`/nagłówek/cookie) — wcześniej **każde urządzenie w LAN** miało pełny dostęp do plików i potfile’a haseł; naprawiony path traversal (`/download/..%2F..%2F`), limit uploadu 64 MB zamiast alokacji `Content-Length` (OOM), sanityzacja nazw plików, escapowanie HTML (XSS), `catch Throwable` w obsłudze klienta. Link z tokenem pokazywany w dialogu/ustawieniach.
- `MainActivity`: `exported=false` i usunięty customowy filtr `ACTION_SEND_IR` (wcześniej dowolna aplikacja mogła wywołać transmisję IR / live capture). Akcje z intentów sprawdzają profil.
- `BruceWidgetProvider`: `exported=false` (wcześniej dowolna aplikacja mogła odpalać IR/makra przez broadcast).
- `ElectricalInspectorActivity`: usunięty filtr `ACTION_VOICE_COMMAND`; mikrofon startuje tylko na jawne `AUTO_VOICE=true` z własnych komponentów (wcześniej obcy intent uruchamiał ciągłe rozpoznawanie mowy).
- `BruceBleRadarActivity`, `BruceBleSpectrumAuditorActivity`: dodana obsługa uprawnień BLUETOOTH_SCAN/CONNECT (na Androidzie 12+ skanowanie kończyło się „Błąd startu radaru”), z retry po zgodzie.
- `WpaCrackerService`: `START_NOT_STICKY` (sticky restart bez foregroundu).
- `FieldWorkbenchActivity` (launcher): żądanie POST_NOTIFICATIONS na Androidzie 13+ (wcześniej powiadomienia crackera/widgeta były wyciszone).
- `WifiSurveyActivity`: ujednolicony klucz `wifi_survey_scan_fresh` (brak wartości domyślnej → w PL/EN rozjazd i warning builda).
- Refaktor menu MainActivity na listę `MenuAction(label, grupa, akcja)` — koniec ręcznych map indeksów `when(which)`, pozycje filtrowane profilem.
- Nowe aktywności zarejestrowane w manifeście, wpisy w Field Workbench i menu „BruceIR tools”/„Wi-Fi Audit”; README zaktualizowane.

## 4. Weryfikacja

- `gradlew :app:assembleDebug` → BUILD SUCCESSFUL (Kotlin 2.2.10, AGP 9.4.1, compileSdk 36).
- Test na emulatorze Android 17 (API 37, urządzenie `Medium_Phone_API_37.0`):
  - bramka: błędny kod `9999` → „Pozostałe próby: 4”, warsztat zablokowany (zrzut 01);
  - kod `3333` → profil IR-only (tylko sekcje IR), po restarcie profil zapamiętany; kod `7777` → pełny warsztat z nowymi modułami (zrzut 02);
  - Agresywny skan Wi-Fi: 1 sieć wykryta, obsada kanałów ch6:1, flaga OPEN (zrzut 03);
  - BLE API Explorer: skan LOW_LATENCY, uprawnienia, GATT Explorer (zrzut 04);
  - Zaawansowany skaner LAN: wykryta sieć 10.0.2.16/24, skan 254 hostów, 3 hosty/3 porty (zrzut 05);
  - brak `FATAL EXCEPTION` w logcat podczas testów.

## 5. Znane ograniczenia / następne kroki

- Profil to bramka konfiguracyjna w aplikacji (kody są w APK), a nie kryptograficzna autoryzacja — przy potrzebie „twardego” dostępu trzeba by dodać hash kodu + Keystore.
- Skaner LAN: brak ICMP/root — „latencja” to czas TCP connect; na Androidzie 10+ `/proc/net/arp` może być ograniczony (brak MAC/vendor).
- BLE: emulator nie ma urządzeń rozgłoszeniowych, więc listę urządzeń i GATT najlepiej sprawdzić na telefonie z Bruce/LilyGO.
- `usesCleartextTraffic="true"` i domyślne dane `admin/bruce` nadal są w aplikacji (kompatybilność z lokalnym WebUI Bruce) — warto rozważyć network-security-config + wymuszenie hasła użytkownika.
- `allowBackup="true"` bez reguł wykluczeń — ustawienia zawierają hasło do Bruce WebUI; warto wykluczyć `sharedpref/settings`.
- Pozostałe punkty z audytu (P1/P2): zabezpieczenie `RemoteConsoleActivity` bramką, `POST_NOTIFICATIONS` w innych ekranach, globalne `GlobalScope` w `BruceUtils`, kolejkowanie transmisji Wi-Fi w `IrTransmitter`, insets na Androidzie 15+ w starszych ekranach.
