// =============================================
// GymCompanion - ESP32 Firmware
// =============================================
// This program tracks bicep curl workouts using:
//   - An EMG sensor  → detects muscle activation
//   - An IMU (MPU6050) → measures arm angle
//   - A heart rate sensor (MAX30102) → reads BPM
//
// It counts reps and sends all data to a phone
// app every 100ms over Bluetooth (BLE).
// =============================================

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include "MAX30105.h"
#include "heartRate.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <math.h>

// =============================================
// Wiring: SDA → pin 8, SCL → pin 9, EMG(SIG) → pin 4
// =============================================
#define EMG_PIN            4      // Analog pin for EMG sensor
#define I2C_SDA            8      // I2C data pin
#define I2C_SCL            9      // I2C clock pin

const int   EMG_THRESHOLD    = 800;   // EMG value that means "muscle is firing"
const float ANGLE_CURLED_UP  = 3.0;   // Degrees: arm is considered curled up
const float ANGLE_BACK_DOWN  = 1.5;   // Degrees: arm is considered back at rest

const unsigned long SEND_INTERVAL_MS  = 1000;   // Send data 1 per second
const unsigned long STABILIZE_MS      = 2500;  // Wait after connect before measuring

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"


// =============================================
// GLOBAL STATE
// =============================================

// Sensors
Adafruit_MPU6050 imu;
MAX30105         heartSensor;

// Bluetooth
BLEServer*         bleServer   = NULL;
BLECharacteristic* bleData     = NULL;
bool               isConnected = false;

// Timing
unsigned long lastSendTime   = 0;
unsigned long connectionTime = 0;

// Heart rate
const byte HR_BUFFER = 4;
byte  hrReadings[HR_BUFFER];
byte  hrIndex       = 0;
long  lastBeatTime  = 0;
int   averageBPM    = 0;

// Rep counting
int  repCount   = 0;
bool armCurled  = false;

// Angle tracking
float smoothedAngle = 0.0;
float baseX = 0, baseY = 0, baseZ = 0;   // Calibrated resting position
bool  calibrated    = false;
bool  firstReading  = true;
int   calibSamples  = 0;


// =============================================
// BLUETOOTH CALLBACKS
// Called automatically when phone connects
// or disconnects
// =============================================
class ConnectionHandler : public BLEServerCallbacks {

  void onConnect(BLEServer* server) {
    isConnected    = true;
    connectionTime = millis();
    Serial.println("[BLE] Phone connected — hold arm still to calibrate...");
  }

