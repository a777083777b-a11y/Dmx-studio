package com.example.dmxstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// Time based automatic control (LSM-like)
object Scheduler {
    var lastAction by mutableStateOf("")
    private val fired = HashMap<Int, String>()
    private var started = false

    // Sunrise / sunset in minutes of local day. null in polar day/night.
    fun sunTimes(cal: Calendar, lat: Double, lon: Double): Pair<Int, Int>? {
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val g = 2.0 * PI / 365.0 * (n - 1)
        val eqt = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = lat * PI / 180.0
        val x = cos(90.833 * PI / 180.0) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (x < -1.0 || x > 1.0) return null
        val ha = acos(x) * 180.0 / PI
        val tzMin = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000
        val rise = 720 - 4 * (lon + ha) - eqt + tzMin
        val set = 720 - 4 * (lon - ha) - eqt + tzMin
        return Pair(((rise.toInt() % 1440) + 1440) % 1440, ((set.toInt() % 1440) + 1440) % 1440)
    }

    fun hhmm(m: Int): String {
        val v = ((m % 1440) + 1440) % 1440
        return String.format("%02d:%02d", v / 60, v % 60)
    }

    // Trigger minute of an entry for the given day, or null
    fun triggerMinute(s: Sched, cal: Calendar): Int? {
        return when (s.kind) {
            0 -> s.minutes
            1 -> sunTimes(cal, Studio.lat, Studio.lon)?.let { it.second + s.minutes }
            else -> sunTimes(cal, Studio.lat, Studio.lon)?.let { it.first + s.minutes }
        }?.let { ((it % 1440) + 1440) % 1440 }
    }

    // Monday = bit 0 ... Sunday = bit 6
    fun dayBit(cal: Calendar): Int {
        val d = cal.get(Calendar.DAY_OF_WEEK) // Sunday = 1
        val idx = if (d == Calendar.SUNDAY) 6 else d - 2
        return 1 shl idx
    }

    fun actionName(s: Sched): String = when (s.action) {
        0 -> L("O'chirish", "Выключить", "Off")
        1 -> L("Sahna: ", "Сцена: ", "Scene: ") + (Studio.scenes.getOrNull(s.target)?.name ?: "?")
        2 -> L("Effekt: ", "Эффект: ", "Effect: ") + (Studio.effects.getOrNull(s.target)?.name ?: "?")
        else -> L("Shou: ", "Шоу: ", "Show: ") + (Studio.shows.getOrNull(s.target)?.name ?: "?")
    }

    fun run(s: Sched) {
        when (s.action) {
            0 -> {
                Engine.stop()
                Studio.stopChase()
                Studio.values.clear()
            }
            1 -> Engine.playScene(s.target, 2000)
            2 -> Engine.playEffect(s.target)
            else -> Engine.playShow(s.target)
        }
        lastAction = hhmm(nowMinute()) + "  " + actionName(s)
    }

    private fun nowMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun dayKey(c: Calendar): String = "" + c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR)

    // On start: restore the state the schedule should be in right now (last event today)
    private fun catchUp() {
        val cal = Calendar.getInstance()
        val now = nowMinute()
        var best: Sched? = null
        var bestMin = -1
        for ((i, s) in Studio.schedules.toList().withIndex()) {
            if (!s.enabled || (s.days and dayBit(cal)) == 0) continue
            val m = triggerMinute(s, cal) ?: continue
            if (m <= now && m > bestMin) {
                best = s
                bestMin = m
            }
            if (m <= now) fired[i] = dayKey(cal)
        }
        best?.let { run(it) }
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            delay(1500)
            try { catchUp() } catch (e: Exception) { }
            while (true) {
                try {
                    val cal = Calendar.getInstance()
                    val now = nowMinute()
                    val key = dayKey(cal)
                    for ((i, s) in Studio.schedules.toList().withIndex()) {
                        if (!s.enabled || (s.days and dayBit(cal)) == 0) continue
                        val m = triggerMinute(s, cal) ?: continue
                        // fire within 2 minutes after the trigger time, once per day
                        if (now >= m && now - m <= 2 && fired[i] != key) {
                            fired[i] = key
                            run(s)
                        }
                    }
                } catch (e: Exception) {
                }
                delay(15000)
            }
        }
    }

    fun resetFired() {
        fired.clear()
    }
}
