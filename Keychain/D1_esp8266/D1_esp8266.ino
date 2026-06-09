// This code partially inspired by the tutorial Make Projects: Wii Motion Plus on the Arduino by rileyporter
// https://makezine.com/projects/hacking-the-wii-motionplus-to-talk-to-the-arduino/
// The source of the code used in the tutorial is written by Miles Moody
// http://randomhacksofboredom.blogspot.com/2009/07/motion-plus-and-nunchuck-together-on.html


#define ISR_SERVO_DEBUG 0  // Disable the ISR servo debug output
#include <Wire.h>
#include <ESP8266_ISR_Servo.h>

#define MIN_MICROS 544   // Minimum pulse width for servo (0)
#define MAX_MICROS 2400  // Maximum pulse width for servo (180)
#define SERVO_PIN 14

int servoIndex;
int lastYaw = 0, lastPitch = 0, lastRoll = 0;
byte data[6];             //six data bytes
int yaw, pitch, roll;     //three axes
int yaw0, pitch0, roll0;  //calibration zeroes

unsigned long lastServoUpdate = 0;  // Timestamp of last servo update
int servoPosition = 0;
int servoStep = 1;
bool flower = false;

void wmpOn() {
  Wire.beginTransmission(0x53);  //WM+ starts out deactivated at address 0x53
  Wire.write(0xfe);              //send 0x04 to address 0xFE to activate WM+
  Wire.write(0x04);
  Wire.endTransmission();  //WM+ jumps to address 0x52 and is now active
}

void wmpSendZero() {
  Wire.beginTransmission(0x52);  //now at address 0x52
  Wire.write(0x00);              //send zero to signal we want info
  Wire.endTransmission();
}

void calibrateZeroes() {
  for (int i = 0; i < 10; i++) {
    wmpSendZero();
    Wire.requestFrom(0x52, 6);
    for (int i = 0; i < 6; i++) {
      data[i] = Wire.read();
    }
    yaw0 += (((data[3] >> 2) << 8) + data[0]) / 10;  //average 10 readings for each zero
    pitch0 += (((data[4] >> 2) << 8) + data[1]) / 10;
    roll0 += (((data[5] >> 2) << 8) + data[2]) / 10;
  }
  Serial.print("Yaw0:");
  Serial.print(yaw0);
  Serial.print("  Pitch0:");
  Serial.print(pitch0);
  Serial.print("  Roll0:");
  Serial.println(roll0);
}

void receiveData() {
  wmpSendZero();              //send zero before each request (same as nunchuck)
  Wire.requestFrom(0x52, 6);  //request the six bytes from the WM+
  for (int i = 0; i < 6; i++) {
    data[i] = Wire.read();
  }
  yaw = ((data[3] >> 2) << 8) + data[0] - yaw0;      //see http://wiibrew.org/wiki/Wiimote/Extension_Controllers#Wii_Motion_Plus
  pitch = ((data[4] >> 2) << 8) + data[1] - pitch0;  //for info on what each byte represents
  roll = ((data[5] >> 2) << 8) + data[2] - roll0;
}

void setup() {
  Serial.begin(115200);
  Wire.begin(4, 5);  //SDA and SCL of Wii MotionPlus
  delay(2000);
  wmpOn();  //turn WM+ on
  delay(1000);
  calibrateZeroes();  //calibrate zeroes
  delay(1000);
  servoIndex = ISR_Servo.setupServo(SERVO_PIN, MIN_MICROS, MAX_MICROS);  //Initialize the servo
}

void loop() {
  receiveData();  //receive data and calculate yaw pitch and roll

  int shake = abs(yaw - lastYaw) + abs(pitch - lastPitch) + abs(roll - lastRoll);  // Calculate the motion
  lastYaw = yaw;
  lastPitch = pitch;
  lastRoll = roll;

  if (shake > 1500) {  //Define the shake
    flower = true;     // The flower bloom if a shake is detected
  }

  if (millis() - lastServoUpdate >= 50) {  // non-blocking timing
    lastServoUpdate = millis();

    if (flower) {  // The flower bloom while the arms are still moving up and down in the same pace
      servoPosition += servoStep;
      if (servoPosition >= 180) servoStep = -1;
      if (servoPosition <= 0) {
        servoStep = 1;
        flower = false;
      }
    } else {  // The arms moving up and down slowly
      servoPosition += servoStep;
      if (servoPosition >= 90) servoStep = -1;
      if (servoPosition <= 0) servoStep = 1;
    }
    ISR_Servo.setPosition(servoIndex, servoPosition);  // Send new positions to the servo
  }

  Serial.print("yaw:");
  Serial.print(yaw);
  Serial.print("  pitch:");
  Serial.print(pitch);
  Serial.print("  roll:");
  Serial.println(roll);
}
