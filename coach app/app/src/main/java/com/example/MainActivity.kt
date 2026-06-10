package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: GymViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.all { it.value }
        if (granted) {
            Toast.makeText(this, "Permissions Granted! Scanning started.", Toast.LENGTH_SHORT).show()
            viewModel.startScanning()
        } else {
            Toast.makeText(this, "Bluetooth/Location permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD0BCFF),       // Radiant Lavender
                    secondary = Color(0xFFF2B8B5),     // Alert Crimson Accent
                    background = Color(0xFF121212),    // Clean Solid Midnight Matte
                    surface = Color(0xFF1C1B1F),       // Dark Elevated Surface
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = Color(0xFFE6E1E5),  // High Contrast Text
                    onSurface = Color(0xFFE6E1E5)
                )
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    GymDashboard(
                        viewModel = viewModel,
                        onRequestPermissions = { requestPermissions() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val allGranted = needed.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.startScanning()
        } else {
            permissionLauncher.launch(needed)
        }
    }
}

@Composable
fun GymDashboard(
    viewModel: GymViewModel,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val repCount by viewModel.repCount.collectAsState()
    val speedState by viewModel.speedState.collectAsState()
    val formState by viewModel.formState.collectAsState()
    val vitalsWarning by viewModel.vitalsWarning.collectAsState()
    val coachSpeech by viewModel.coachResponseFlow.collectAsState()

    val currentHr by viewModel.currentHr.collectAsState()
    val currentEmg by viewModel.currentEmg.collectAsState()
    val currentAccel by viewModel.currentAccel.collectAsState()
    val currentGyro by viewModel.currentGyro.collectAsState()
    val currentAngle by viewModel.currentAngle.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "KinetiCoach",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                        color = Color.White,
                        modifier = Modifier.testTag("app_title")
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = when (connectionState) {
                                        BleConnectionState.CONNECTED -> Color(0xFFD0BCFF)
                                        else -> Color.Gray
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Text(
                            text = when (connectionState) {
                                BleConnectionState.DISCONNECTED -> "BLE DISCONNECTED"
                                BleConnectionState.SCANNING -> "BLE SCANNING: SCANNING..."
                                BleConnectionState.CONNECTING -> "BLE CONNECTING: CONNECTING..."
                                BleConnectionState.CONNECTED -> "BLE CONNECTED: ESP32-S3"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD0BCFF),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF49454F), RoundedCornerShape(50))
                        .border(1.dp, Color(0xFF938F99).copy(alpha = 0.2f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 18.sp)
                }
            }
        }

        // --- PRIMARY FOCUS AREA (NEON CIRCLE REP COUNTER) ---
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(230.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer decorative circle
                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.2f), RoundedCornerShape(115.dp))
                    )
                    // Inner progress track
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .border(3.dp, Color(0xFFD0BCFF).copy(alpha = 0.1f), RoundedCornerShape(100.dp))
                    )
                    // Active matching segment arc using CircularProgressIndicator
                    CircularProgressIndicator(
                        progress = { (repCount % 15) / 15f },
                        modifier = Modifier.size(200.dp),
                        color = Color(0xFFD0BCFF),
                        strokeWidth = 3.dp,
                        trackColor = Color.Transparent
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$repCount",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            lineHeight = 64.sp
                        )
                        Text(
                            text = "REPS COMPLETED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD0BCFF),
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        HorizontalDivider(
                            color = Color(0xFFD0BCFF).copy(alpha = 0.2f),
                            modifier = Modifier.width(60.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f°", currentAngle),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "JOINT ANGLE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.4f),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        // --- WARNING STATUS PILL ---
        item {
            val warningMsg = when {
                currentHr > 170 -> "Heart rate exceeds safe threshold!"
                formState.contains("Low") -> "Warning: Low muscle engagement"
                speedState == "Too Fast" -> "Slow down on eccentric phase"
                speedState == "Too Slow" -> "Speed up concentric pace"
                else -> null
            }

            if (warningMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2B8B5).copy(alpha = 0.1f), RoundedCornerShape(50))
                        .border(1.dp, Color(0xFFF2B8B5).copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(vertical = 12.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Text(
                            text = warningMsg.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF2B8B5),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFD0BCFF).copy(alpha = 0.05f), RoundedCornerShape(50))
                        .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.15f), RoundedCornerShape(50))
                        .padding(vertical = 12.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✨", fontSize = 14.sp)
                        Text(
                            text = "TRAINING CADENCE OPTIMAL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD0BCFF),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // --- METRICS GRID (SIDE BY SIDE) ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Heart Rate Metric Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFF49454F).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                               text = "HEART RATE",
                               fontSize = 11.sp,
                               fontWeight = FontWeight.Bold,
                               color = Color.White.copy(alpha = 0.7f),
                               letterSpacing = 1.sp
                            )
                            Text("❤️", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (currentHr > 0) "$currentHr" else "--",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (currentHr > 170) Color(0xFFF2B8B5) else Color.White
                            )
                            Text(
                                text = "BPM",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Activation Metric Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFF49454F).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ACTIVATION",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f),
                                letterSpacing = 1.sp
                            )
                            Text("⚡", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (currentEmg > 0) "$currentEmg" else "--",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD0BCFF)
                            )
                            Text(
                                text = "uV",
                                fontSize = 11.sp,
                                color = Color(0xFFD0BCFF).copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        // --- AI COACH BOX ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFD0BCFF), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🤖", fontSize = 18.sp, color = Color.Black)
                            }
                            Column {
                                Text(
                                    text = "COACH GEMINI",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = Color(0xFFD0BCFF),
                                    letterSpacing = 2.sp
                                )
                                Text(
                                    text = "Polled every 15 seconds",
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.speak(coachSpeech) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1B1F)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("🔊 Repeat Speech", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = Color(0xFFD0BCFF).copy(alpha = 0.1f))

                    Text(
                        text = "\"$coachSpeech\"",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE6E1E5).copy(alpha = 0.9f),
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("coach_speech_box")
                    )
                }
            }
        }

        // --- SENSOR STATUS & CONTROLS ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF49454F).copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Device Connection",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                text = when (connectionState) {
                                    BleConnectionState.DISCONNECTED -> "Device Offline - Connect sensor"
                                    BleConnectionState.SCANNING -> "Scanning for GymCompanion_ESP32..."
                                    BleConnectionState.CONNECTING -> "Handshaking..."
                                    BleConnectionState.CONNECTED -> "ESP32-S3 Hardware Connected"
                                },
                                fontSize = 12.sp,
                                color = when (connectionState) {
                                    BleConnectionState.CONNECTED -> Color(0xFFD0BCFF)
                                    else -> Color.Gray
                                }
                            )
                        }
                    }

                    if (connectionState == BleConnectionState.CONNECTED) {
                        Button(
                            onClick = { viewModel.disconnectSensor() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49454F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("disconnect_button")
                        ) {
                            Text("Disconnect Sensor", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { onRequestPermissions() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("connect_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                        ) {
                            Text(
                                text = if (connectionState == BleConnectionState.SCANNING) "Scanning for sensor..." else "Connect ESP32 Sensor",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // --- SECONDARY SENSOR DATA COMPRESSION ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ACCELEROMETRY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f G", currentAccel),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GYROSCOPIC FLOW",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f °/s", currentGyro),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

