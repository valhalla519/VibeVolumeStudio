package com.vibevolume.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var vibeService: VibeVolumeService? = null
    private var isBound = false

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBluetoothCount: TextView
    private lateinit var tvVibrationLevel: TextView
    private lateinit var tvCurrentVolume: TextView
    private lateinit var tvCrowdScore: TextView
    private lateinit var seekMin: SeekBar
    private lateinit var seekMax: SeekBar
    private lateinit var tvMinLabel: TextView
    private lateinit var tvMaxLabel: TextView
    private lateinit var btnGradual: Button
    private lateinit var btnMedium: Button
    private lateinit var btnAggressive: Button
    private lateinit var tvCurveDesc: TextView

    private var selectedCurve = VibeVolumeService.CurveMode.MEDIUM
    private val PERMISSION_REQUEST_CODE = 101

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as VibeVolumeService.LocalBinder
            vibeService = binder.getService()
            isBound = true
            vibeService?.setUpdateListener(updateListener)
            vibeService?.setCurveMode(selectedCurve)
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            vibeService = null
        }
    }

    private val updateListener = object : VibeVolumeService.UpdateListener {
        override fun onUpdate(bluetoothCount: Int, vibrationLevel: Float, crowdScore: Float, currentVolume: Int) {
            runOnUiThread {
                tvBluetoothCount.text = "Bluetooth Devices: $bluetoothCount"
                tvVibrationLevel.text = "Vibration Energy: ${"%.3f".format(vibrationLevel)}"
                tvCrowdScore.text = "Crowd Score: ${"%.0f".format(crowdScore * 100)}%"
                tvCurrentVolume.text = "System Volume: $currentVolume"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSeekBars()
        setupCurveButtons()
        checkPermissions()
        btnToggle.setOnClickListener {
            if (isBound && vibeService?.isRunning() == true) stopVibeService()
            else startVibeService()
        }
    }

    private fun bindViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvBluetoothCount = findViewById(R.id.tvBluetoothCount)
        tvVibrationLevel = findViewById(R.id.tvVibrationLevel)
        tvCurrentVolume = findViewById(R.id.tvCurrentVolume)
        tvCrowdScore = findViewById(R.id.tvCrowdScore)
        seekMin = findViewById(R.id.seekMin)
        seekMax = findViewById(R.id.seekMax)
        tvMinLabel = findViewById(R.id.tvMinLabel)
        tvMaxLabel = findViewById(R.id.tvMaxLabel)
        btnGradual = findViewById(R.id.btnGradual)
        btnMedium = findViewById(R.id.btnMedium)
        btnAggressive = findViewById(R.id.btnAggressive)
        tvCurveDesc = findViewById(R.id.tvCurveDesc)
    }

    private fun setupSeekBars() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        seekMin.max = maxVol
        seekMin.progress = (maxVol * 0.2).toInt()
        seekMax.max = maxVol
        seekMax.progress = (maxVol * 0.9).toInt()
        updateSeekLabels()

        seekMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (progress >= seekMax.progress) seekMin.progress = seekMax.progress - 1
                updateSeekLabels()
                vibeService?.setVolumeBounds(seekMin.progress, seekMax.progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (progress <= seekMin.progress) seekMax.progress = seekMin.progress + 1
                updateSeekLabels()
                vibeService?.setVolumeBounds(seekMin.progress, seekMax.progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupCurveButtons() {
        selectCurve(VibeVolumeService.CurveMode.MEDIUM)
        btnGradual.setOnClickListener { selectCurve(VibeVolumeService.CurveMode.GRADUAL) }
        btnMedium.setOnClickListener { selectCurve(VibeVolumeService.CurveMode.MEDIUM) }
        btnAggressive.setOnClickListener { selectCurve(VibeVolumeService.CurveMode.AGGRESSIVE) }
    }

    private fun selectCurve(mode: VibeVolumeService.CurveMode) {
        selectedCurve = mode
        vibeService?.setCurveMode(mode)

        val activeColor = ContextCompat.getColor(this, R.color.green)
        val inactiveColor = ContextCompat.getColor(this, R.color.button_inactive)
        val activeTextColor = ContextCompat.getColor(this, R.color.white)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        listOf(btnGradual, btnMedium, btnAggressive).forEach {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(inactiveColor)
            it.setTextColor(inactiveTextColor)
        }
        val active = when (mode) {
            VibeVolumeService.CurveMode.GRADUAL -> btnGradual
            VibeVolumeService.CurveMode.MEDIUM -> btnMedium
            VibeVolumeService.CurveMode.AGGRESSIVE -> btnAggressive
        }
        active.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
        active.setTextColor(activeTextColor)

        tvCurveDesc.text = when (mode) {
            VibeVolumeService.CurveMode.GRADUAL ->
                "Slow, smooth ramp-up. Volume barely moves until the room is quite full. Good for chill gatherings."
            VibeVolumeService.CurveMode.MEDIUM ->
                "Balanced response. Volume tracks crowd growth proportionally. Good default for most parties."
            VibeVolumeService.CurveMode.AGGRESSIVE ->
                "Reacts quickly to small crowd increases. Volume hits the ceiling fast. Good for loud events."
        }
    }

    private fun updateSeekLabels() {
        tvMinLabel.text = "Min Volume: ${seekMin.progress}"
        tvMaxLabel.text = "Max Volume: ${seekMax.progress}"
    }

    private fun startVibeService() {
        val intent = Intent(this, VibeVolumeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        tvStatus.text = "● ACTIVE — Listening to the room"
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        btnToggle.text = "STOP"
    }

    private fun stopVibeService() {
        if (isBound) { unbindService(serviceConnection); isBound = false }
        stopService(Intent(this, VibeVolumeService::class.java))
        tvStatus.text = "● INACTIVE"
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
        btnToggle.text = "START"
    }

    private fun updateUI() {
        val running = vibeService?.isRunning() == true
        tvStatus.text = if (running) "● ACTIVE — Listening to the room" else "● INACTIVE"
        tvStatus.setTextColor(
            if (running) ContextCompat.getColor(this, R.color.green)
            else ContextCompat.getColor(this, R.color.red)
        )
        btnToggle.text = if (running) "STOP" else "START"
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(serviceConnection); isBound = false }
    }
}