  void onDisconnect(BLEServer* server) {
    isConnected   = false;
    calibrated    = false;
    calibSamples  = 0;
    firstReading  = true;
    baseX = baseY = baseZ = 0;
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

  // --- Motion sensor (IMU) ---
  if (imu.begin(0x68, &Wire)) {
    imu.setAccelerometerRange(MPU6050_RANGE_8_G);
    imu.setGyroRange(MPU6050_RANGE_250_DEG);
    imu.setFilterBandwidth(MPU6050_BAND_44_HZ);
    Serial.println("[OK] Motion sensor ready.");
  } else {
    Serial.println("[ERROR] Motion sensor not found! Check wiring.");
  }

  // --- Heart rate sensor ---
  if (heartSensor.begin(Wire, I2C_SPEED_FAST)) {
    heartSensor.setup();
    heartSensor.setPulseAmplitudeRed(0x1F);
    heartSensor.setPulseAmplitudeIR(0x1F);
    Serial.println("[OK] Heart rate sensor ready.");
  } else {
    Serial.println("[ERROR] Heart rate sensor not found! Check wiring.");
  }

  // --- EMG sensor ---
  pinMode(EMG_PIN, INPUT);
  Serial.println("[OK] EMG sensor ready.");

  // --- Bluetooth ---
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
// HELPER FUNCTIONS
// =============================================

// Reads the heart rate sensor and keeps a rolling average of the last 4 beats
void readHeartRate() {
  long irValue = heartSensor.getIR();

  if (checkForBeat(irValue)) {
    long interval  = millis() - lastBeatTime;
    lastBeatTime   = millis();
    float bpm      = 60000.0 / interval;

    // Only store plausible values (20–255 BPM)
    if (bpm > 20 && bpm < 255) {
      hrReadings[hrIndex++] = (byte)bpm;
      hrIndex %= HR_BUFFER;

      int total = 0;
      for (byte i = 0; i < HR_BUFFER; i++) total += hrReadings[i];
      averageBPM = total / HR_BUFFER;
    }
  }
}

// Collects 10 samples of the arm at rest to set the "zero angle" baseline
void runCalibration(float x, float y, float z) {
  baseX += x;
  baseY += y;
  baseZ += z;
  calibSamples++;

  if (calibSamples >= 10) {
    baseX /= 10.0;
    baseY /= 10.0;
    baseZ /= 10.0;
    calibrated = true;
    Serial.println("[CALIBRATION] Done! Start curling.");
  }
}

// Calculates how many degrees the arm has moved away from the calibrated rest position
float getArmAngle(float x, float y, float z) {
  float dot    = x * baseX + y * baseY + z * baseZ;
  float magNow = sqrt(x*x + y*y + z*z);
  float magRef = sqrt(baseX*baseX + baseY*baseY + baseZ*baseZ);

  float cosAngle = (magNow * magRef > 0) ? (dot / (magNow * magRef)) : 1.0;
  cosAngle = constrain(cosAngle, -1.0, 1.0);

  return acos(cosAngle) * 57.2958;   // Radians → degrees
}

// Detects a completed rep: arm must activate muscle AND curl up, then come back down
void checkForCompletedRep(float angle, int emg) {
  // Did the user curl the arm up while flexing?
  if (!armCurled && angle > ANGLE_CURLED_UP && emg > EMG_THRESHOLD) {
    armCurled = true;
    Serial.println("[REP] Curl detected — coming back down...");
  }

  // Did the arm return to the starting position?
  if (armCurled && angle < ANGLE_BACK_DOWN) {
    armCurled = false;
    repCount++;
    Serial.print("[REP] Rep complete! Total: ");
    Serial.println(repCount);
  }
}

// Packages all sensor readings as JSON and sends them to the phone via Bluetooth
void sendToBluetooth(int emg, float accel, float gyro, float angle) {
  String json = "{";
  json += "\"emg\":"   + String(emg)        + ",";
  json += "\"accel\":" + String(accel,  2)  + ",";
  json += "\"gyro\":"  + String(gyro,   2)  + ",";
  json += "\"hr\":"    + String(averageBPM) + ",";
  json += "\"angle\":" + String(angle,  1)  + ",";
  json += "\"reps\":"  + String(repCount);
  json += "}";

  bleData->setValue(json.c_str());
  bleData->notify();
  Serial.println("[TX] " + json);
}


// =============================================
// MAIN LOOP  —  runs continuously
// =============================================
void loop() {
  // Always keep reading the heart rate sensor (it needs continuous polling)
  readHeartRate();

  // Do nothing else until the phone connects
  if (!isConnected) return;

  // Wait 2.5 seconds after connecting so the connection is stable before calibrating
  if (millis() - connectionTime < STABILIZE_MS) return;

  // Limit the main logic to 10 times per second (every 100ms)
  if (millis() - lastSendTime < SEND_INTERVAL_MS) return;
  lastSendTime = millis();

  // --- Read all sensors ---
  int emg = analogRead(EMG_PIN);
  sensors_event_t a, g, temp;
  imu.getEvent(&a, &g, &temp);

  float ax = a.acceleration.x;
  float ay = a.acceleration.y;
  float az = a.acceleration.z;

  // --- Step 1: Calibrate the resting arm position (first 10 cycles only) ---
  if (!calibrated) {
    runCalibration(ax, ay, az);
    return;
  }

  // --- Step 2: Calculate how far the arm has moved from the rest position ---
  float rawAngle = getArmAngle(ax, ay, az);

  // First reading: snap instantly to the real angle (no filter lag)
  // After that: smooth the angle gently to remove sensor jitter
  if (firstReading) {
    smoothedAngle = rawAngle;
    firstReading  = false;
  } else {
    smoothedAngle = (rawAngle * 0.1) + (smoothedAngle * 0.9);
  }

  // --- Step 3: Count reps using angle and muscle activation together ---
  checkForCompletedRep(smoothedAngle, emg);

  // --- Step 4: Calculate overall movement speed from gyro and accelerometer ---
  float totalAccel = sqrt(ax*ax + ay*ay + az*az);
  float gyroSpeed  = sqrt(
    pow(g.gyro.x, 2) + pow(g.gyro.y, 2) + pow(g.gyro.z, 2)
  ) * 57.2958;   // Convert rad/s → deg/s

  // --- Step 5: Send everything to the phone app ---
  sendToBluetooth(emg, totalAccel, gyroSpeed, smoothedAngle);
}
