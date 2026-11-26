# ESP32 Device Firmware — prototype integration

This is a port of the existing working R503Pro AutoEnroll 6+6 / AutoIdentify code. It adds TFT, RTC, SD-first event journal and a background HTTPS upload worker. **Not yet hardware-compiled or verified**; test on the actual ESP32 and module before deployment.

Libraries: Adafruit Fingerprint Sensor Library, Adafruit GFX Library, Adafruit ILI9341, RTClib, ArduinoJson v6, ESP32 board core.

Pins: R503 TX→GPIO16, RX→GPIO17, VCC→3.3V, GND→GND; TFT CS27 DC26 RST25, SCK18 MOSI23 MISO19; SD CS13 shares SPI bus; DS3231 SDA21 SCL22; transistor-driven passive buzzer GPIO14. TFT backlight control GPIO32 where module supports it. Supply ratings must be checked.

Create `config.h` privately (template included) and configure Wi-Fi, access-point password, server HTTPS address, device ID/key, and HTTPS root CA. Never store secrets in a public repo. Compile for classic ESP32-WROOM-32.

The local SD journal is `/queue.jsonl`; acknowledged byte offset `/sync.pos`; cached configuration `/config.json`. The sensor firmware may have different AutoEnroll progress steps. Enroll in a supervised setting only.

**Clock:** RTC stores UTC; NTP sync updates RTC when Wi-Fi is available. Display and attendance scheduling use Asia/Colombo (+05:30). If RTC is unset, attendance is not accepted.

**Caveat:** TFT/SD share SPI, so electrical compatibility and chip-select timing need hardware verification. `SPA_ROOT_CA` must be configured: TLS verification is not disabled.
