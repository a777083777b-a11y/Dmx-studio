package com.example.dmxstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

fun L(uz: String, ru: String, en: String): String = when (Studio.lang) {
    1 -> ru
    2 -> en
    else -> uz
}

data class Fixture(val id: Int, val name: String, val universe: Int, val address: Int, val channels: Int)
data class Scene(val name: String, val values: Map<Int, Int>)
data class ChaseStep(val scene: Int, val holdMs: Int, val fadeMs: Int)
data class Chase(val name: String, val steps: List<ChaseStep>)

object Studio {
    val fixtures = mutableStateListOf<Fixture>()
    val scenes = mutableStateListOf<Scene>()
    val chases = mutableStateListOf<Chase>()
    val values = mutableStateMapOf<Int, Int>()

    var ip by mutableStateOf("255.255.255.255")
    var u0 by mutableStateOf(0)
    var master by mutableStateOf(100)
    var blackout by mutableStateOf(false)
    var packets by mutableStateOf(0)
    var lastError by mutableStateOf("")
    var playing by mutableStateOf(-1)
    var lang by mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var file: File? = null
    private var started = false
    private var chaseJob: Job? = null
    private var fadeJob: Job? = null

    fun key(u: Int, ch: Int): Int = (u - 1) * 512 + (ch - 1)

    fun get(u: Int, ch: Int): Int = values[key(u, ch)] ?: 0

    fun set(u: Int, ch: Int, v: Int) {
        values[key(u, ch)] = v.coerceIn(0, 255)
    }

    fun allSet(v: Int) {
        for (f in fixtures.toList()) {
            for (i in 0 until f.channels) set(f.universe, f.address + i, v)
        }
    }

    fun start(dir: File) {
        if (started) return
        started = true
        file = File(dir, "project.json")
        lang = when (java.util.Locale.getDefault().language) {
            "ru" -> 1
            "en" -> 2
            else -> 0
        }
        try {
            val f = file
            if (f != null && f.exists()) load(f.readText())
        } catch (e: Exception) {
            lastError = L("Yuklash xatosi: ", "Ошибка загрузки: ", "Load error: ") + e.message
        }
        scope.launch(Dispatchers.IO) { senderLoop() }
    }

    private fun artDmx(seq: Int, port: Int, data: ByteArray): ByteArray {
        val p = ByteArray(18 + 512)
        val head = "Art-Net".toByteArray()
        for (i in head.indices) p[i] = head[i]
        p[8] = 0x00
        p[9] = 0x50
        p[10] = 0
        p[11] = 14
        p[12] = seq.toByte()
        p[13] = 0
        p[14] = (port and 0xFF).toByte()
        p[15] = ((port shr 8) and 0x7F).toByte()
        p[16] = 2
        p[17] = 0
        for (i in 0 until 512) p[18 + i] = data[i]
        return p
    }

    private suspend fun senderLoop() {
        var sock: DatagramSocket? = null
        var seq = 1
        while (true) {
            try {
                val s: DatagramSocket = sock ?: DatagramSocket().also {
                    it.broadcast = true
                    sock = it
                }
                val addr = InetAddress.getByName(ip.trim())
                val frames = HashMap<Int, ByteArray>()
                for (f in fixtures.toList()) frames.getOrPut(f.universe) { ByteArray(512) }
                val m = if (blackout) 0 else master
                for ((k, v) in values.entries.toList()) {
                    val u = k / 512 + 1
                    val c = k % 512
                    val buf = frames.getOrPut(u) { ByteArray(512) }
                    buf[c] = (v * m / 100).toByte()
                }
                for ((u, buf) in frames) {
                    val port = (u - 1 + u0).coerceIn(0, 32767)
                    val pkt = artDmx(seq, port, buf)
                    s.send(DatagramPacket(pkt, pkt.size, addr, 6454))
                }
                seq = if (seq >= 255) 1 else seq + 1
                packets += frames.size
                if (lastError.isNotEmpty()) lastError = ""
            } catch (e: Exception) {
                lastError = e.message ?: "xato"
                try {
                    sock?.close()
                } catch (e2: Exception) {
                }
                sock = null
            }
            delay(40)
        }
    }

    private suspend fun fadeTo(target: Map<Int, Int>, ms: Int) {
        val keys = values.keys.toList().toSet() + target.keys
        val from = keys.associateWith { values[it] ?: 0 }
        val steps = (ms / 25).coerceAtLeast(1)
        for (i in 1..steps) {
            for (k in keys) {
                val a = from[k] ?: 0
                val b = target[k] ?: 0
                values[k] = a + (b - a) * i / steps
            }
            delay(25)
        }
    }

    // ---- patch ----
    fun addFixtures(name: String, universe: Int, address: Int, channels: Int, count: Int) {
        var a = address
        var id = (fixtures.maxOfOrNull { it.id } ?: 0) + 1
        for (i in 0 until count) {
            if (a + channels - 1 > 512) break
            val nm = if (count > 1) name + " " + (i + 1) else name
            fixtures.add(Fixture(id, nm, universe, a, channels))
            id += 1
            a += channels
        }
        save()
    }

    fun nextFreeAddress(u: Int): Int {
        val end = fixtures.filter { it.universe == u }.maxOfOrNull { it.address + it.channels } ?: 1
        return end.coerceAtMost(512)
    }

    fun overlaps(f: Fixture): Boolean {
        return fixtures.any {
            it.id != f.id && it.universe == f.universe &&
                it.address < f.address + f.channels && f.address < it.address + it.channels
        }
    }

    fun removeFixture(f: Fixture) {
        fixtures.remove(f)
        for (i in 0 until f.channels) values.remove(key(f.universe, f.address + i))
        save()
    }

