package com.drivedex.prototype

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.abs

/** Reads the foreground app's AAOS signals. No simulated defaults are substituted. */
internal class AutomotiveSignals(
    context: Context,
    private val readFallbackDistance: (((Double?) -> Unit) -> Unit)?,
    private val accept: (VehicleReading) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var connectionGeneration = 0
    private var closed = false
    private val car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
        val generation = ++connectionGeneration
        if (!closed) {
            if (ready) poll(service, generation)
            else accept(VehicleReading(SystemClock.elapsedRealtime(), null, null, Gear.UNKNOWN, null, null))
        }
    }

    private fun poll(service: Car, generation: Int) {
        worker.execute {
            val properties = read { service.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
            val ux = read { service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }
            val started = SystemClock.elapsedRealtime()
            val speed = properties?.value(Float::class.javaObjectType, VehiclePropertyIds.PERF_VEHICLE_SPEED)
                ?.toDouble()?.takeIf { it.isFinite() }?.let { abs(it) }
            val gear = when (properties?.value(Int::class.javaObjectType, VehiclePropertyIds.GEAR_SELECTION)) {
                null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                VehicleGear.GEAR_PARK -> Gear.PARK
                else -> Gear.DRIVE
            }
            val meters = properties?.value(Float::class.javaObjectType, VehiclePropertyIds.PERF_ODOMETER)
                ?.toDouble()?.times(1000.0)?.takeIf { it.isFinite() && it >= 0 }
            val restricted = read { ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization }
            val now = SystemClock.elapsedRealtime()
            val timely = now - started <= 2000
            val sample = if (timely) {
                VehicleReading(now, meters, speed, gear, restricted, null)
            } else {
                VehicleReading(now, null, null, Gear.UNKNOWN, null, null)
            }
            main.post {
                if (!closed && connectionGeneration == generation) {
                    fun deliver(reading: VehicleReading) {
                        if (closed || connectionGeneration != generation) return
                        accept(reading)
                        main.postDelayed({
                            if (!closed && connectionGeneration == generation) poll(service, generation)
                        }, 1000)
                    }
                    if (timely && meters == null && readFallbackDistance != null) {
                        readFallbackDistance { fallbackMeters ->
                            deliver(sample.copy(
                                odometerM = fallbackMeters,
                                usesPleosOdometer = fallbackMeters != null
                            ))
                        }
                    } else deliver(sample)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> CarPropertyManager.value(type: Class<T>, property: Int): T? =
        read {
            getProperty(type, property, 0)?.takeIf {
                it.status == CarPropertyValue.STATUS_AVAILABLE
            }?.value
        }

    private fun <T> read(block: () -> T?): T? = try {
        block()
    } catch (error: RuntimeException) {
        Log.d("CarlectorSignals", "Vehicle signal unavailable", error)
        null
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        car?.disconnect()
    }
}
