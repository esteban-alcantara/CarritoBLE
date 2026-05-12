package com.example.carritoble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import java.util.UUID
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private var isConnected by mutableStateOf(false)
    private var statusText by mutableStateOf("Desconectado")

    // ⚠️ Cambia esto si tu Pico tiene otro nombre BLE
    private val deviceName = "PicoCar"

    // ⚠️ Cambia estos UUIDs para que coincidan con los de tu código MicroPython
    private val serviceUUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val characteristicUUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            statusText = if (permissions.values.all { it }) {
                "Permisos concedidos. Presiona Conectar."
            } else {
                "Faltan permisos Bluetooth"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        requestBluetoothPermissions()

        setContent {
            PicoCarScreen(
                isConnected = isConnected,
                statusText = statusText,
                onConnectClick = { if (isConnected) disconnect() else scanAndConnect() },
                onForward  = { sendCommand("F") },
                onBack     = { sendCommand("B") },
                onLeft     = { sendCommand("I") },
                onRight    = { sendCommand("R") },
                onStop     = { sendCommand("S") }
            )
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionsLauncher.launch(permissions)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanAndConnect() {
        if (!hasBluetoothPermissions()) {
            statusText = "Permisos no concedidos"
            requestBluetoothPermissions()
            return
        }

        statusText = "Buscando $deviceName..."

        val scanFilter = ScanFilter.Builder().setDeviceName(deviceName).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == deviceName) {
                bluetoothLeScanner?.stopScan(this)
                statusText = "Encontrado. Conectando..."
                bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            statusText = "Error al escanear: $errorCode"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    statusText = "Conectado. Buscando servicios..."
                    isConnected = true
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    statusText = "Desconectado"
                    isConnected = false
                    commandCharacteristic = null
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
                if (characteristic != null) {
                    commandCharacteristic = characteristic
                    runOnUiThread { statusText = "¡Listo! Envía comandos." }
                } else {
                    runOnUiThread { statusText = "Característica BLE no encontrada" }
                }
            } else {
                runOnUiThread { statusText = "Error al descubrir servicios" }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: String) {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic

        if (!isConnected || gatt == null || characteristic == null) {
            statusText = "No conectado"
            return
        }

        val bytes = command.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — API moderna
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            // Android 12 y menor — API clásica
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        statusText = "Enviado: $command"
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
        isConnected = false
        statusText = "Desconectado"
    }
}

@Composable
fun PicoCarScreen(
    isConnected: Boolean,
    statusText: String,
    onConnectClick: () -> Unit,
    onForward: () -> Unit,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onStop: () -> Unit
) {
    val connectColor = if (isConnected) Color(0xFFE53935) else Color(0xFF1E88E5)

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                // Status arriba
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                )

                // Controlador centrado
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.75f)
                        .border(
                            width = 2.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 40.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        // === IZQUIERDA: Adelante / Stop / Atrás ===
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Adelante
                            IconButton(onClick = onForward, enabled = isConnected,
                                modifier = Modifier.size(56.dp)) {
                                Canvas(modifier = Modifier.size(52.dp)) {
                                    val color = if (isConnected) Color(0xFF00E676) else Color(0xFF444444)
                                    val path = Path().apply {
                                        moveTo(size.width / 2f, 0f)
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height)
                                        close()
                                    }
                                    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
                                }
                            }

                            // Stop
                            IconButton(onClick = onStop, enabled = isConnected,
                                modifier = Modifier.size(44.dp)) {
                                Canvas(modifier = Modifier.size(38.dp)) {
                                    val color = if (isConnected) Color(0xFFE53935) else Color(0xFF444444)
                                    drawCircle(
                                        color = color,
                                        radius = size.minDimension / 2f,
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }

                            // Atrás
                            IconButton(onClick = onBack, enabled = isConnected,
                                modifier = Modifier.size(56.dp)) {
                                Canvas(modifier = Modifier.size(52.dp)) {
                                    val color = if (isConnected) Color(0xFF00E676) else Color(0xFF444444)
                                    val path = Path().apply {
                                        moveTo(0f, 0f)
                                        lineTo(size.width, 0f)
                                        lineTo(size.width / 2f, size.height)
                                        close()
                                    }
                                    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
                                }
                            }
                        }

                        // === CENTRO: Botón Bluetooth ===
                        IconButton(
                            onClick = onConnectClick,
                            modifier = Modifier
                                .size(72.dp)
                                .border(2.dp, connectColor, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = "Conectar",
                                tint = connectColor,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // === DERECHA: Izquierda / Derecha ===
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onLeft, enabled = isConnected,
                                modifier = Modifier.size(56.dp)) {
                                Canvas(modifier = Modifier.size(52.dp)) {
                                    val color = if (isConnected) Color(0xFFFFB300) else Color(0xFF444444)
                                    val path = Path().apply {
                                        moveTo(size.width, 0f)
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height / 2f)
                                        close()
                                    }
                                    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
                                }
                            }

                            IconButton(onClick = onRight, enabled = isConnected,
                                modifier = Modifier.size(56.dp)) {
                                Canvas(modifier = Modifier.size(52.dp)) {
                                    val color = if (isConnected) Color(0xFFFFB300) else Color(0xFF444444)
                                    val path = Path().apply {
                                        moveTo(0f, 0f)
                                        lineTo(0f, size.height)
                                        lineTo(size.width, size.height / 2f)
                                        close()
                                    }
                                    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}