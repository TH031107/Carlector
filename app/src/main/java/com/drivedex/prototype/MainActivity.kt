package com.drivedex.prototype

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.content.pm.PackageManager
import android.os.SystemClock
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var store: GameStore
    private lateinit var dashboard: CarlectorDashboard
    private val engine = MissionEngine()
    private var state = GameState()
    private var reading: VehicleReading? = null
    private var simulationMs = 0L
    private var odometerM = 0.0
    private var storageHealthy = true
    private var automotive = false
    private var signals: AutomotiveSignals? = null
    private var careSignals: PleosCareSignals? = null
    private var lastCheckpoint = 0L
    private var checkpointPending = false
    private var requestedPermissions = false
    private var notice = "개발용 모의 신호로 체험할 수 있습니다."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        automotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        store = GameStore(this, automotive)
        if (automotive) notice = "AAOS 차량 신호 연결을 기다리고 있습니다."
        try { state = store.load() } catch (_: Exception) {
            storageHealthy = false
            notice = "저장 데이터를 읽을 수 없어 보상과 뽑기를 잠갔습니다."
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!automotive || !storageHealthy) return
        careSignals = runCatching { PleosCareSignals(this) }.getOrNull()
        signals = AutomotiveSignals(this, careSignals?.let { it::readOdometerMeters }) { sample ->
            val parkedNow = sample.parked && reading?.parked != true
            val warnings = careSignals?.warnings(SystemClock.elapsedRealtime()).orEmpty()
            receive(
                sample.copy(washerLow = warnings[CareItem.WASHER], careWarnings = warnings),
                parkedNow || sample.elapsedMs - lastCheckpoint >= 60_000
            )
        }
        if (!requestedPermissions) {
            requestedPermissions = true
            val permissions = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_MILEAGE_3P")
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onStop() {
        signals?.close()
        signals = null
        careSignals?.close()
        careSignals = null
        if (automotive) {
            checkpoint()
            engine.resetInput()
            reading = null
        }
        super.onStop()
    }

    private fun receive(sample: VehicleReading, saveProgress: Boolean) {
        if (!storageHealthy) return
        reading = sample
        val old = state
        state = engine.accept(state, sample, LocalDate.now().toString(), if (automotive) 5_000 else 120_000)
        checkpointPending = checkpointPending || state != old
        if (saveProgress || state.paid != old.paid || state.warningActive != old.warningActive ||
            state.activeCareWarnings != old.activeCareWarnings
        ) checkpoint()
        if (storageHealthy) notice = engine.message
        render()
    }

    private fun accept(gear: Gear, speed: Double, restricted: Boolean?, washer: Boolean?, advance: Long) {
        if (!storageHealthy) return
        simulationMs += advance
        if (reading?.speedMps?.let { it > 0 } == true) {
            odometerM += reading!!.speedMps!! * advance / 1000.0
        }
        receive(VehicleReading(simulationMs, odometerM, speed, gear, restricted, washer), true)
    }

    private fun checkpoint() {
        if (!storageHealthy || !checkpointPending) return
        if (store.save(state)) {
            checkpointPending = false
            lastCheckpoint = SystemClock.elapsedRealtime()
        } else {
            storageHealthy = false
            notice = "저장에 실패했습니다. 앱을 다시 실행해 주세요."
            state = runCatching { store.load() }.getOrDefault(GameState())
        }
    }

    private fun render() {
        if (!::dashboard.isInitialized) {
            dashboard = CarlectorDashboard(this, automotive)
            setContentView(dashboard)
        }
        val now = SystemClock.elapsedRealtime()
        dashboard.show(
            state, LocalDate.now().toString(), reading, notice,
            careSignals?.indicators(now), careSignals?.powerDown(now)
        )
        if (BuildConfig.DEBUG && !automotive) {
            dashboard.demoControls.removeAllViews()
            developerControls()
        }
    }

    private fun developerControls() {
        text("개발자 모의 입력", 24f)
        text("아래 버튼은 시연용 가상 신호입니다. 실제 차량 조회·Virtual Console 연동과 구분됩니다.")
        val gear = reading?.gear ?: Gear.PARK
        val speed = reading?.speedMps ?: 0.0
        val restricted = reading?.uxRestricted ?: false
        val washer = reading?.washerLow
        button("P단 / 정지 / 조작 허용", storageHealthy) {
            accept(Gear.PARK, 0.0, false, washer, 1_000)
        }
        button("D단 / 54km/h 출발", storageHealthy) {
            accept(Gear.DRIVE, 15.0, true, washer, 1_000)
        }
        button("현재 주행 1분 진행", storageHealthy && speed > 0) {
            accept(gear, speed, restricted, washer, 60_000)
        }
        button("D단 / 신호 대기 / 조작 제한", storageHealthy) {
            accept(Gear.DRIVE, 0.0, true, washer, 1_000)
        }
        button("워셔액 경고 발생", storageHealthy) {
            accept(gear, speed, restricted, true, 1_000)
        }
        button("워셔액 경고 없음", storageHealthy) {
            accept(gear, speed, restricted, false, 1_000)
        }
        button("10초 후 경고 없음 재확인", storageHealthy) {
            accept(gear, speed, restricted, false, 10_000)
        }
        button("차량 데이터 수신 실패", storageHealthy) {
            simulationMs += 1_000
            receive(VehicleReading(simulationMs, null, null, Gear.UNKNOWN, null, null), true)
        }
    }

    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF172923.toInt())
        setPadding(0, 12, 0, 12)
        dashboard.demoControls.addView(this)
    }

    private fun button(label: String, enabled: Boolean, action: () -> Unit) {
        dashboard.demoControls.addView(Button(this).apply {
            text = label
            isEnabled = enabled
            minHeight = 64
            setOnClickListener { action() }
        })
    }
}