    // ---- scenes ----
    fun saveScene(name: String, overwrite: Int) {
        val v = HashMap<Int, Int>()
        for ((k, x) in values.entries.toList()) if (x > 0) v[k] = x
        if (overwrite >= 0 && overwrite < scenes.size) {
            scenes[overwrite] = Scene(scenes[overwrite].name, v)
        } else {
            val n = if (name.isBlank()) L("Sahna ", "Сцена ", "Scene ") + (scenes.size + 1) else name
            scenes.add(Scene(n, v))
        }
        save()
    }

    fun recall(i: Int, fadeMs: Int) {
        val s = scenes.getOrNull(i) ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch { fadeTo(s.values, fadeMs) }
    }

    fun deleteScene(i: Int) {
        stopChase()
        scenes.removeAt(i)
        for (ci in chases.indices) {
            val c = chases[ci]
            val ns = c.steps.filter { it.scene != i }.map {
                if (it.scene > i) it.copy(scene = it.scene - 1) else it
            }
            chases[ci] = c.copy(steps = ns)
        }
        save()
    }

    // ---- chases ----
    fun addChase(name: String) {
        val n = if (name.isBlank()) L("Chase ", "Чейз ", "Chase ") + (chases.size + 1) else name
        chases.add(Chase(n, emptyList()))
        save()
    }

    fun addStep(ci: Int, scene: Int, holdMs: Int, fadeMs: Int) {
        val c = chases.getOrNull(ci) ?: return
        chases[ci] = c.copy(steps = c.steps + ChaseStep(scene, holdMs, fadeMs))
        save()
    }

    fun removeStep(ci: Int, si: Int) {
        val c = chases.getOrNull(ci) ?: return
        chases[ci] = c.copy(steps = c.steps.filterIndexed { idx, _ -> idx != si })
        save()
    }

    fun removeChase(ci: Int) {
        stopChase()
        if (ci >= 0 && ci < chases.size) chases.removeAt(ci)
        save()
    }

    fun playChase(i: Int) {
        stopChase()
        val c = chases.getOrNull(i) ?: return
        if (c.steps.isEmpty()) return
        playing = i
        chaseJob = scope.launch {
            while (isActive) {
                val cur = chases.getOrNull(i) ?: break
                if (cur.steps.isEmpty()) break
                for (st in cur.steps) {
                    val sc = scenes.getOrNull(st.scene) ?: continue
                    fadeTo(sc.values, st.fadeMs)
                    delay(st.holdMs.toLong())
                }
                delay(50)
            }
        }
    }

    fun stopChase() {
        chaseJob?.cancel()
        chaseJob = null
        playing = -1
    }

    // ---- save / load ----
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("ip", ip)
        o.put("u0", u0)
        o.put("lang", lang)
        val fa = JSONArray()
        for (f in fixtures) {
            val j = JSONObject()
            j.put("id", f.id)
            j.put("name", f.name)
            j.put("u", f.universe)
            j.put("a", f.address)
            j.put("n", f.channels)
            fa.put(j)
        }
        o.put("fixtures", fa)
        val sa = JSONArray()
        for (s in scenes) {
            val j = JSONObject()
            j.put("name", s.name)
            val vs = JSONArray()
            for ((k, v) in s.values) {
                val pair = JSONArray()
                pair.put(k)
                pair.put(v)
                vs.put(pair)
            }
            j.put("vals", vs)
            sa.put(j)
        }
        o.put("scenes", sa)
        val ca = JSONArray()
        for (c in chases) {
            val j = JSONObject()
            j.put("name", c.name)
            val st = JSONArray()
            for (s in c.steps) {
                val t = JSONArray()
                t.put(s.scene)
                t.put(s.holdMs)
                t.put(s.fadeMs)
                st.put(t)
            }
            j.put("steps", st)
            ca.put(j)
        }
        o.put("chases", ca)
        return o
    }

    fun load(text: String) {
        val o = JSONObject(text)
        val newFixtures = ArrayList<Fixture>()
        val newScenes = ArrayList<Scene>()
        val newChases = ArrayList<Chase>()
        val fa = o.optJSONArray("fixtures") ?: JSONArray()
        for (i in 0 until fa.length()) {
            val f = fa.getJSONObject(i)
            newFixtures.add(Fixture(f.getInt("id"), f.optString("name"), f.getInt("u"), f.getInt("a"), f.getInt("n")))
        }
        val sa = o.optJSONArray("scenes") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val j = sa.getJSONObject(i)
            val vs = j.optJSONArray("vals") ?: JSONArray()
            val m = HashMap<Int, Int>()
            for (x in 0 until vs.length()) {
                val pair = vs.getJSONArray(x)
                m[pair.getInt(0)] = pair.getInt(1)
            }
            newScenes.add(Scene(j.optString("name"), m))
        }
        val ca = o.optJSONArray("chases") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val j = ca.getJSONObject(i)
            val st = j.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<ChaseStep>()
            for (x in 0 until st.length()) {
                val t = st.getJSONArray(x)
                steps.add(ChaseStep(t.getInt(0), t.getInt(1), t.getInt(2)))
            }
            newChases.add(Chase(j.optString("name"), steps))
        }
        stopChase()
        ip = o.optString("ip", "255.255.255.255")
        u0 = o.optInt("u0", 0)
        lang = o.optInt("lang", lang)
        fixtures.clear()
        fixtures.addAll(newFixtures)
        scenes.clear()
        scenes.addAll(newScenes)
        chases.clear()
        chases.addAll(newChases)
    }

    fun save() {
        try {
            file?.writeText(toJson().toString())
        } catch (e: Exception) {
            lastError = L("Saqlash xatosi: ", "Ошибка сохранения: ", "Save error: ") + e.message
        }
    }
}
