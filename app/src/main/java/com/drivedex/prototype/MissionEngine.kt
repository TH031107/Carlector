package com.drivedex.prototype

enum class Gear { PARK, DRIVE, UNKNOWN }

enum class CareItem(val label: String) {
    WASHER("워셔액"), ENGINE_OIL("엔진오일"), BRAKE_FLUID("브레이크액"),
    TRANSMISSION_OIL("변속기 오일")
}

/** Adapter boundary. Null represents missing, unavailable or failed readings. */
data class VehicleReading(
    val elapsedMs: Long,
    val odometerM: Double?,
    val speedMps: Double?,
    val gear: Gear,
    val uxRestricted: Boolean?,
    val washerLow: Boolean?,
    val careWarnings: Map<CareItem, Boolean?> = emptyMap(),
    val usesPleosOdometer: Boolean = false
) {
    val parked: Boolean get() = speedMps == 0.0 && gear == Gear.PARK && uxRestricted == false
    val validMotion: Boolean get() = odometerM != null && odometerM.isFinite() && odometerM >= 0 &&
        speedMps != null && speedMps.isFinite() && speedMps >= 0 && gear != Gear.UNKNOWN &&
        !(gear == Gear.PARK && speedMps > 0)
}

data class Progress(val movingMs: Long, val distanceM: Double)
data class Card(val id: String, val name: String, val rarity: String, val weight: Int, val color: Int)

val cards = listOf(
    Card("mint", "민트 시티", "일반", 40, 0xFF70C9B0.toInt()),
    Card("sky", "스카이 투어", "일반", 40, 0xFF7BB9EB.toInt()),
    Card("sunset", "선셋 러너", "희귀", 15, 0xFFFFB46A.toInt()),
    Card("night", "나이트 스타", "특별", 5, 0xFFA99BE8.toInt())
)

data class GameState(
    val tickets: Int = 0,
    val paid: Set<String> = emptySet(),
    val owned: Map<String, Int> = emptyMap(),
    val warningActive: Boolean = false,
    val warningEpisode: Int = 0,
    val lastCard: String? = null,
    val progressDay: String = "",
    val dailyProgress: Progress = Progress(0, 0.0),
    val activeCareWarnings: Set<CareItem> = emptySet()
)

/** Pure mission logic. The caller commits the returned state before exposing it to the UI. */
class MissionEngine {
    companion object {
        const val MIN_MOVING_MS = 600_000L
        const val MIN_DISTANCE_M = 5_000.0
        const val DAILY_CAP = 3
        const val RECOVERY_MS = 10_000L
    }

    var progress = Progress(0, 0.0)
        private set
    var message = "차량 신호를 기다리고 있습니다."
        private set
    private var previous: VehicleReading? = null
    private val recoverySince = mutableMapOf<CareItem, Long>()

    fun resetInput() {
        previous = null
        recoverySince.clear()
    }

    fun accept(state: GameState, reading: VehicleReading, day: String, maxIntervalMs: Long = 120_000): GameState {
        var next = if (state.progressDay == day) state else {
            resetInput()
            state.copy(progressDay = day, dailyProgress = Progress(0, 0.0))
        }
        progress = next.dailyProgress
        if (!reading.validMotion) {
            previous = null
            message = "차량 신호 확인이 필요해 운행 판정을 보류합니다."
        } else {
            val old = previous
            previous = reading
            var validInterval = true
            if (old != null && old.usesPleosOdometer == reading.usesPleosOdometer) {
                val dt = reading.elapsedMs - old.elapsedMs
                val distance = reading.odometerM!! - old.odometerM!!
                val moving = old.speedMps!! > 0.0 || reading.speedMps!! > 0.0
                validInterval = dt in 1..maxIntervalMs && distance >= 0 &&
                    distance <= 70.0 * dt / 1000.0 + 10 && (moving || distance <= 10)
                if (validInterval && moving) {
                    progress = Progress(
                        progress.movingMs + if (old.speedMps > 0.0) dt else 0,
                        progress.distanceM + distance
                    )
                    next = next.copy(dailyProgress = progress)
                }
            }
            if (!validInterval) {
                message = "시간·거리 신호가 일치하지 않아 이 구간을 제외했습니다."
            } else if (reading.parked && "$day/trip" !in next.paid &&
                progress.movingMs >= MIN_MOVING_MS && progress.distanceM >= MIN_DISTANCE_M
            ) {
                next = reward(next, day, "trip")
            }
        }

        for (item in CareItem.entries) {
            val warning = if (item == CareItem.WASHER) reading.washerLow else reading.careWarnings[item]
            val active = if (item == CareItem.WASHER) next.warningActive else item in next.activeCareWarnings
            when (warning) {
                true -> {
                    recoverySince.remove(item)
                    if (!active) {
                        next = if (item == CareItem.WASHER) {
                            next.copy(warningActive = true, warningEpisode = next.warningEpisode + 1)
                        } else next.copy(activeCareWarnings = next.activeCareWarnings + item)
                        message = "${item.label} 경고가 감지됐습니다. 차량 안내에 따라 확인해 주세요."
                    }
                }
                false -> if (active) {
                    val start = recoverySince[item]
                    if (start == null || reading.elapsedMs < start) {
                        recoverySince[item] = reading.elapsedMs
                        message = "${item.label} 경고 해제 상태를 재확인하고 있습니다."
                    } else if (reading.elapsedMs - start >= RECOVERY_MS && reading.parked) {
                        next = if (item == CareItem.WASHER) next.copy(warningActive = false)
                            else next.copy(activeCareWarnings = next.activeCareWarnings - item)
                        if ("$day/care" !in next.paid) next = reward(next, day, "care")
                        recoverySince.remove(item)
                    }
                }
                null -> recoverySince.remove(item)
            }
        }
        return next
    }

    private fun reward(state: GameState, day: String, mission: String): GameState {
        val key = "$day/$mission"
        val date = java.time.LocalDate.parse(day)
        val monday = date.minusDays(date.dayOfWeek.value.toLong() - 1)
        val week = (0L..6L).map { monday.plusDays(it).toString() }.toSet()
        if (key in state.paid || state.paid.count { it.startsWith("$day/") } >= DAILY_CAP ||
            state.paid.count { it.substringBefore('/') in week } >= 21
        ) {
            message = "미션별 1회 또는 일일·주간 지급 한도가 적용됐습니다."
            return state
        }
        message = if (mission == "care") "차량 경고 해제가 재확인되어 티켓 1장을 지급했습니다."
            else "유효 주행을 완료해 티켓 1장을 지급했습니다."
        return state.copy(tickets = state.tickets + 1, paid = state.paid + key)
    }

    fun draw(state: GameState, reading: VehicleReading?, roll: Int): GameState {
        require(roll in 0..99)
        if (reading?.parked != true || state.tickets <= 0) return state
        var boundary = 0
        val card = cards.first { boundary += it.weight; roll < boundary }
        return state.copy(
            tickets = state.tickets - 1,
            owned = state.owned + (card.id to ((state.owned[card.id] ?: 0) + 1)),
            lastCard = card.id
        )
    }
}
