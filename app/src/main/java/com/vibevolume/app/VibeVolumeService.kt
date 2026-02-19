package com.vibevolume.app

import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class VibeVolumeService : Service(), SensorEventListener {

    enum class CurveMode { GRADUAL, MEDIUM, AGGRESSIVE }

    inner class LocalBinder : Binder() {
        fun getService(): VibeVolumeService = this@VibeVolumeService
    }

    interface UpdateListener {
        fun onUpdate(bluetoothCount: Int, vibrationLevel: Float, crowdScore: Float, currentVolume: Int)
    }

    private val binder = LocalBinder()
    private var updateListener: UpdateListener? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var audioManager: AudioManager? = null
    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null

    private val discoveredDevices = mutableSetOf<String>()
    private var bluetoothDeviceCount = 0
    private var isScanning = false

    private val vibrationWindow = ArrayDeque<Float>()
    private val WINDOW_SIZE = 30
    private var lastAccelMagnitude = 0f
    private var vibrationEnergy = 0f

    private var baselineBluetoothCount = -1
    private var baselineVibration = -1f

    private var minVolume = 3
    private var maxVolume = 12
    private var curveMode = CurveMode.MEDIUM

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var volumeUpdateTask: ScheduledFuture<*>? = null
    private var btScanTask: ScheduledFuture<*>? = null
    private var running = false

    private val NOTIF_CHANNEL_ID = "vibe_volume_channel"
    private val NOTIF_ID = 1

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothLeScanner = btManager.adapter?.bluetoothLeScanner
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Listening to the room..."))
        startSensing()
        running = true
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopSensing()
        super.onDestroy()
    }

    fun isRunning() = running
    fun setUpdateListener(listener: UpdateListener) { updateListener = listener }
    fun setVolumeBounds(min: Int, max: Int) { minVolume = min; maxVolume = max }
    fun setCurveMode(mode: CurveMode) { curveMode = mode }

    private fun startSensing() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        btScanTask = scheduler.scheduleAtFixedRate({ performBluetoothScan() }, 0, 30, TimeUnit.SECONDS)
        volumeUpdateTask = scheduler.scheduleAtFixedRate({ adjustVolume() }, 5, 5, TimeUnit.SECONDS)
    }

    private fun stopSensing() {
        running = false
        sensorManager.unregisterListener(this)
        stopBluetoothScan()
        volumeUpdateTask?.cancel(true)
        btScanTask?.cancel(true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val delta = abs(magnitude - lastAccelMagnitude)
            lastAccelMagnitude = magnitude
            if (vibrationWindow.size >= WINDOW_SIZE) vibrationWindow.removeFirst()
            vibrationWindow.addLast(delta)
            vibrationEnergy = if (vibrationWindow.isNotEmpty()) vibrationWindow.sum() / vibrationWindow.size else 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.device.address?.let { discoveredDevices.add(it) }
        }
        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { it.device.address?.let { addr -> discoveredDevices.add(addr) } }
        }
    }

    private fun performBluetoothScan() {
        if (isScanning) return
        try {
            discoveredDevices.clear()
            bluetoothLeScanner?.startScan(bleScanCallback)
            isScanning = true
            Handler(Looper.getMainLooper()).postDelayed({
                stopBluetoothScan()
                bluetoothDeviceCount = discoveredDevices.size
                if (baselineBluetoothCount == -1) {
                    baselineBluetoothCount = bluetoothDeviceCount
                    baselineVibration = vibrationEnergy
                }
            }, 8000)
        } catch (e: SecurityException) {
            isScanning = false
        }
    }

    private fun stopBluetoothScan() {
        try { bluetoothLeScanner?.stopScan(bleScanCallback) } catch (e: SecurityException) {}
        isScanning = false
    }

    private fun adjustVolume() {
        val rawScore = computeRawCrowdScore()
        val curvedScore = applyCurve(rawScore)
        val targetVolume = (minVolume + (maxVolume - minVolume) * curvedScore).toInt().coerceIn(minVolume, maxVolume)
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        updateListener?.onUpdate(bluetoothDeviceCount, vibrationEnergy, curvedScore, currentVol)
        updateNotification("Volume: $currentVol | Crowd: ${"%.0f".format(curvedScore * 100)}%")
    }

    private fun computeRawCrowdScore(): Float {
        val btScore = computeBluetoothScore()
        val vibScore = computeVibrationScore()
        return ((btScore * 0.6f) + (vibScore * 0.4f)).coerceIn(0f, 1f)
    }

    /**
     * GRADUAL  → sqrt curve  (x^0.5): rises fast early, flattens near ceiling
     * MEDIUM   → linear      (x^1.0): straight proportional mapping
     * AGGRESSIVE → power     (x^2.0): stays low until crowd is substantial, then surges
     */
    private fun applyCurve(raw: Float): Float = when (curveMode) {
        CurveMode.GRADUAL    -> raw.toDouble().pow(0.5).toFloat().coerceIn(0f, 1f)
        CurveMode.MEDIUM     -> raw
        CurveMode.AGGRESSIVE -> raw.toDouble().pow(2.0).toFloat().coerceIn(0f, 1f)
    }

    private fun computeBluetoothScore(): Float {
        if (baselineBluetoothCount < 0) return 0.5f
        val baseline = baselineBluetoothCount.coerceAtLeast(1).toFloat()
        return ((bluetoothDeviceCount - baseline) / baseline).coerceIn(0f, 1f)
    }

    private fun computeVibrationScore(): Float {
        if (baselineVibration < 0f) return 0.5f
        val baseline = baselineVibration.coerceAtLeast(0.001f)
        return ((vibrationEnergy / baseline - 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "VibeVolume", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VibeVolume background service"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("VibeVolume")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }
}
