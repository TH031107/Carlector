package com.drivedex.prototype

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

internal class PleosCareSignals(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext).also { it.initialize() }
    private val safety = vehicle.getSafety()
    private var closed = false

    private data class Sample<T>(val time: Long, val value: T)
    private val warnings = mutableMapOf<CareItem, Sample<Boolean?>>()
    private var indicators: Sample<List<String>?>? = null
    private var powerDown: Sample<Boolean?>? = null

    init {
        poll()
    }

    fun warnings(now: Long): Map<CareItem, Boolean?> = CareItem.entries.associateWith {
        warnings[it]?.fresh(now)
    }

    fun indicators(now: Long): List<String>? = indicators?.fresh(now)
    fun powerDown(now: Long): Boolean? = powerDown?.fresh(now)

    fun readOdometerMeters(accept: (Double?) -> Unit) {
        val odometer = vehicle.getOdometer()
        query(
            "odometer", odometer::checkCurrentDistanceDrivenCapabilities,
            odometer::getCurrentDistanceDriven, logTag = "CarlectorSignals"
        ) { result ->
            accept(result.value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }?.times(1000.0))
        }
    }

    private fun <T> Sample<T>.fresh(now: Long): T? = value.takeIf { now - time in 0..6000 }

    private fun poll() {
        query("washer", safety::checkLowWasherFluidWarningStateCapabilities, safety::hasLowWasherFluidWarning) {
            warnings[CareItem.WASHER] = it
        }
        oil(CareItem.ENGINE_OIL, safety::checkEngineOilLevelStateCapabilities, safety::getEngineOilLevelState)
        oil(CareItem.BRAKE_FLUID, safety::checkBrakeFluidLevelStateCapabilities, safety::getBrakeFluidLevelState)
        oil(CareItem.TRANSMISSION_OIL, safety::checkTransmissionOilLevelStateCapabilities, safety::getTransmissionOilLevelState)
        query("power down", safety::checkPowerDownWarningIndicatorCapabilities, safety::hasPowerDownWarningIndicator) {
            powerDown = it
        }
        query("telltales", safety::checkTelltalesCapabilities, safety::getTelltalesInfo) { result ->
            indicators = Sample(result.time, result.value?.telltales
                ?.filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
                ?.map { it.type.name }?.distinct())
        }
        main.postDelayed(::poll, 3000)
    }

    private fun oil(
        item: CareItem,
        capability: ((CapabilityConfig, List<OilLevelState>) -> Unit, (Exception) -> Unit) -> Unit,
        get: ((OilLevelState?) -> Unit, (Exception) -> Unit) -> Unit
    ) {
        query(item.name, { ok, fail -> capability({ config, _ -> ok(config) }, fail) }, get) { result ->
            val warning = when (result.value) {
                OilLevelState.LOW, OilLevelState.HIGH, OilLevelState.CRITICALLY_LOW -> true
                OilLevelState.NORMAL -> false
                OilLevelState.ERROR, null -> null
            }
            warnings[item] = Sample(result.time, warning)
        }
    }

    private fun <T> query(
        name: String,
        capability: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
        get: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        logTag: String = "CarlectorSafety",
        update: (Sample<T?>) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        var done = false
        fun complete(value: T?) {
            if (!closed && !done) {
                done = true
                val now = SystemClock.elapsedRealtime()
                update(Sample(now, value.takeIf { now - started <= 2500 }))
            }
        }
        val fail: (Exception) -> Unit = { error ->
            Log.d(logTag, "$name unavailable: ${error.message}")
            main.post { complete(null) }
        }
        main.postDelayed({ complete(null) }, 2500)
        try {
            capability({ config ->
                main.post {
                    if (!closed && !done) {
                        if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                            try {
                                get({ value -> main.post { complete(value) } }, fail)
                            } catch (error: RuntimeException) {
                                fail(error)
                            }
                        } else complete(null)
                    }
                }
            }, fail)
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        vehicle.release()
    }
}
