package com.example.dmxstudio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

fun L(uz: String, ru: String, en: String): String = when (Studio.lang) {
    1 -> ru
    2 -> en
    else -> uz
}

data class Pal(val uz: String, val ru: String, val en: String, val r: Int, val g: Int, val b: Int, val w: Int)
data class NodeInfo(val ip: String, val name: String)

val PALETTE = listOf(
    Pal("Qizil", "Красный", "Red", 255, 0, 0, 0),
    Pal("Yashil", "Зелёный", "Green", 0, 255, 0, 0),
    Pal("Ko'k", "Синий", "Blue", 0, 0, 255, 0),
    Pal("Oq", "Белый", "White", 0, 0, 0, 255),
    Pal("Sariq", "Жёлтый", "Yellow", 255, 255, 0, 0),
    Pal("Moviy", "Голубой", "Cyan", 0, 255, 255, 0),
    Pal("Pushti", "Пурпурный", "Magenta", 255, 0, 255, 0)
)

data class Fixture(val id: Int, val name: String, val universe: Int, val address: Int, val channels: Int, val x: Float = 0f, val y: Float = 0f)
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
    var protocol by mutableStateOf(0)
    var wifiOnly by mutableStateOf(true)
    var background by mutableStateOf(false)
    var backgroundWanted by mutableStateOf(false)
    var wifiName by mutableStateOf("")
    @Volatile var wifiNet: Network? = null
    @Volatile var outFrame: Map<Int, Int> = emptyMap()

    var testing by mutableStateOf(false)
    var testEffect by mutableStateOf(1)
    var testColor by mutableStateOf(3)
    var testLevel by mutableStateOf(100)
    var testSpeed by mutableStateOf(500)
    var testUniverse by mutableStateOf(0)
    var testColumns by mutableStateOf(true)
    @Volatile private var testFrame: Map<Int, Int> = emptyMap()
    private var testJob: Job? = null
    private var identJob: Job? = null

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

    fun start(ctx: Context) {
        if (started) return
        started = true
        file = File(ctx.filesDir, "project.json")
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
        Engine.start()
        watchWifi(ctx)
        scope.launch(Dispatchers.IO) { senderLoop() }
    }

    // Keep Art-Net on Wi-Fi even when Wi-Fi has no internet and mobile data is on
    private fun watchWifi(ctx: Context) {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifiNet = network
                    wifiName = "Wi-Fi"
                }

                override fun onLost(network: Network) {
                    if (wifiNet == network) {
                        wifiNet = null
                        wifiName = ""
                    }
                }
            })
        } catch (e: Exception) {
        }
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

    private val cid = ByteArray(16) { (it * 7 + 3).toByte() }

    private fun sacn(seq: Int, universe: Int, data: ByteArray): ByteArray {
        val p = ByteArray(638)
        p[1] = 0x10
        val pid = "ASC-E1.17".toByteArray()
        for (i in pid.indices) p[4 + i] = pid[i]
        p[16] = 0x72
        p[17] = 0x6E
        p[21] = 4
        for (i in 0 until 16) p[22 + i] = cid[i]
        p[38] = 0x72
        p[39] = 0x58
        p[43] = 2
        val name = "DMX Studio".toByteArray()
        for (i in name.indices) p[44 + i] = name[i]
        p[108] = 100
        p[111] = seq.toByte()
        p[113] = (universe shr 8).toByte()
        p[114] = (universe and 0xFF).toByte()
        p[115] = 0x72
        p[116] = 0x0B
        p[117] = 2
        p[118] = 0xA1.toByte()
        p[122] = 1
        p[123] = 0x02
        p[124] = 0x01
        for (i in 0 until 512) p[126 + i] = data[i]
        return p
    }

    private suspend fun senderLoop() {
        var sock: DatagramSocket? = null
        var bound: Network? = null
        var seq = 1
        while (true) {
            try {
                val want = if (wifiOnly) wifiNet else null
                if (sock != null && bound != want) {
                    try {
                        sock?.close()
                    } catch (e: Exception) {
                    }
                    sock = null
                }
                val s: DatagramSocket = sock ?: DatagramSocket().also {
                    it.broadcast = true
                    if (want != null) {
                        try {
                            want.bindSocket(it)
                        } catch (e: Exception) {
                        }
                    }
                    bound = want
                    sock = it
                }
                val addr = InetAddress.getByName(ip.trim())
                val frames = HashMap<Int, ByteArray>()
                for (f in fixtures.toList()) frames.getOrPut(f.universe) { ByteArray(512) }
                val m = if (blackout) 0 else master
                val src: Map<Int, Int> = if (testing) testFrame else {
                    val mm = HashMap(values)
                    val ef = Engine.effectFrame
                    if (ef.isNotEmpty()) mm.putAll(ef)
                    mm
                }
                outFrame = src
                val ipStr = ip.trim()
                val bcast = ipStr == "255.255.255.255" || ipStr.endsWith(".255")
                for ((k, v) in src) {
                    val u = k / 512 + 1
                    val c = k % 512
                    val buf = frames.getOrPut(u) { ByteArray(512) }
                    buf[c] = (v * m / 100).toByte()
                }
                for ((u, buf) in frames) {
                    if (protocol == 1) {
                        val su = u.coerceIn(1, 63999)
                        val pkt = sacn(seq, su, buf)
                        val dest = if (bcast) InetAddress.getByName("239.255." + (su shr 8) + "." + (su and 255)) else addr
                        s.send(DatagramPacket(pkt, pkt.size, dest, 5568))
                    } else {
                        val port = (u - 1 + u0).coerceIn(0, 32767)
                        val pkt = artDmx(seq, port, buf)
                        s.send(DatagramPacket(pkt, pkt.size, addr, 6454))
                    }
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

    // ---- colors ----
    fun colorValues(f: Fixture, p: Pal, level: Int, columns: Boolean): IntArray {
        val out = IntArray(f.channels)
        val isWhite = p.w > 0 && p.r + p.g + p.b == 0
        if (f.channels >= 4) {
            out[0] = p.r * level / 100
            out[1] = p.g * level / 100
            out[2] = p.b * level / 100
            out[3] = p.w * level / 100
        } else if (f.channels == 3) {
            val r = if (isWhite) 255 else p.r
            val g = if (isWhite) 255 else p.g
            val b = if (isWhite) 255 else p.b
            out[0] = r * level / 100
            out[1] = g * level / 100
            out[2] = b * level / 100
        } else if (f.channels == 1) {
            if (isWhite && columns) out[0] = 255 * level / 100
        }
        return out
    }

    fun setColor(f: Fixture, p: Pal) {
        val cv = colorValues(f, p, 100, true)
        for (i in cv.indices) set(f.universe, f.address + i, cv[i])
    }

    fun setColorAll(p: Pal) {
        for (f in fixtures.toList()) setColor(f, p)
    }

    // ---- test mode ----
    fun startTest() {
        stopTest()
        testing = true
        testJob = scope.launch {
            var tick = 0
            while (isActive) {
                val fx = fixtures.toList()
                    .filter { testUniverse == 0 || it.universe == testUniverse }
                    .sortedWith(compareBy({ it.universe }, { it.address }))
                val n = fx.size
                val frame = HashMap<Int, Int>()
                val pal = if (testEffect == 3) PALETTE[tick % PALETTE.size]
                else PALETTE[testColor.coerceIn(0, PALETTE.size - 1)]
                for ((i, f) in fx.withIndex()) {
                    val on = when (testEffect) {
                        0 -> tick % 2 == 0
                        1 -> i == tick % n
                        2 -> i < tick % (n + 1)
                        else -> true
                    }
                    if (on) {
                        val cv = colorValues(f, pal, testLevel, testColumns)
                        for (c in cv.indices) frame[key(f.universe, f.address + c)] = cv[c]
                    }
                }
                testFrame = frame
                tick += 1
                delay(testSpeed.toLong().coerceAtLeast(50))
            }
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
        identJob?.cancel()
        identJob = null
        testFrame = emptyMap()
        testing = false
    }

    fun identify(f: Fixture) {
        if (testJob != null) return
        identJob?.cancel()
        identJob = scope.launch {
            testing = true
            for (i in 0 until 8) {
                val frame = HashMap<Int, Int>()
                if (i % 2 == 0) {
                    val cv = colorValues(f, PALETTE[3], 100, true)
                    for (c in cv.indices) frame[key(f.universe, f.address + c)] = cv[c]
                }
                testFrame = frame
                delay(300)
            }
            testFrame = emptyMap()
            testing = false
        }
    }

    // ---- network scan (ArtPoll) ----
    fun discover(ctx: Context): List<NodeInfo> {
        val res = LinkedHashMap<String, NodeInfo>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wm?.createMulticastLock("dmxstudio")
        var s: DatagramSocket? = null
        try {
            lock?.setReferenceCounted(false)
            lock?.acquire()
            val sock = DatagramSocket(null)
            s = sock
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(6454))
            sock.broadcast = true
            val wn = wifiNet
            if (wifiOnly && wn != null) {
                try {
                    wn.bindSocket(sock)
                } catch (e: Exception) {
                }
            }
            sock.soTimeout = 400
            val poll = ByteArray(14)
            val head = "Art-Net".toByteArray()
            for (i in head.indices) poll[i] = head[i]
            poll[9] = 0x20
            poll[11] = 14
            sock.send(DatagramPacket(poll, poll.size, InetAddress.getByName("255.255.255.255"), 6454))
            val end = System.currentTimeMillis() + 2500
            val buf = ByteArray(600)
            while (System.currentTimeMillis() < end) {
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    sock.receive(pk)
                    if (pk.length >= 44 && buf[8].toInt() == 0x00 && buf[9].toInt() == 0x21) {
                        val ipA = pk.address.hostAddress ?: continue
                        val nm = String(buf, 26, 18).split('\u0000')[0].trim()
                        res[ipA] = NodeInfo(ipA, nm)
                    }
                } catch (e: SocketTimeoutException) {
                }
            }
        } finally {
            try {
                s?.close()
            } catch (e: Exception) {
            }
            try {
                lock?.release()
            } catch (e: Exception) {
            }
        }
        return res.values.toList()
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

    fun updateFixture(f: Fixture) {
        val i = fixtures.indexOfFirst { it.id == f.id }
        if (i < 0) return
        val old = fixtures[i]
        for (c in 0 until old.channels) values.remove(key(old.universe, old.address + c))
        fixtures[i] = f.copy(x = old.x, y = old.y)
        save()
    }

    // QuickPlay-style: give fixtures of one universe consecutive addresses from 1
    fun renumber(u: Int): Int {
        var a = 1
        var n = 0
        val list = fixtures.withIndex().filter { it.value.universe == u }.sortedBy { it.value.address }
        for ((i, f) in list) {
            if (a + f.channels - 1 > 512) break
            fixtures[i] = f.copy(address = a)
            a += f.channels
            n += 1
        }
        values.clear()
        save()
        return n
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
        Engine.fixRefs(1, i)
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
        o.put("proto", protocol)
        o.put("wifiOnly", wifiOnly)
        o.put("bg", backgroundWanted)
        val fa = JSONArray()
        for (f in fixtures) {
            val j = JSONObject()
            j.put("id", f.id)
            j.put("name", f.name)
            j.put("u", f.universe)
            j.put("a", f.address)
            j.put("n", f.channels)
            if (f.x != 0f || f.y != 0f) {
                j.put("x", f.x.toDouble())
                j.put("y", f.y.toDouble())
            }
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
        Engine.toJson(o)
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
            newFixtures.add(Fixture(f.getInt("id"), f.optString("name"), f.getInt("u"), f.getInt("a"), f.getInt("n"), f.optDouble("x", 0.0).toFloat(), f.optDouble("y", 0.0).toFloat()))
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
        protocol = o.optInt("proto", 0)
        wifiOnly = o.optBoolean("wifiOnly", true)
        backgroundWanted = o.optBoolean("bg", backgroundWanted)
        fixtures.clear()
        fixtures.addAll(newFixtures)
        scenes.clear()
        scenes.addAll(newScenes)
        chases.clear()
        chases.addAll(newChases)
        values.clear()
        Engine.load(o)
        if (fixtures.isNotEmpty() && fixtures.all { it.x == 0f && it.y == 0f }) {
            if (!Engine.layoutFacade()) Engine.layoutGrid()
        }
    }

    fun save() {
        try {
            file?.writeText(toJson().toString())
        } catch (e: Exception) {
            lastError = L("Saqlash xatosi: ", "Ошибка сохранения: ", "Save error: ") + e.message
        }
    }
}
