// =============================================
// RepCounter.h
// =============================================
// A reusable class that tracks exercise reps
// using an IMU (accelerometer) and an EMG sensor.
//
// Works for any curling exercise — orientation
// agnostic, calibrates to whatever resting position
// the sensor is in when begin() is called.
//
// HOW TO USE:
//   RepCounter counter(EMG_PIN, 800);
//   counter.calibrate(ax, ay, az); // first 10 readings
//   counter.update(ax, ay, az, emg);
//   int reps = counter.getRepCount();
// =============================================

#pragma once
#include <math.h>

class RepCounter {
  public:

    // emgPin:       analog pin the EMG sensor is on
    // emgThreshold: signal level that means "muscle is firing"
    // angleUp:      degrees that count as "arm curled up"
    // angleDown:    degrees that count as "arm back at rest"
    RepCounter(int emgPin, int emgThreshold, float angleUp = 3.0, float angleDown = 1.5)
      : _emgPin(emgPin),
        _emgThreshold(emgThreshold),
        _angleUp(angleUp),
        _angleDown(angleDown) {}

    // Feed accelerometer samples during the calibration phase.
    // Returns true once calibration is complete (after 10 samples).
    bool calibrate(float x, float y, float z) {
      if (_calibrated) return true;

      _baseX += x;
      _baseY += y;
      _baseZ += z;
      _calibSamples++;

      if (_calibSamples >= 10) {
        _baseX /= 10.0;
        _baseY /= 10.0;
        _baseZ /= 10.0;
        _calibrated = true;
      }
      return _calibrated;
    }

    // Call every measurement cycle with fresh accelerometer values.
    // Reads EMG internally, updates the angle, and counts reps.
    void update(float x, float y, float z) {
      if (!_calibrated) return;

      int emg = analogRead(_emgPin);
      float rawAngle = _calculateAngle(x, y, z);

      // Snap to real angle on the very first reading to avoid filter lag
      if (_firstReading) {
        _smoothedAngle = rawAngle;
        _firstReading  = false;
      } else {
        // Low-pass filter: 10% new data, 90% history → removes sensor jitter
        _smoothedAngle = (rawAngle * 0.1) + (_smoothedAngle * 0.9);
      }

      // A rep is: arm curls UP (with muscle activation), then comes back DOWN
      if (!_armCurled && _smoothedAngle > _angleUp && emg > _emgThreshold) {
        _armCurled = true;
      }
      if (_armCurled && _smoothedAngle < _angleDown) {
        _armCurled = false;
        _repCount++;
      }
    }

    // Reset everything — useful when starting a new workout set
    void reset() {
      _repCount      = 0;
      _armCurled     = false;
      _calibrated    = false;
      _calibSamples  = 0;
      _firstReading  = true;
      _baseX = _baseY = _baseZ = 0;
    }

    bool  isCalibrated()  { return _calibrated;    }
    int   getRepCount()   { return _repCount;       }
    float getAngle()      { return _smoothedAngle;  }

  private:

    // Calculates the angle between the current arm position and the calibrated baseline
    float _calculateAngle(float x, float y, float z) {
      float dot    = x * _baseX + y * _baseY + z * _baseZ;
      float magNow = sqrt(x*x + y*y + z*z);
      float magRef = sqrt(_baseX*_baseX + _baseY*_baseY + _baseZ*_baseZ);

      float cosAngle = (magNow * magRef > 0) ? (dot / (magNow * magRef)) : 1.0;
      cosAngle = constrain(cosAngle, -1.0, 1.0);
      return acos(cosAngle) * 57.2958;   // Radians to degrees
    }

    // Settings
    int   _emgPin;
    int   _emgThreshold;
    float _angleUp;
    float _angleDown;

    // State
    int   _repCount     = 0;
    bool  _armCurled    = false;
    float _smoothedAngle = 0.0;
    bool  _firstReading  = true;

    // Calibration
    float _baseX = 0, _baseY = 0, _baseZ = 0;
    bool  _calibrated   = false;
    int   _calibSamples = 0;
};
