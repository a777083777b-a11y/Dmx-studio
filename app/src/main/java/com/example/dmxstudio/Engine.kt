package com.example.dmxstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// Live effects, media playback and shows (ColorPlay / VSM like).
object Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    // What is running now (shown in the UI). Empty = nothing.
    var status by mutableStateOf("")
    var showPlaying by mutableStateOf(-1)
    var showStep by mutableStateOf(-1)

    // Effect edited in the Effects tab. Changes are visible live while it plays.
    var edit by mutableStateOf(
        EffectPreset("", 3, rgbOf(255, 0, 0), rgbOf(0, 0, 255), 4000, 1f, 0, 100, 0)
    )
    var editLive by mutableStateOf(false)

    val effectNames: List<String>
        get() = listOf(
            L("Statik rang", "Статичный цвет", "Static color"),
            L("Ikki rang almashuvi", "Перелив 2 цветов", "Two-color fade"),
            L("Ranglar aylanishi", "Смена цветов", "Color cycle"),
            L("Kamalak", "Радуга", "Rainbow"),
            L("Gradient to'lqin", "Градиентная волна", "Gradient wave"),
            L("Yuguruvchi (chase)", "Бегущий огонь", "Chase"),
            L("Uchqun", "Искры", "Sparkle"),
            L("Strob", "Стробоскоп", "Strobe"),
            L("Yorug'lik to'lqini", "Волна яркости", "Brightness wave"),
            L("To'ldirish (wipe)", "Заливка (wipe)", "Wipe"),
            L("Olov", "Огонь", "Fire"),
            L("Skaner (borib-kelish)", "Сканер (туда-обратно)", "Scanner"),
            L("Yulduzlar", "Звёзды", "Twinkle")
        )

    val dirNames: List<String>
        get() = listOf(
            L("Chapdan o'ngga", "Слева направо", "Left to right"),
            L("O'ngdan chapga", "Справа налево", "Right to left"),
            L("Tepadan pastga", "Сверху вниз", "Top to bottom"),
            L("Pastdan tepaga", "Снизу вверх", "Bottom to top"),
            L("Markazdan tashqariga", "Из центра", "Center out"),
            L("Tashqaridan markazga", "К центру", "Outside in")
        )

    fun stop() {
        job?.cancel()
        job = null
        Studio.liveFrame = null
        status = ""
        showPlaying = -1
        showStep = -1
        editLive = false
    }

    private fun launchSource(name: String, src: suspend (Long) -> Map<Int, Int>?) {
        Studio.stopChase()
        Studio.stopTest()
        job?.cancel()
        status = name
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            while (isActive) {
                val f = src(System.currentTimeMillis() - t0)
                if (f != null) Studio.liveFrame = f
                delay(25)
            }
        }
    }

    // ---- effects ----
    fun playEdit() {
        launchSource(L("Effekt (tahrirlash)", "Эффект (редактор)", "Effect (editor)")) { t -> render(edit, t) }
        editLive = true
    }

    fun playEffect(i: Int) {
        val e = Studio.effects.getOrNull(i) ?: return
        launchSource(L("Effekt: ", "Эффект: ", "Effect: ") + e.name) { t -> render(e, t) }
    }

    fun playScene(i: Int, fadeMs: Int) {
        val s = Studio.scenes.getOrNull(i) ?: return
        val from = Studio.liveFrame ?: HashMap(Studio.values)
        launchSource(L("Sahna: ", "Сцена: ", "Scene: ") + s.name) { t ->
            if (t < fadeMs) blend(from, s.values, t.toFloat() / fadeMs) else s.values
        }
    }

    fun playClip(i: Int) {
        val c = Studio.clips.getOrNull(i) ?: return
        val name = L("Media: ", "Медиа: ", "Media: ") + c.name
        Studio.stopChase()
        job?.cancel()
        status = name + " " + L("(yuklanmoqda...)", "(загрузка...)", "(loading...)")
        job = scope.launch {
            val fr = Media.get(c) ?: run {
                status = L("Media ochilmadi", "Не удалось открыть медиа", "Could not open media")
                return@launch
            }
            status = name
            val t0 = System.currentTimeMillis()
            while (isActive) {
                Studio.liveFrame = Media.sample(fr, System.currentTimeMillis() - t0)
                delay(25)
            }
        }
    }

    // ---- shows ----
    fun playShow(i: Int) {
        val sh = Studio.shows.getOrNull(i) ?: return
        if (sh.items.isEmpty()) return
        Studio.stopChase()
        Studio.stopTest()
        job?.cancel()
        showPlaying = i
        status = L("Shou: ", "Шоу: ", "Show: ") + sh.name
        job = scope.launch {
            var prev: Map<Int, Int> = Studio.liveFrame ?: HashMap(Studio.values)
            do {
                val cur = Studio.shows.getOrNull(i) ?: break
                if (cur.items.isEmpty()) break
                for ((si, item) in cur.items.withIndex()) {
                    if (!isActive) break
                    showStep = si
                    val src: (Long) -> Map<Int, Int>? = when (item.kind) {
                        0 -> {
                            val s = Studio.scenes.getOrNull(item.index)
                            val fn: (Long) -> Map<Int, Int>? = { _ -> s?.values }
                            fn
                        }
                        1 -> {
                            val e = Studio.effects.getOrNull(item.index)
                            val fn: (Long) -> Map<Int, Int>? = { t -> if (e != null) render(e, t) else null }
                            fn
                        }
                        else -> {
                            val c = Studio.clips.getOrNull(item.index)
                            val fr = if (c != null) Media.get(c) else null
                            val fn: (Long) -> Map<Int, Int>? = { t -> if (fr != null) Media.sample(fr, t) else null }
                            fn
                        }
                    }
                    val t0 = System.currentTimeMillis()
                    var last: Map<Int, Int> = prev
                    while (isActive) {
                        val t = System.currentTimeMillis() - t0
                        if (t >= item.durMs) break
                        val f = src(t) ?: last
                        val out = if (item.fadeMs > 0 && t < item.fadeMs) blend(prev, f, t.toFloat() / item.fadeMs) else f
                        Studio.liveFrame = out
                        last = f
                        delay(25)
                    }
                    prev = last
                }
            } while (isActive && (Studio.shows.getOrNull(i)?.loop == true))
            if (isActive) {
                showPlaying = -1
                showStep = -1
                status = ""
            }
        }
    }

    fun blend(a: Map<Int, Int>, b: Map<Int, Int>, k0: Float): Map<Int, Int> {
        val k = k0.coerceIn(0f, 1f)
        val out = HashMap<Int, Int>()
        for (key in a.keys + b.keys) {
            val x = a[key] ?: 0
            val y = b[key] ?: 0
            out[key] = (x + (y - x) * k).toInt()
        }
        return out
    }

    // ---- effect rendering ----
    private fun frac(x: Float): Float = x - floor(x)

    private fun hash(i: Int, j: Long): Float {
        var h = (i.toLong() * 374761393L + j * 668265263L) xor 0x5DEECE66DL
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        return ((h and 0xFFFF).toInt()) / 65535f
    }

    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = frac(h) * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return rgbOf((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun lerp(c1: Int, c2: Int, k0: Float): Int {
        val k = k0.coerceIn(0f, 1f)
        return rgbOf(
            (rOf(c1) + (rOf(c2) - rOf(c1)) * k).toInt(),
            (gOf(c1) + (gOf(c2) - gOf(c1)) * k).toInt(),
            (bOf(c1) + (bOf(c2) - bOf(c1)) * k).toInt()
        )
    }

    private fun scale(c: Int, k0: Float): Int {
        val k = k0.coerceIn(0f, 1f)
        return rgbOf((rOf(c) * k).toInt(), (gOf(c) * k).toInt(), (bOf(c) * k).toInt())
    }

    fun render(e: EffectPreset, t: Long): Map<Int, Int> {
        val fx = Studio.sortedFixtures(e.universe)
        val pos = Studio.positions(fx)
        val n = fx.size
        val frame = HashMap<Int, Int>()
        val period = e.periodMs.coerceAtLeast(50)
        val ph = (t % period).toFloat() / period
        val size = e.size.coerceIn(0.02f, 1f)
        val lvl = e.level.coerceIn(0, 100) / 100f
        for ((i, f) in fx.withIndex()) {
            val (x, y) = pos[i]
            val p = when (e.dir) {
                1 -> 1f - x
                2 -> y
                3 -> 1f - y
                4 -> (sqrt((x - 0.5f) * (x - 0.5f) + (y - 0.5f) * (y - 0.5f)) / 0.7072f).coerceIn(0f, 1f)
                5 -> 1f - (sqrt((x - 0.5f) * (x - 0.5f) + (y - 0.5f) * (y - 0.5f)) / 0.7072f).coerceIn(0f, 1f)
                else -> x
            }
            val c: Int = when (e.type) {
                0 -> e.c1
                1 -> lerp(e.c1, e.c2, 0.5f - 0.5f * cos(2f * PI.toFloat() * ph))
                2 -> hsv(ph, 1f, 1f)
                3 -> hsv(frac(ph - p * size), 1f, 1f)
                4 -> lerp(e.c1, e.c2, 0.5f + 0.5f * sin(2f * PI.toFloat() * (p / size - ph)))
                5 -> {
                    val d = ph * (1f + size) - p
                    if (d >= 0f && d < size) lerp(e.c2, e.c1, 1f - d / size) else e.c2
                }
                6 -> {
                    val slot = t / (period / 8).coerceAtLeast(20)
                    if (hash(i, slot) < size * 0.5f) e.c1 else e.c2
                }
                7 -> if (ph < size * 0.5f) e.c1 else e.c2
                8 -> scale(e.c1, 0.5f + 0.5f * sin(2f * PI.toFloat() * (p / size - ph)))
                9 -> if (ph < 0.5f) {
                    if (p <= ph * 2f) e.c1 else e.c2
                } else {
                    if (p <= (ph - 0.5f) * 2f) e.c2 else e.c1
                }
                10 -> {
                    val slot = t / 60
                    val k = 0.6f * hash(i, slot) + 0.4f * hash(i + 7919, slot / 3)
                    rgbOf(255, (40 + 120 * k).toInt(), (10 * k).toInt()).let { scale(it, 0.45f + 0.55f * k) }
                }
                11 -> {
                    val tri = if (ph < 0.5f) ph * 2f else 2f - ph * 2f
                    val k = (1f - abs(p - tri) / size).coerceIn(0f, 1f)
                    lerp(e.c2, e.c1, k)
                }
                else -> {
                    val rate = 0.5f + hash(i, 1L) * 1.5f
                    val off = hash(i, 2L)
                    val k = 0.5f + 0.5f * sin(2f * PI.toFloat() * (ph * rate + off))
                    lerp(e.c2, e.c1, k * k)
                }
            }
            val cc = scale(c, lvl)
            Studio.putRgb(frame, f, rOf(cc), gOf(cc), bOf(cc))
        }
        if (n == 0) return frame
        return frame
    }
}
