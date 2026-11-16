package com.example.dmxstudio

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

// RDM device found through an Art-Net node
data class RdmDev(
    val uid: String,
    val raw: ByteArray,
    val nodeIp: String,
    val portAddr: Int,
    val model: Int = -1,
    val label: String = "",
    val footprint: Int = -1,
    val start: Int = -1,
    val personality: String = ""
)

// RDM over Art-Net (ArtTodControl / ArtTodRequest / ArtTodData / ArtRdm), QuickPlay-like addressing
object Rdm {
    private val src = byteArrayOf(0x7F, 0xF0.toByte(), 0, 0, 0, 1) // prototype manufacturer range
    private var tn = 0
    private val lock = Mutex()

    const val PID_DEVICE_INFO = 0x0060
    const val PID_MODEL_DESC = 0x0080
    const val PID_DEVICE_LABEL = 0x0082
    const val PID_START_ADDRESS = 0x00F0
    const val PID_IDENTIFY = 0x1000

    fun uidString(b: ByteArray): String {
        val sb = StringBuilder()
        for (i in 0 until 6) {
            if (i == 2) sb.append(':')
            sb.append(String.format("%02X", b[i].toInt() and 255))
        }
        return sb.toString()
    }

    fun portAddress(universe: Int): Int = (universe - 1 + Studio.u0).coerceIn(0, 32767)

    private fun artHeader(p: ByteArray, op: Int) {
        val head = "Art-Net".toByteArray()
        for (i in head.indices) p[i] = head[i]
        p[8] = (op and 255).toByte()
        p[9] = (op shr 8).toByte()
        p[10] = 0
        p[11] = 14
    }

