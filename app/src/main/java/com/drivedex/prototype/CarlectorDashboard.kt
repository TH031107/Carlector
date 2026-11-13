package com.drivedex.prototype

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal class CarlectorDashboard(context: Context, private val automotive: Boolean) : ScrollView(context) {
    private val ink = 0xFF172923.toInt()
    private val muted = 0xFF52645B.toInt()
    private val surface = 0xFFFFFFFF.toInt()
    private val backgroundColor = 0xFFF1F5EF.toInt()
    private val green = 0xFF176349.toInt()
    private val mint = 0xFFB9F3CF.toInt()
    private val dark = 0xFF15362B.toInt()
    private val warningColor = 0xFF94450D.toInt()

    private val content = column().apply { setPadding(dp(24), dp(22), dp(24), dp(24)) }
    private val parking = label(15, green, true)
    private val balance = label(38, surface, true)
    private val notice = label(15, muted)
    private val tripStatus = label(14, green, true)
    private val careStatus = label(14, green, true)
    private val time = Metric("이동 시간", "10분")
    private val distance = Metric("이동 거리", "5 km")
    private val speed = Signal("속도")
    private val gear = Signal("기어")
    private val odometer = Signal("계기판 거리")
    private val ux = label(14, muted)
    private val warnings = CareItem.entries.associateWith { label(15, muted) }
    private val indicators = label(15, muted)
    private val power = label(15, muted)
    val demoControls = column()

    init {
        isFillViewport = true
        setBackgroundColor(backgroundColor)
        addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(header("Carlector", parking, 28))
        if (!automotive) content.addView(label(14, muted, true).apply { text = "모의 차량 신호" })
        content.addView(ticketCard(), sectionParams())

        val trip = panel()
        trip.addView(header("오늘 운행", tripStatus))
        trip.addView(row(time.view, distance.view), sectionParams(12))
        trip.addView(notice, sectionParams(12))
        content.addView(trip, sectionParams())

        if (automotive) {
            val signals = panel()
            signals.addView(label(19, ink, true).apply { text = "차량 신호" })
            signals.addView(row(speed.view, gear.view, odometer.view), sectionParams(10))
            signals.addView(ux, sectionParams(8))
            content.addView(signals, sectionParams())
        }

        val care = panel()
        care.addView(header("차량 관리", careStatus))
        val items = if (automotive) CareItem.entries else listOf(CareItem.WASHER)
        for (item in items) care.addView(detailRow(item.label, warnings.getValue(item)), sectionParams(10))
        if (automotive) {
            care.addView(detailRow("계기판 표시등", indicators), sectionParams(10))
            care.addView(detailRow("출력 제한", power), sectionParams(10))
        }
        content.addView(care, sectionParams())
        if (!automotive && BuildConfig.DEBUG) content.addView(demoControls, sectionParams())
    }

    fun show(
        state: GameState, day: String, sample: VehicleReading?, message: String,
        activeIndicators: List<String>?, powerDown: Boolean?
    ) {
        parking.text = when {
            sample?.parked == true -> "주차 확인"
            sample?.speedMps == null || sample.gear == Gear.UNKNOWN || sample.uxRestricted == null -> "차량 신호 대기"
            else -> "주차 후 적립"
        }
        balance.text = "${state.tickets}장"
        notice.text = if (sample?.validMotion == true && message == "차량 신호를 기다리고 있습니다.") {
            "미션별 하루 1회 · 하루 3장 · 주간 21장 상한"
        } else message
        val progress = state.dailyProgress.takeIf { state.progressDay == day } ?: Progress(0, 0.0)
        val seconds = progress.movingMs / 1000
        time.show("${seconds / 60}분 ${seconds % 60}초", progress.movingMs.toDouble() / MissionEngine.MIN_MOVING_MS)
        distance.show(km(progress.distanceM, 2), progress.distanceM / MissionEngine.MIN_DISTANCE_M)
        tripStatus.text = when {
            "$day/trip" in state.paid -> "적립 완료"
            progress.movingMs >= MissionEngine.MIN_MOVING_MS && progress.distanceM >= MissionEngine.MIN_DISTANCE_M ->
                "기준 충족"
            else -> "하루 1회"
        }
        careStatus.text = if ("$day/care" in state.paid) "적립 완료" else "하루 1회"
        speed.value.text = sample?.speedMps?.let { String.format(Locale.US, "%.1f km/h", it * 3.6) } ?: "조회 불가"
        gear.value.text = when (sample?.gear) {
            Gear.PARK -> "P"
            Gear.DRIVE -> "P단 아님"
            else -> "조회 불가"
        }
        odometer.value.text = sample?.odometerM?.let { km(it, 3) } ?: "조회 불가"
        ux.text = "UX 제한 · " + when (sample?.uxRestricted) {
            true -> "적용 중"
            false -> "해제"
            null -> "조회 불가"
        }
        for ((item, view) in warnings) {
            showWarning(view, if (item == CareItem.WASHER) sample?.washerLow else sample?.careWarnings?.get(item))
        }
        indicators.text = when {
            activeIndicators == null -> "확인 불가"
            activeIndicators.isEmpty() -> "활성 표시등 없음"
            else -> activeIndicators.joinToString()
        }
        showWarning(power, powerDown)
    }

    private fun showWarning(view: TextView, active: Boolean?) {
        view.text = when (active) {
            true -> "경고 발생"
            false -> "경고 없음"
            null -> "확인 불가"
        }
        view.setTextColor(when (active) {
            true -> warningColor
            false -> green
            null -> muted
        })
    }

    private fun ticketCard() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(dark, 0xFF246247.toInt()))
            .apply { cornerRadius = dp(20).toFloat() }
        addView(column().apply {
            addView(label(15, mint, true).apply { text = "보유 티켓" })
            addView(balance)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TicketIcon(), LinearLayout.LayoutParams(dp(80), dp(62)))
    }

    private inner class TicketIcon : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(width / 80f, height / 62f)
            canvas.rotate(-12f, 40f, 31f)
            paint.color = mint
            canvas.drawRoundRect(7f, 12f, 73f, 50f, 9f, 9f, paint)
            paint.color = 0xFF21563F.toInt()
            canvas.drawCircle(7f, 31f, 5f, paint)
            canvas.drawCircle(73f, 31f, 5f, paint)
            for (y in 19..43 step 6) canvas.drawCircle(52f, y.toFloat(), 1.2f, paint)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 23f
            canvas.drawText("C", 22f, 39f, paint)
            canvas.restore()
        }
    }

    private inner class Metric(name: String, goal: String) {
        private val value = label(20, ink, true)
        private val bar = ProgressBar()
        val view = column().apply {
            addView(label(14, muted).apply { text = name })
            addView(value, sectionParams(3))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(7)).apply { topMargin = dp(6) })
            addView(label(13, muted).apply { text = "목표 $goal" }, sectionParams(5))
        }

        fun show(text: String, fraction: Double) {
            value.text = text
            bar.fraction = fraction.coerceIn(0.0, 1.0).toFloat()
            bar.invalidate()
        }
    }

    private inner class ProgressBar : View(context) {
        var fraction = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = 0xFFDFEAE1.toInt()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            if (fraction > 0f) {
                paint.color = green
                canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), radius, radius, paint)
            }
        }
    }

    private inner class Signal(title: String) {
        val value = label(16, ink, true)
        val view = column().apply {
            addView(label(13, muted).apply { text = title })
            addView(value, sectionParams(3))
        }
    }

    private fun panel() = column().apply {
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0xFFDDE7DD.toInt())
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun header(title: String, status: TextView, size: Int = 19) =
        detailRow(title, status, size, true)

    private fun detailRow(title: String, value: TextView, size: Int = 15, bold: Boolean = false) =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(size, ink, bold).apply { text = title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            value.gravity = Gravity.END
            addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(12)
            })
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun label(size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionParams(top: Int = 12) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun km(meters: Double, digits: Int) = String.format(Locale.US, "%.${digits}f km", meters / 1000)
}
