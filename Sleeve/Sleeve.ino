// =============================================
// GymCompanion.ino  —  Main Sketch
// =============================================
// Auther: name
// 
//
// Uses two reusable library modules:
//   • HeartRateMonitor.h  — reads BPM from MAX30102
//   • RepCounter.h        — counts reps using IMU + EMG
//
// Hardware: ESP32-S3, MPU6050, MAX30102, EMG sensor
// =============================================

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "HeartRateMonitor.h"   // Our reusable HR module
#include "RepCounter.h"         // Our reusable rep-counting module

// =============================================
// Wiring:   SDA → pin 8 | SCL → pin 9 | EMG (SIG) → pin 4
// =============================================
#define EMG_PIN  4
#define I2C_SDA  8
#define I2C_SCL  9

const int EMG_THRESHOLD = 800;   // EMG level that means "muscle is firing" (tune this!)

const unsigned long SEND_INTERVAL_MS = 100;   // Send data 10x per second
const unsigned long STABILIZE_MS     = 2500;  // Pause after connect for calibration

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"


// =============================================
// OBJECTS  —  one per module
// =============================================
Adafruit_MPU6050 imu;
HeartRateMonitor heartRate;
RepCounter       repCounter(EMG_PIN, EMG_THRESHOLD);

BLEServer*         bleServer   = NULL;
BLECharacteristic* bleData     = NULL;
bool               isConnected = false;

unsigned long lastSendTime   = 0;
unsigned long connectionTime = 0;


// =============================================
// BLUETOOTH — handles connect / disconnect events
// =============================================
class ConnectionHandler : public BLEServerCallbacks {

  void onConnect(BLEServer* server) {
    isConnected    = true;
    connectionTime = millis();
    Serial.println("[BLE] Phone connected — hold arm still to calibrate...");
  }

  void onDisconnect(BLEServer* server) {
    isConnected = false;
    repCounter.reset();   // Reset reps and calibration for next session
    Serial.println("[BLE] Phone disconnected — advertising again.");
    server->startAdvertising();
  }
};


// =============================================
// SETUP  —  runs once on power-on
// =============================================
void setup() {
  Serial.begin(115200);
  Serial.println("\n=== GymCompanion Starting ===");

  Wire.begin(I2C_SDA, I2C_SCL);

  // Start motion sensor
  if (imu.begin(0x68, &Wire)) {
    imu.setAccelerometerRange(MPU6050_RANGE_8_G);
    imu.setGyroRange(MPU6050_RANGE_250_DEG);
    imu.setFilterBandwidth(MPU6050_BAND_44_HZ);
    Serial.println("[OK] Motion sensor ready.");
  } else {
    Serial.println("[ERROR] Motion sensor not found! Check wiring.");
  }

  // Start heart rate sensor
  if (heartRate.begin(Wire)) {
    Serial.println("[OK] Heart rate sensor ready.");
  } else {
    Serial.println("[ERROR] Heart rate sensor not found! Check wiring.");
  }

  pinMode(EMG_PIN, INPUT);
  Serial.println("[OK] EMG sensor ready.");

  // Start Bluetooth
  BLEDevice::init("GymCompanion");
  BLEDevice::setMTU(512);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ConnectionHandler());

  BLEService* service = bleServer->createService(SERVICE_UUID);
  bleData = service->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  bleData->addDescriptor(new BLE2902());
  service->start();

  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();
  Serial.println("[OK] Bluetooth on — waiting for phone...\n");
}


// =============================================
// MAIN LOOP  —  runs continuously
// =============================================
void loop() {
  // Always read heart rate — the sensor needs continuous polling
  heartRate.update();

  // Do nothing else until the phone connects
  if (!isConnected) return;

  // Wait for the connection to stabilize before doing anything
  if (millis() - connectionTime < STABILIZE_MS) return;

  // Run the main logic 10 times per second
  if (millis() - lastSendTime < SEND_INTERVAL_MS) return;
  lastSendTime = millis();

  // --- Read the motion sensor ---
  sensors_event_t a, g, temp;
  imu.getEvent(&a, &g, &temp);
  float ax = a.acceleration.x;
  float ay = a.acceleration.y;
  float az = a.acceleration.z;

  // --- Step 1: Calibrate the resting arm position (first 10 cycles) ---
  if (!repCounter.isCalibrated()) {
    repCounter.calibrate(ax, ay, az);
    return;
  }

  // --- Step 2: Update angle and count reps ---
  repCounter.update(ax, ay, az);

  // --- Step 3: Calculate movement speed from gyro ---
  float totalAccel = sqrt(ax*ax + ay*ay + az*az);
  float gyroSpeed  = sqrt(
    pow(g.gyro.x, 2) + pow(g.gyro.y, 2) + pow(g.gyro.z, 2)
  ) * 57.2958;

  // --- Step 4: Send all data to the phone as JSON ---
  String json = "{";
  json += "\"emg\":"   + String(analogRead(EMG_PIN))      + ",";
  json += "\"accel\":" + String(totalAccel, 2)            + ",";
  json += "\"gyro\":"  + String(gyroSpeed,  2)            + ",";
  json += "\"hr\":"    + String(heartRate.getBPM())       + ",";
  json += "\"angle\":" + String(repCounter.getAngle(), 1) + ",";
  json += "\"reps\":"  + String(repCounter.getRepCount());
  json += "}";

  bleData->setValue(json.c_str());
  bleData->notify();
  Serial.println("[TX] " + json);
}