    private fun openSocket(): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(6454))
        s.broadcast = true
        s.soTimeout = 300
        return s
    }

    private fun <T> withNet(ctx: Context, body: (DatagramSocket) -> T): T {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ml = wm?.createMulticastLock("dmxstudio-rdm")
        var s: DatagramSocket? = null
        try {
            ml?.setReferenceCounted(false)
            ml?.acquire()
            val sock = openSocket()
            s = sock
            return body(sock)
        } finally {
            try { s?.close() } catch (e: Exception) { }
            try { ml?.release() } catch (e: Exception) { }
        }
    }

    // Full RDM discovery on one universe. ip = node IP or broadcast.
    suspend fun scan(ctx: Context, ip: String, universe: Int, onStatus: (String) -> Unit): List<RdmDev> = lock.withLock {
        withNet(ctx) { s ->
            val pa = portAddress(universe)
            val dest = InetAddress.getByName(ip.trim())
            // ArtTodControl, AtcFlush: forces the node to run full discovery
            val ctl = ByteArray(24)
            artHeader(ctl, 0x8200)
            ctl[21] = ((pa shr 8) and 0x7F).toByte()
            ctl[22] = 0x01
            ctl[23] = (pa and 0xFF).toByte()
            s.send(DatagramPacket(ctl, ctl.size, dest, 6454))
            onStatus(L("Discovery ishlamoqda...", "Идёт discovery...", "Discovery running..."))
            val found = LinkedHashMap<String, RdmDev>()
            val buf = ByteArray(1500)
            fun listen(ms: Long) {
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) {
                    try {
                        val pk = DatagramPacket(buf, buf.size)
                        s.receive(pk)
                        if (pk.length < 28) continue
                        if (buf[8].toInt() != 0x00 || (buf[9].toInt() and 255) != 0x81) continue
                        val net = buf[21].toInt() and 0x7F
                        val addr = buf[23].toInt() and 0xFF
                        if ((net shl 8) or addr != pa) continue
                        val cnt = buf[27].toInt() and 255
                        val nodeIp = pk.address.hostAddress ?: ip
                        for (k in 0 until cnt) {
                            val o = 28 + k * 6
                            if (o + 6 > pk.length) break
                            val raw = buf.copyOfRange(o, o + 6)
                            val u = uidString(raw)
                            if (!found.containsKey(u)) found[u] = RdmDev(u, raw, nodeIp, pa)
                        }
                    } catch (e: SocketTimeoutException) {
                    }
                }
            }
            listen(2500)
            // ArtTodRequest
            val req = ByteArray(56)
            artHeader(req, 0x8000)
            req[21] = ((pa shr 8) and 0x7F).toByte()
            req[22] = 0 // TodFull
            req[23] = 1 // AdCount
            req[24] = (pa and 0xFF).toByte()
            for (round in 0 until 2) {
                s.send(DatagramPacket(req, req.size, dest, 6454))
                listen(2000)
            }
            onStatus(L("Topildi: ", "Найдено: ", "Found: ") + found.size + ". " + L("Ma'lumot o'qilmoqda...", "Чтение данных...", "Reading info..."))
            val out = ArrayList<RdmDev>()
            for (d in found.values) out.add(readInfo(s, d))
            out
        }
    }

    private fun readInfo(s: DatagramSocket, d0: RdmDev): RdmDev {
        var d = d0
        val info = request(s, d, 0x20, PID_DEVICE_INFO, ByteArray(0))
        if (info != null && info.size >= 19) {
            val model = ((info[2].toInt() and 255) shl 8) or (info[3].toInt() and 255)
            val fp = ((info[10].toInt() and 255) shl 8) or (info[11].toInt() and 255)
            val pers = (info[12].toInt() and 255).toString() + "/" + (info[13].toInt() and 255)
            val st = ((info[14].toInt() and 255) shl 8) or (info[15].toInt() and 255)
            d = d.copy(model = model, footprint = fp, start = st, personality = pers)
        }
        val lab = request(s, d, 0x20, PID_DEVICE_LABEL, ByteArray(0))
            ?: request(s, d, 0x20, PID_MODEL_DESC, ByteArray(0))
        if (lab != null) d = d.copy(label = String(lab, Charsets.US_ASCII).trim('\u0000', ' '))
        return d
    }

    private fun rdmPacket(dest: ByteArray, cc: Int, pid: Int, pd: ByteArray): ByteArray {
        val len = 24 + pd.size
        val p = ByteArray(len + 2)
        p[0] = 0xCC.toByte()
        p[1] = 0x01
        p[2] = len.toByte()
        for (i in 0 until 6) p[3 + i] = dest[i]
        for (i in 0 until 6) p[9 + i] = src[i]
        tn = (tn + 1) and 255
        p[15] = tn.toByte()
        p[16] = 1
        p[17] = 0
        p[18] = 0
        p[19] = 0
        p[20] = cc.toByte()
        p[21] = (pid shr 8).toByte()
        p[22] = (pid and 255).toByte()
        p[23] = pd.size.toByte()
        for (i in pd.indices) p[24 + i] = pd[i]
        var sum = 0
        for (i in 0 until len) sum += p[i].toInt() and 255
        p[len] = ((sum shr 8) and 255).toByte()
        p[len + 1] = (sum and 255).toByte()
        return p
    }

    // Sends one RDM request, returns the parameter data of an ACK or null
    private fun request(s: DatagramSocket, d: RdmDev, cc: Int, pid: Int, pd: ByteArray): ByteArray? {
        val dest = InetAddress.getByName(d.nodeIp)
        val buf = ByteArray(1500)
        for (attempt in 0 until 3) {
            val rdm = rdmPacket(d.raw, cc, pid, pd)
            val myTn = rdm[15]
            val pk = ByteArray(24 + rdm.size - 1)
            artHeader(pk, 0x8300)
            pk[12] = 1
            pk[21] = ((d.portAddr shr 8) and 0x7F).toByte()
            pk[22] = 0
            pk[23] = (d.portAddr and 0xFF).toByte()
            for (i in 1 until rdm.size) pk[24 + i - 1] = rdm[i]
            s.send(DatagramPacket(pk, pk.size, dest, 6454))
            val end = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < end) {
                try {
                    val r = DatagramPacket(buf, buf.size)
                    s.receive(r)
                    if (r.length < 24 + 23) continue
                    if (buf[8].toInt() != 0x00 || (buf[9].toInt() and 255) != 0x83) continue
                    var o = 24
                    if ((buf[o].toInt() and 255) == 0xCC) o += 1
                    // o -> sub start code
                    if (buf[o].toInt() != 0x01) continue
                    // source UID of response must be the device
                    var same = true
                    for (i in 0 until 6) if (buf[o + 8 + i] != d.raw[i]) same = false
                    if (!same) continue
                    val rtn = buf[o + 14]
                    val respType = buf[o + 15].toInt() and 255
                    val rcc = buf[o + 19].toInt() and 255
                    val rpid = ((buf[o + 20].toInt() and 255) shl 8) or (buf[o + 21].toInt() and 255)
                    if (rpid != pid || rcc != cc + 1) continue
                    if (rtn != myTn) continue
                    if (respType != 0) return null
                    val pdl = buf[o + 22].toInt() and 255
                    val start = o + 23
                    if (start + pdl > r.length) return null
                    return buf.copyOfRange(start, start + pdl)
                } catch (e: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    suspend fun identify(ctx: Context, d: RdmDev, on: Boolean): Boolean = lock.withLock {
        withNet(ctx) { s -> request(s, d, 0x30, PID_IDENTIFY, byteArrayOf(if (on) 1 else 0)) != null }
    }

    suspend fun setAddress(ctx: Context, d: RdmDev, addr: Int): Boolean = lock.withLock {
        withNet(ctx) { s ->
            request(s, d, 0x30, PID_START_ADDRESS, byteArrayOf((addr shr 8).toByte(), (addr and 255).toByte())) != null
        }
    }

    suspend fun setLabel(ctx: Context, d: RdmDev, label: String): Boolean = lock.withLock {
        withNet(ctx) { s ->
            request(s, d, 0x30, PID_DEVICE_LABEL, label.take(32).toByteArray(Charsets.US_ASCII)) != null
        }
    }

    suspend fun refresh(ctx: Context, d: RdmDev): RdmDev = lock.withLock {
        withNet(ctx) { s -> readInfo(s, d) }
    }
}
