package com.drivedex.prototype

private const val DAY = "2026-11-05"
private fun reading(
    time: Long = 0, distance: Double? = 0.0, speed: Double? = 0.0,
    gear: Gear = Gear.PARK, ux: Boolean? = false, washer: Boolean? = null
) = VehicleReading(time, distance, speed, gear, ux, washer)

private fun completeTrip(engine: MissionEngine, initial: GameState): GameState {
    var state = engine.accept(initial, reading(0, 0.0, 15.0, Gear.DRIVE, true), DAY)
    for (minute in 1..10) {
        state = engine.accept(state, reading(minute * 60_000L, minute * 900.0, 15.0, Gear.DRIVE, true), DAY)
    }
    return engine.accept(state, reading(601_000, 9015.0), DAY)
}

fun main() {
    val state = completeTrip(MissionEngine(), GameState())
    check(state.tickets == 1)
    check(completeTrip(MissionEngine(), state).tickets == 1) { "Restart paid twice" }
    val short = MissionEngine()
    var accumulated = short.accept(GameState(), reading(0, 0.0, 15.0, Gear.DRIVE, true), DAY)
    for (minute in 1..5) {
        accumulated = short.accept(accumulated, reading(minute * 60_000L, minute * 900.0, 15.0, Gear.DRIVE, true), DAY)
    }
    accumulated = short.accept(accumulated, reading(300_001, 4500.0), DAY)
    check(accumulated.tickets == 0 && accumulated.dailyProgress.distanceM == 4500.0)
    val resumed = MissionEngine()
    accumulated = resumed.accept(accumulated, reading(0, 20_000.0, 15.0, Gear.DRIVE, true), DAY)
    for (minute in 1..5) {
        accumulated = resumed.accept(accumulated, reading(minute * 60_000L, 20_000.0 + minute * 900, 15.0, Gear.DRIVE, true), DAY)
    }
    check(accumulated.tickets == 0 && accumulated.dailyProgress.distanceM == 9000.0)
    check(resumed.accept(accumulated, reading(301_000, 9015.0 + 15_500.0, ux = null), DAY).tickets == 0)
    check(resumed.accept(accumulated, reading(302_000, 24515.0), DAY).tickets == 1)
    val tomorrow = resumed.accept(accumulated, reading(303_000, 24515.0), "2026-11-06")
    check(tomorrow.tickets == 0 && tomorrow.dailyProgress == Progress(0, 0.0))
    val missing = reading(1000, null, null, Gear.UNKNOWN, null)
    val engine = MissionEngine()
    check(engine.draw(state, missing, 0) == state)
    check(engine.draw(state, reading(gear = Gear.DRIVE), 0) == state)
    check(engine.draw(state, reading(ux = true), 0) == state)
    check(engine.draw(state, reading(), 0).tickets == 0)
    check(engine.draw(GameState(), reading(), 0).tickets == 0)
    val care = MissionEngine()
    var recovered = care.accept(GameState(), reading(washer = false), DAY)
    check(recovered.tickets == 0)
    recovered = care.accept(recovered, reading(time = 1000, washer = true), DAY)
    recovered = care.accept(recovered, reading(time = 2000, washer = false), DAY)
    check(recovered.tickets == 0)
    recovered = care.accept(recovered, reading(time = 12_000, washer = false), DAY)
    check(recovered.tickets == 1)
    recovered = care.accept(recovered, reading(time = 13_000, washer = true), DAY)
    recovered = care.accept(recovered, reading(time = 14_000, washer = false), DAY)
    recovered = care.accept(recovered, reading(time = 24_000, washer = false), DAY)
    check(recovered.tickets == 1)
    val restarting = MissionEngine()
    var persisted = restarting.accept(GameState(), reading(washer = true), DAY)
    val reopened = MissionEngine()
    persisted = reopened.accept(persisted, missing, DAY)
    check(persisted.warningActive && persisted.tickets == 0)
    persisted = reopened.accept(persisted, reading(time = 2000, washer = false), DAY)
    persisted = reopened.accept(persisted, reading(time = 12_000, washer = false), DAY)
    check(persisted.tickets == 1)
    val interrupted = MissionEngine()
    var pending = interrupted.accept(GameState(), reading(washer = true), DAY)
    pending = interrupted.accept(pending, reading(time = 1000, washer = false), DAY)
    pending = interrupted.accept(pending, missing.copy(elapsedMs = 5000), DAY)
    pending = interrupted.accept(pending, reading(time = 11_000, washer = false), DAY)
    check(pending.tickets == 0)
    val multiple = MissionEngine()
    var warnings = multiple.accept(GameState(), reading().copy(careWarnings =
        mapOf(CareItem.ENGINE_OIL to true, CareItem.BRAKE_FLUID to true)), DAY)
    warnings = multiple.accept(warnings, reading(time = 1000).copy(careWarnings =
        mapOf(CareItem.ENGINE_OIL to false, CareItem.BRAKE_FLUID to false)), DAY)
    warnings = multiple.accept(warnings, reading(time = 5000).copy(careWarnings =
        mapOf(CareItem.ENGINE_OIL to null, CareItem.BRAKE_FLUID to false)), DAY)
    warnings = multiple.accept(warnings, reading(time = 11_000).copy(careWarnings =
        mapOf(CareItem.ENGINE_OIL to false, CareItem.BRAKE_FLUID to false)), DAY)
    check(warnings.tickets == 1 && warnings.activeCareWarnings == setOf(CareItem.ENGINE_OIL))
    warnings = multiple.accept(warnings, reading(time = 21_000).copy(careWarnings =
        mapOf(CareItem.ENGINE_OIL to false)), DAY)
    check(warnings.tickets == 1 && warnings.activeCareWarnings.isEmpty())
    val expected = mapOf(0 to "mint", 39 to "mint", 40 to "sky", 79 to "sky",
        80 to "sunset", 94 to "sunset", 95 to "night", 99 to "night")
    expected.forEach { (roll, id) -> check(engine.draw(state, reading(), roll).lastCard == id) }
    val capped = state.copy(tickets = 3, paid = setOf("$DAY/a", "$DAY/b", "$DAY/c"))
    check(completeTrip(MissionEngine(), capped).tickets == 3)
    val switching = MissionEngine()
    val aaos = reading(0, 1000.0, 15.0, Gear.DRIVE, true)
    val pleos = reading(1000, 1015.0, 15.0, Gear.DRIVE, true).copy(usesPleosOdometer = true)
    var switched = switching.accept(GameState(), aaos, DAY)
    switched = switching.accept(switched, pleos, DAY)
    check(switched.dailyProgress == Progress(0, 0.0))
    switched = switching.accept(switched, pleos.copy(elapsedMs = 2000, odometerM = 1030.0), DAY)
    check(switched.dailyProgress == Progress(1000, 15.0))
    switched = switching.accept(switched, aaos.copy(elapsedMs = 3000, odometerM = 1045.0), DAY)
    check(switched.dailyProgress == Progress(1000, 15.0))
    println("PASS: trip, restart deduplication, parking lock, recovery, missing data, daily cap, rarity boundaries, odometer source changes")
}
