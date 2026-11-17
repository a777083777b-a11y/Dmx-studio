package com.example.dmxstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

// ColorPlay-style effect preset
data class Effect(
    val name: String,
    val type: Int,
    val colors: List<Int>,
    val period: Float,
    val size: Float,
    val dir: Int,
    val level: Int,
    val target: Int,
    val tu: Int,
    val imgW: Int = 0,
    val imgH: Int = 0,
    val img: List<Int> = emptyList()
)

// kind: 0 = effect, 1 = scene, 2 = off
data class ShowStep(val kind: Int, val ref: Int, val sec: Int)
data class Show(val name: String, val steps: List<ShowStep>, val loop: Boolean)

// LSM-style schedule. mode: 0 clock, 1 sunset, 2 sunrise. act: 0 off, 1 show, 2 effect, 3 scene
data class Sched(val on: Boolean, val mode: Int, val min: Int, val days: Int, val act: Int, val ref: Int)

object Engine {
    const val T_FIXED = 0
    const val T_CYCLE = 1
    const val T_CHASE = 2
    const val T_WAVE = 3
    const val T_RAINBOW = 4
    const val T_SPARKLE = 5
    const val T_STROBE = 6
    const val T_BREATHE = 7
    const val T_RANDOM = 8
    const val T_IMAGE = 9

    val effects = mutableStateListOf<Effect>()
    val shows = mutableStateListOf<Show>()
    val sched = mutableStateListOf<Sched>()

    var activeEffect by mutableStateOf(-1)
    var playingShow by mutableStateOf(-1)
    var showStep by mutableStateOf(-1)
    var schedOn by mutableStateOf(false)
    var lat by mutableStateOf(55.75)
    var lon by mutableStateOf(37.62)
    var lastSchedEvent by mutableStateOf("")

    @Volatile var effectFrame: Map<Int, Int> = emptyMap()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engineJob: Job? = null
    private var showJob: Job? = null
    private var schedJob: Job? = null
    private val fired = HashMap<Int, String>()

    fun typeName(t: Int): String = when (t) {
        T_FIXED -> L("Bir rang", "Один цвет", "Fixed")
        T_CYCLE -> L("Rang almashinuvi", "Смена цветов", "Color cycle")
        T_CHASE -> L("Yuguruvchi", "Бегущий огонь", "Chase")
        T_WAVE -> L("To'lqin", "Волна", "Wave")
        T_RAINBOW -> L("Kamalak", "Радуга", "Rainbow")
        T_SPARKLE -> L("Uchqun", "Искры", "Sparkle")
        T_STROBE -> L("Strob", "Строб", "Strobe")
        T_BREATHE -> L("Nafas", "Дыхание", "Breathe")
        T_RANDOM -> L("Tasodifiy ranglar", "Случайные цвета", "Random fade")
        else -> L("Rasm (VSM)", "Картинка (VSM)", "Image (VSM)")
    }

    fun targetName(t: Int, u: Int): String = when (t) {
        1 -> L("Faqat RGB/RGBW", "Только RGB/RGBW", "RGB/RGBW only")
        2 -> L("Faqat 1 kanalli (oq)", "Только 1-канальные", "1-channel only")
        3 -> "Universe $u"
        else -> L("Hammasi", "Все", "All")
    }

    fun defaults() {
        effects.add(Effect(L("Kamalak", "Радуга", "Rainbow"), T_RAINBOW, listOf(0xFF0000), 8f, 1f, 0, 100, 0, 1))
        effects.add(Effect(L("Ko'k to'lqin", "Синяя волна", "Blue wave"), T_WAVE, listOf(0x0000FF, 0x00FFFF, 0xFFFFFF), 6f, 0.5f, 0, 100, 0, 1))
        effects.add(Effect(L("Qizil yuguruvchi", "Красный бегущий", "Red chase"), T_CHASE, listOf(0xFF0000, 0x000000), 4f, 0.15f, 0, 100, 0, 1))
        effects.add(Effect(L("Oq uchqunlar", "Белые искры", "White sparkle"), T_SPARKLE, listOf(0xFFFFFF, 0x000020), 1.5f, 0.15f, 0, 100, 0, 1))
        effects.add(Effect(L("Iliq rang almashinuvi", "Тёплая смена", "Warm cycle"), T_CYCLE, listOf(0xFF4000, 0xFFB000, 0xFF0060), 10f, 1f, 0, 100, 0, 1))
        effects.add(Effect(L("Oq nafas", "Белое дыхание", "White breathe"), T_BREATHE, listOf(0xFFFFFF), 4f, 1f, 0, 100, 0, 1))
        effects.add(Effect(L("Bayroq (Rossiya)", "Триколор", "Tricolor"), T_WAVE, listOf(0xFFFFFF, 0x0000FF, 0xFF0000), 12f, 1f, 0, 100, 0, 1))
        effects.add(Effect(L("Doimiy oq", "Статичный белый", "Static white"), T_FIXED, listOf(0xFFFFFF), 5f, 1f, 0, 100, 0, 1))
    }

