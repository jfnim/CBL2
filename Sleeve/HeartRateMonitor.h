// =============================================
// HeartRateMonitor.h
// =============================================
// A reusable class for reading the MAX30102
// heart rate sensor and computing an average BPM.
//
// HOW TO USE:
//   HeartRateMonitor hr;
//   hr.begin();          // in setup()
//   hr.update();         // call every loop()
//   int bpm = hr.getBPM();
// =============================================

#pragma once
#include "MAX30105.h"
#include "heartRate.h"

class HeartRateMonitor {
  public:

    // Call once in setup() to start the sensor
    bool begin(TwoWire& wire = Wire) {
      if (!_sensor.begin(wire, I2C_SPEED_FAST)) return false;
      _sensor.setup();
      _sensor.setPulseAmplitudeRed(0x1F);
      _sensor.setPulseAmplitudeIR(0x1F);
      return true;
    }

    // Call every loop() — reads the sensor and updates the BPM average
    void update() {
      long irValue = _sensor.getIR();

      if (checkForBeat(irValue)) {
        long interval = millis() - _lastBeatTime;
        _lastBeatTime = millis();
        float bpm = 60000.0 / interval;

        // Only store plausible heartbeat values
        if (bpm > 20 && bpm < 255) {
          _readings[_index++] = (byte)bpm;
          _index %= BUFFER_SIZE;

          int total = 0;
          for (byte i = 0; i < BUFFER_SIZE; i++) total += _readings[i];
          _averageBPM = total / BUFFER_SIZE;
        }
      }
    }

    // Returns the rolling average BPM (0 if no beats detected yet)
    int getBPM() { return _averageBPM; }

  private:
    static const byte BUFFER_SIZE = 4;

    MAX30105 _sensor;
    byte     _readings[BUFFER_SIZE] = {0};
    byte     _index       = 0;
    long     _lastBeatTime = 0;
    int      _averageBPM  = 0;
};
