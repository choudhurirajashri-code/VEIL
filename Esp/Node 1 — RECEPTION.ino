
#include <WiFi.h>

const char* WIFI_SSID = "OFFICE_RECEPTION";
const char* WIFI_PASSWORD = "OfficeNet123";
const int WIFI_CHANNEL = 1;

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n=== RECEPTION BEACON ===");

  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);

  bool started = WiFi.softAP(
    WIFI_SSID,
    WIFI_PASSWORD,
    WIFI_CHANNEL,
    false,
    4
  );

  if (started) {
    Serial.println("AP started successfully");
    Serial.print("SSID: ");
    Serial.println(WIFI_SSID);
    Serial.print("IP: ");
    Serial.println(WiFi.softAPIP());
    Serial.print("MAC: ");
    Serial.println(WiFi.softAPmacAddress());
  } else {
    Serial.println("ERROR: AP failed to start");
  }
}

void loop() {
  delay(5000);
}