    fun start() {
        if (effects.isEmpty()) defaults()
        if (engineJob == null) engineJob = scope.launch { engineLoop() }
        if (schedJob == null) schedJob = scope.launch { schedLoop() }
    }

    // ---------------- effect engine ----------------
    private suspend fun engineLoop() {
        val t0 = System.nanoTime()
        while (true) {
            try {
                val e = effects.getOrNull(activeEffect)
                if (e == null) {
                    if (effectFrame.isNotEmpty()) effectFrame = emptyMap()
                    delay(100)
                    continue
                }
                effectFrame = render(e, (System.nanoTime() - t0) / 1e9)
            } catch (ex: Exception) {
                effectFrame = emptyMap()
            }
            delay(30)
        }
    }

    fun targeted(e: Effect, f: Fixture): Boolean = when (e.target) {
        1 -> f.channels >= 3
        2 -> f.channels < 3
        3 -> f.universe == e.tu
        else -> true
    }

    // normalized positions (0..1) for every fixture, by id
    fun positions(fx: List<Fixture>): Map<Int, FloatArray> {
        val res = HashMap<Int, FloatArray>()
        if (fx.isEmpty()) return res
        val minX = fx.minOf { it.x }
        val maxX = fx.maxOf { it.x }
        val minY = fx.minOf { it.y }
        val maxY = fx.maxOf { it.y }
        val sorted = fx.sortedWith(compareBy({ it.universe }, { it.address }))
        val n = sorted.size
        if (maxX - minX < 1e-6f && maxY - minY < 1e-6f) {
            sorted.forEachIndexed { i, f -> res[f.id] = floatArrayOf(if (n > 1) i.toFloat() / (n - 1) else 0.5f, 0.5f) }
            return res
        }
        val w = (maxX - minX).coerceAtLeast(1e-6f)
        val h = (maxY - minY).coerceAtLeast(1e-6f)
        for (f in fx) {
            res[f.id] = floatArrayOf(
                if (maxX - minX < 1e-6f) 0.5f else (f.x - minX) / w,
                if (maxY - minY < 1e-6f) 0.5f else (f.y - minY) / h
            )
        }
        return res
    }

    private fun frac(v: Double): Double = v - floor(v)

    private fun hf(i: Int, k: Int): Double {
        var h = i * 374761393 + k * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647.0
    }

    fun lerp(a: Int, b: Int, f: Double): Int {
        val t = f.coerceIn(0.0, 1.0)
        val r = ((a shr 16) and 255) + ((((b shr 16) and 255) - ((a shr 16) and 255)) * t).toInt()
        val g = ((a shr 8) and 255) + ((((b shr 8) and 255) - ((a shr 8) and 255)) * t).toInt()
        val bl = (a and 255) + (((b and 255) - (a and 255)) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    private fun scale(c: Int, f: Double): Int = lerp(0, c, f)

    private fun grad(cols: List<Int>, f: Double): Int {
        if (cols.isEmpty()) return 0
        if (cols.size == 1) return cols[0]
        val s = frac(f) * cols.size
        val i = floor(s).toInt() % cols.size
        return lerp(cols[i], cols[(i + 1) % cols.size], s - floor(s))
    }

    fun hsv(h: Double): Int = android.graphics.Color.HSVToColor(floatArrayOf((frac(h) * 360).toFloat(), 1f, 1f)) and 0xFFFFFF

    fun colorAt(e: Effect, t: Double, idx: Int, n: Int, id: Int, x: Float, y: Float): Int {
        val cols = if (e.colors.isEmpty()) listOf(0xFFFFFF) else e.colors
        val ph = t / e.period.coerceAtLeast(0.05f)
        val size = e.size.coerceIn(0.02f, 1f).toDouble()
        val p = when (e.dir) {
            1 -> 1.0 - x
            2 -> y.toDouble()
            3 -> 1.0 - y
            else -> x.toDouble()
        }
        val q = if (e.dir >= 2) x.toDouble() else y.toDouble()
        return when (e.type) {
            T_FIXED -> cols[0]
            T_CYCLE -> grad(cols, ph)
            T_CHASE -> {
                var pos = if (n > 1) idx.toDouble() / n else 0.0
                if (e.dir == 1 || e.dir == 3) pos = 1.0 - pos
                val d = frac(frac(ph) - pos)
                val bg = cols.getOrElse(1) { 0 }
                if (d < size) lerp(bg, cols[0], 1.0 - d / size) else bg
            }
            T_WAVE -> grad(cols, p / size - ph)
            T_RAINBOW -> hsv(p / size - ph)
            T_SPARKLE -> {
                val bg = cols.getOrElse(1) { 0 }
                val v = ph * 3 + hf(id, 7)
                val k = floor(v).toInt()
                if (hf(id, k) < size) lerp(bg, cols[0], 1.0 - frac(v)) else bg
            }
            T_STROBE -> if (frac(ph) < size.coerceIn(0.05, 0.9)) cols[0] else cols.getOrElse(1) { 0 }
            T_BREATHE -> {
                val c = cols[floor(ph).toInt().mod(cols.size)]
                scale(c, 0.5 - 0.5 * cos(2 * PI * ph))
            }
            T_RANDOM -> {
                val v = ph + hf(id, 3)
                val k = floor(v).toInt()
                val a = cols[(hf(id, k) * cols.size).toInt().coerceIn(0, cols.size - 1)]
                val b = cols[(hf(id, k + 1) * cols.size).toInt().coerceIn(0, cols.size - 1)]
                lerp(a, b, frac(v))
            }
            else -> {
                if (e.imgW <= 0 || e.imgH <= 0 || e.img.size < e.imgW * e.imgH) 0
                else {
                    val u = frac(p * size - ph)
                    val px = (u * e.imgW).toInt().coerceIn(0, e.imgW - 1)
                    val py = (q * (e.imgH - 1)).toInt().coerceIn(0, e.imgH - 1)
                    e.img[py * e.imgW + px] and 0xFFFFFF
                }
            }
        }
    }

    fun rgbOut(f: Fixture, rgb: Int, level: Int): IntArray {
        val out = IntArray(f.channels)
        val r = ((rgb shr 16) and 255) * level / 100
        val g = ((rgb shr 8) and 255) * level / 100
        val b = (rgb and 255) * level / 100
        if (f.channels >= 4) {
            val w = minOf(r, g, b)
            out[0] = r - w
            out[1] = g - w
            out[2] = b - w
            out[3] = w
        } else if (f.channels == 3) {
            out[0] = r
            out[1] = g
            out[2] = b
        } else if (f.channels >= 1) {
            out[0] = maxOf(r, g, b)
        }
        return out
    }

    fun render(e: Effect, t: Double): Map<Int, Int> {
        val fx = Studio.fixtures.toList()
        val pos = positions(fx)
        val order = fx.sortedWith(compareBy({ it.universe }, { it.address }))
        val n = order.size
        val frame = HashMap<Int, Int>()
        order.forEachIndexed { i, f ->
            if (targeted(e, f)) {
                val pp = pos[f.id] ?: floatArrayOf(0.5f, 0.5f)
                val c = colorAt(e, t, i, n, f.id, pp[0], pp[1])
                val o = rgbOut(f, c, e.level)
                for (k in o.indices) frame[Studio.key(f.universe, f.address + k)] = o[k]
            }
        }
        return frame
    }

    fun runEffect(i: Int) {
        stopShow()
        activeEffect = i
    }

    fun stopEffect() {
        activeEffect = -1
        effectFrame = emptyMap()
    }

    fun stopAll() {
        stopShow()
        stopEffect()
        Studio.stopChase()
        Studio.stopTest()
    }

    fun allOff() {
        stopAll()
        Studio.allSet(0)
    }

    fun saveEffect(e: Effect, index: Int): Int {
        return if (index >= 0 && index < effects.size) {
            effects[index] = e
            Studio.save()
            index
        } else {
            effects.add(e)
            Studio.save()
            effects.size - 1
        }
    }

    fun deleteEffect(i: Int) {
        if (i < 0 || i >= effects.size) return
        stopShow()
        if (activeEffect == i) stopEffect() else if (activeEffect > i) activeEffect -= 1
        effects.removeAt(i)
        fixRefs(0, i)
        Studio.save()
    }

    // keep show/schedule references valid after deleting effect (kind 0) or scene (kind 1)
    fun fixRefs(kind: Int, removed: Int) {
        for (si in shows.indices) {
            val s = shows[si]
            val ns = s.steps.filter { !(it.kind == kind && it.ref == removed) }
                .map { if (it.kind == kind && it.ref > removed) it.copy(ref = it.ref - 1) else it }
            shows[si] = s.copy(steps = ns)
        }
        val act = if (kind == 0) 2 else 3
        for (k in sched.indices.reversed()) {
            val s = sched[k]
            if (s.act == act && s.ref == removed) sched.removeAt(k)
            else if (s.act == act && s.ref > removed) sched[k] = s.copy(ref = s.ref - 1)
        }
    }

    // ---------------- shows ----------------
    fun addShow(name: String) {
        val n = if (name.isBlank()) L("Shou ", "Шоу ", "Show ") + (shows.size + 1) else name
        shows.add(Show(n, emptyList(), true))
        Studio.save()
    }

    fun deleteShow(i: Int) {
        if (i < 0 || i >= shows.size) return
        stopShow()
        shows.removeAt(i)
        for (k in sched.indices.reversed()) {
            val s = sched[k]
            if (s.act == 1 && s.ref == i) sched.removeAt(k)
            else if (s.act == 1 && s.ref > i) sched[k] = s.copy(ref = s.ref - 1)
        }
        Studio.save()
    }

    fun playShow(i: Int) {
        stopShow()
        val s = shows.getOrNull(i) ?: return
        if (s.steps.isEmpty()) return
        Studio.stopChase()
        Studio.blackout = false
        playingShow = i
        showJob = scope.launch {
            while (isActive) {
                val cur = shows.getOrNull(i) ?: break
                if (cur.steps.isEmpty()) break
                for ((k, st) in cur.steps.withIndex()) {
                    showStep = k
                    when (st.kind) {
                        0 -> activeEffect = st.ref
                        1 -> {
                            activeEffect = -1
                            effectFrame = emptyMap()
                            Studio.recall(st.ref, 1000)
                        }
                        else -> {
                            activeEffect = -1
                            effectFrame = emptyMap()
                            Studio.allSet(0)
                        }
                    }
                    delay(st.sec.coerceAtLeast(1) * 1000L)
                }
                if (!cur.loop) break
            }
            showStep = -1
            playingShow = -1
        }
    }

    fun stopShow() {
        showJob?.cancel()
        showJob = null
        if (playingShow >= 0) activeEffect = -1
        playingShow = -1
        showStep = -1
    }

    // ---------------- schedule (LSM) ----------------
    // minutes of day in local time, or -1 if the sun does not rise/set
    fun sunMinutes(date: LocalDate, rise: Boolean): Int {
        val rad = PI / 180
        val nDay = date.dayOfYear
        val lngHour = lon / 15
        val t = nDay + ((if (rise) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(m * rad) + 0.020 * sin(2 * m * rad) + 282.634
        l = ((l % 360) + 360) % 360
        var ra = atan(0.91764 * tan(l * rad)) / rad
        ra = ((ra % 360) + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(l * rad)
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(90.833 * rad) - sinDec * sin(lat * rad)) / (cosDec * cos(lat * rad))
        if (cosH > 1 || cosH < -1) return -1
        var h = acos(cosH) / rad
        if (rise) h = 360 - h
        h /= 15
        val tt = h + ra - 0.06571 * t - 6.622
        var ut = tt - lngHour
        ut = ((ut % 24) + 24) % 24
        val off = ZoneId.systemDefault().rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val local = ((ut + off) % 24 + 24) % 24
        return (local * 60).toInt()
    }

    fun trigMinute(s: Sched, date: LocalDate): Int {
        return when (s.mode) {
            1 -> {
                val m = sunMinutes(date, false)
                if (m < 0) -1 else (m + s.min).coerceIn(0, 1439)
            }
            2 -> {
                val m = sunMinutes(date, true)
                if (m < 0) -1 else (m + s.min).coerceIn(0, 1439)
            }
            else -> s.min.coerceIn(0, 1439)
        }
    }

    fun dayOn(s: Sched, date: LocalDate): Boolean = (s.days shr (date.dayOfWeek.value - 1)) and 1 == 1

    fun hhmm(m: Int): String = if (m < 0) "--:--" else "%02d:%02d".format(m / 60, m % 60)

    fun actName(s: Sched): String = when (s.act) {
        1 -> L("Shou: ", "Шоу: ", "Show: ") + (shows.getOrNull(s.ref)?.name ?: "?")
        2 -> L("Effekt: ", "Эффект: ", "Effect: ") + (effects.getOrNull(s.ref)?.name ?: "?")
        3 -> L("Sahna: ", "Сцена: ", "Scene: ") + (Studio.scenes.getOrNull(s.ref)?.name ?: "?")
        else -> L("O'chirish", "Выключить", "Turn off")
    }

    fun execute(s: Sched) {
        when (s.act) {
            1 -> {
                stopAll()
                Studio.allSet(0)
                playShow(s.ref)
            }
            2 -> {
                stopAll()
                Studio.allSet(0)
                Studio.blackout = false
                runEffect(s.ref)
            }
            3 -> {
                stopAll()
                Studio.blackout = false
                Studio.recall(s.ref, 2000)
            }
            else -> allOff()
        }
        lastSchedEvent = hhmm(LocalDateTime.now().let { it.hour * 60 + it.minute }) + "  " + actName(s)
    }

    // apply the latest event that should already have happened today
    fun applyCurrent() {
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        var best: Sched? = null
        var bestMin = -1
        for (s in sched.toList()) {
            if (!s.on || !dayOn(s, date)) continue
            val m = trigMinute(s, date)
            if (m in 0..nowMin && m >= bestMin) {
                best = s
                bestMin = m
            }
        }
        val b = best
        if (b != null) execute(b)
    }

    private suspend fun schedLoop() {
        var wasOn = false
        while (true) {
            try {
                if (schedOn) {
                    if (!wasOn) applyCurrent()
                    val now = LocalDateTime.now()
                    val date = now.toLocalDate()
                    val nowMin = now.hour * 60 + now.minute
                    val tag = date.toString() + " " + nowMin
                    for ((i, s) in sched.toList().withIndex()) {
                        if (!s.on || !dayOn(s, date)) continue
                        if (trigMinute(s, date) == nowMin && fired[i] != tag) {
                            fired[i] = tag
                            execute(s)
                        }
                    }
                }
                wasOn = schedOn
            } catch (e: Exception) {
            }
            delay(10000)
        }
    }

    // ---------------- layouts (VSM map) ----------------
    fun layoutGrid() {
        val byU = Studio.fixtures.toList().groupBy { it.universe }.toSortedMap()
        var row = 0
        val upd = HashMap<Int, Pair<Float, Float>>()
        for ((_, list) in byU) {
            list.sortedBy { it.address }.forEachIndexed { i, f -> upd[f.id] = Pair(i.toFloat(), row.toFloat()) }
            row += 1
        }
        applyPositions(upd)
    }

    private val facadeRe = Regex("""(?:Корп\.(\d+)\s+)?\d+\.к(\d+)\.(\S+?)\.(\d+)""")

    // Facade layout from drawing names like "3.к1.Б.041 RGBW": numbers run around the building
    fun layoutFacade(): Boolean {
        val fx = Studio.fixtures.toList()
        data class P(val f: Fixture, val korp: Int, val rgb: Boolean, val num: Int)
        val ps = ArrayList<P>()
        for (f in fx) {
            val m = facadeRe.find(f.name) ?: continue
            val korp = m.groupValues[1].toIntOrNull() ?: 1
            ps.add(P(f, korp, f.channels >= 3, m.groupValues[4].toIntOrNull() ?: 0))
        }
        if (ps.size < fx.size / 2 || ps.isEmpty()) return false
        val maxNum = ps.groupBy { Pair(it.korp, it.rgb) }.mapValues { e -> e.value.maxOf { it.num }.coerceAtLeast(1) }
        val upd = HashMap<Int, Pair<Float, Float>>()
        for (p in ps) {
            val mx = maxNum[Pair(p.korp, p.rgb)] ?: 1
            upd[p.f.id] = Pair(p.num.toFloat() / mx * 100f, (p.korp - 1) * 3f + (if (p.rgb) 0f else 1f))
        }
        // anything not matched goes in a row below
        var i = 0
        for (f in fx) if (!upd.containsKey(f.id)) {
            upd[f.id] = Pair(i.toFloat(), 100f)
            i += 1
        }
        applyPositions(upd)
        return true
    }

    fun applyPositions(upd: Map<Int, Pair<Float, Float>>) {
        for (k in Studio.fixtures.indices) {
            val f = Studio.fixtures[k]
            val p = upd[f.id] ?: continue
            Studio.fixtures[k] = f.copy(x = p.first, y = p.second)
        }
        Studio.save()
    }

    fun moveFixture(id: Int, x: Float, y: Float) {
        val k = Studio.fixtures.indexOfFirst { it.id == id }
        if (k < 0) return
        Studio.fixtures[k] = Studio.fixtures[k].copy(x = x, y = y)
    }

    // ---------------- json ----------------
    fun toJson(o: JSONObject) {
        val ea = JSONArray()
        for (e in effects) {
            val j = JSONObject()
            j.put("name", e.name)
            j.put("type", e.type)
            j.put("cols", JSONArray(e.colors))
            j.put("period", e.period.toDouble())
            j.put("size", e.size.toDouble())
            j.put("dir", e.dir)
            j.put("level", e.level)
            j.put("target", e.target)
            j.put("tu", e.tu)
            if (e.imgW > 0) {
                j.put("iw", e.imgW)
                j.put("ih", e.imgH)
                j.put("img", JSONArray(e.img))
            }
            ea.put(j)
        }
        o.put("effects", ea)
        val sa = JSONArray()
        for (s in shows) {
            val j = JSONObject()
            j.put("name", s.name)
            j.put("loop", s.loop)
            val st = JSONArray()
            for (x in s.steps) st.put(JSONArray(listOf(x.kind, x.ref, x.sec)))
            j.put("steps", st)
            sa.put(j)
        }
        o.put("shows", sa)
        val ca = JSONArray()
        for (s in sched) ca.put(JSONArray(listOf(if (s.on) 1 else 0, s.mode, s.min, s.days, s.act, s.ref)))
        o.put("sched", ca)
        o.put("schedOn", schedOn)
        o.put("lat", lat)
        o.put("lon", lon)
    }

    fun load(o: JSONObject) {
        val ea = o.optJSONArray("effects")
        if (ea != null) {
            val list = ArrayList<Effect>()
            for (i in 0 until ea.length()) {
                val j = ea.getJSONObject(i)
                val ca = j.optJSONArray("cols") ?: JSONArray()
                val cols = (0 until ca.length()).map { ca.getInt(it) }
                val ia = j.optJSONArray("img")
                val img = if (ia != null) (0 until ia.length()).map { ia.getInt(it) } else emptyList()
                list.add(
                    Effect(
                        j.optString("name"), j.optInt("type"), cols,
                        j.optDouble("period", 5.0).toFloat(), j.optDouble("size", 1.0).toFloat(),
                        j.optInt("dir"), j.optInt("level", 100), j.optInt("target"), j.optInt("tu", 1),
                        j.optInt("iw"), j.optInt("ih"), img
                    )
                )
            }
            stopAll()
            effects.clear()
            effects.addAll(list)
        }
        val sa = o.optJSONArray("shows")
        if (sa != null) {
            val list = ArrayList<Show>()
            for (i in 0 until sa.length()) {
                val j = sa.getJSONObject(i)
                val st = j.optJSONArray("steps") ?: JSONArray()
                val steps = (0 until st.length()).map {
                    val t = st.getJSONArray(it)
                    ShowStep(t.getInt(0), t.getInt(1), t.getInt(2))
                }
                list.add(Show(j.optString("name"), steps, j.optBoolean("loop", true)))
            }
            stopShow()
            shows.clear()
            shows.addAll(list)
        }
        val ca = o.optJSONArray("sched")
        if (ca != null) {
            val list = ArrayList<Sched>()
            for (i in 0 until ca.length()) {
                val t = ca.getJSONArray(i)
                list.add(Sched(t.getInt(0) == 1, t.getInt(1), t.getInt(2), t.getInt(3), t.getInt(4), t.getInt(5)))
            }
            sched.clear()
            sched.addAll(list)
        }
        if (o.has("schedOn")) schedOn = o.optBoolean("schedOn")
        if (o.has("lat")) lat = o.optDouble("lat", lat)
        if (o.has("lon")) lon = o.optDouble("lon", lon)
        if (effects.isEmpty()) defaults()
    }
}
