package com.example.dmxstudio

// Project "Marshala Zhukova 11", korpus 1-3 (albums 391/392/393-Ж-АСФ).
// Controller K1 = RGBW fixtures (4 ch) on the roof crown, K2/K3 = 1-channel white column fixtures on the facade.
data class ZFix(
    val building: Int,
    val controller: Int,
    val universe: Int,
    val side: Int,        // 0 А, 1 Б, 2 В, 3 Г
    val no: Int,
    val address: Int,
    val channels: Int,
    val name: String
) {
    // Universe inside DMX Studio: korpus, controller and universe packed together
    val studioUniverse: Int get() = Zhukova.studioUniverse(building, controller, universe)
}

object Zhukova {
    val SIDES = listOf("А", "Б", "В", "Г")

    private data class R(
        val b: Int, val c: Int, val u: Int, val side: String,
        val from: Int, val to: Int, val uniFirst: Int, val ch: Int
    )

    private val ranges = listOf(
        // ===== Корпус 1 =====
        R(1, 1, 1, "А", 1, 18, 1, 4),
        R(1, 1, 2, "А", 19, 36, 19, 4),
        R(1, 1, 3, "Б", 37, 60, 37, 4),
        R(1, 1, 4, "В", 61, 81, 61, 4),
        R(1, 1, 5, "Г", 82, 99, 82, 4),
        R(1, 2, 1, "А", 1, 32, 1, 1),
        R(1, 2, 2, "А", 33, 58, 33, 1),
        R(1, 2, 3, "А", 59, 78, 59, 1),
        R(1, 2, 3, "Б", 79, 84, 59, 1),
        R(1, 2, 4, "Б", 85, 110, 85, 1),
        R(1, 2, 5, "Б", 111, 130, 111, 1),
        R(1, 2, 5, "В", 131, 136, 111, 1),
        R(1, 2, 6, "В", 137, 162, 137, 1),
        R(1, 2, 7, "В", 163, 176, 163, 1),
        R(1, 2, 7, "Г", 177, 189, 163, 1),
        R(1, 2, 8, "Г", 190, 215, 190, 1),
        // ===== Корпус 2 =====
        R(2, 1, 1, "А", 1, 15, 1, 4),
        R(2, 1, 2, "А", 16, 33, 16, 4),
        R(2, 1, 3, "Б", 34, 57, 34, 4),
        R(2, 1, 4, "В", 58, 84, 58, 4),
        R(2, 1, 5, "Г", 85, 102, 85, 4),
        R(2, 2, 1, "А", 1, 31, 1, 1),
        R(2, 2, 2, "А", 32, 62, 32, 1),
        R(2, 2, 3, "А", 63, 85, 63, 1),
        R(2, 2, 3, "Б", 86, 93, 63, 1),
        R(2, 2, 4, "Б", 94, 124, 94, 1),
        R(2, 2, 5, "Б", 125, 147, 125, 1),
        R(2, 2, 5, "В", 148, 155, 125, 1),
        R(2, 2, 6, "В", 156, 186, 156, 1),
        R(2, 2, 7, "В", 187, 217, 187, 1),
        R(2, 3, 1, "Г", 218, 248, 218, 1),
        R(2, 3, 2, "Г", 249, 264, 249, 1),
        // ===== Корпус 3 =====
        R(3, 1, 1, "А", 1, 15, 1, 4),
        R(3, 1, 2, "Б", 16, 42, 16, 4),
        R(3, 1, 3, "В", 43, 66, 43, 4),
        R(3, 1, 4, "Г", 67, 81, 67, 4),
        R(3, 1, 5, "Г", 82, 99, 82, 4),
        R(3, 2, 1, "А", 1, 26, 1, 1),
        R(3, 2, 2, "Б", 27, 52, 27, 1),
        R(3, 2, 3, "Б", 53, 73, 53, 1),
        R(3, 2, 4, "В", 74, 94, 74, 1),
        R(3, 2, 5, "В", 95, 115, 95, 1),
        R(3, 2, 5, "Г", 116, 120, 95, 1),
        R(3, 2, 6, "Г", 121, 147, 121, 1),
        R(3, 2, 7, "Г", 148, 173, 148, 1)
    )

    val controllers: Map<Int, Int> = mapOf(1 to 2, 2 to 3, 3 to 2)

    fun studioUniverse(b: Int, c: Int, u: Int): Int = (b - 1) * 30 + (c - 1) * 10 + u

    val fixtures: List<ZFix> = ranges.flatMap { r ->
        (r.from..r.to).map { n ->
            val num = n.toString().padStart(3, '0')
            ZFix(
                building = r.b,
                controller = r.c,
                universe = r.u,
                side = SIDES.indexOf(r.side),
                no = n,
                address = (n - r.uniFirst) * r.ch + 1,
                channels = r.ch,
                name = "K" + r.b + " " + r.u + ".К" + r.c + "." + r.side + "." + num
            )
        }
    }

    fun forBuilding(b: Int): List<ZFix> = fixtures.filter { it.building == b }

    // Schematic placement on a side: u = 0..1 across the side, h = 0..1 height of the tower.
    // K1 (RGBW) sit on the roof crown, K2/K3 columns are spread in vertical lines over the facade.
    class Place(val f: ZFix, val u: Float, val h: Float)

    private val placeCache = HashMap<Int, List<Place>>()

    fun places(b: Int): List<Place> = placeCache.getOrPut(b) {
        val out = ArrayList<Place>()
        val fx = forBuilding(b)
        for (side in 0 until 4) {
            val crown = fx.filter { it.side == side && it.channels >= 3 }.sortedBy { it.no }
            for ((i, f) in crown.withIndex()) {
                val u = (i + 0.5f) / crown.size
                out.add(Place(f, u, 0.965f))
            }
            val cols = fx.filter { it.side == side && it.channels < 3 }.sortedWith(compareBy({ it.controller }, { it.no }))
            val n = cols.size
            if (n == 0) continue
            val lines = maxOf(1, Math.round(n / 5.2f))
            val per = (n + lines - 1) / lines
            for ((i, f) in cols.withIndex()) {
                val line = i / per
                val k = i % per
                val u = (line + 0.5f) / lines
                val step = 0.42f / per
                val stagger = step * (line % 3) / 3f
                val h = 0.88f - k * step - stagger
                out.add(Place(f, u, h))
            }
        }
        out
    }

    // Brightness/color of a project fixture from the last output frame
    fun outRgb(f: ZFix): Int {
        val buf = Studio.outFrames[f.studioUniverse] ?: return 0
        fun at(i: Int): Int {
            val a = f.address - 1 + i
            return if (a in 0..511) buf[a].toInt() and 255 else 0
        }
        return if (f.channels >= 4) {
            val w = at(3)
            rgbOf(minOf(255, at(0) + w), minOf(255, at(1) + w), minOf(255, at(2) + w))
        } else {
            val v = at(0)
            rgbOf(v, v * 235 / 255, v * 200 / 255) // warm white
        }
    }

    fun isPatched(b: Int): Boolean {
        val us = forBuilding(b).map { it.studioUniverse }.toSet()
        return Studio.fixtures.any { it.universe in us }
    }

    // Adds the korpus to the Patch (replaces fixtures on its universes) and lays it out on the Map
    fun importBuilding(b: Int) {
        val fx = forBuilding(b)
        val us = fx.map { it.studioUniverse }.toSet()
        Studio.fixtures.removeAll { it.universe in us }
        var id = (Studio.fixtures.maxOfOrNull { it.id } ?: 0) + 1
        for (p in places(b)) {
            val f = p.f
            val x = ((b - 1) * 4 + f.side + p.u) / 12f
            val y = 1f - p.h
            Studio.fixtures.add(Fixture(id, f.name, f.studioUniverse, f.address, f.channels, x, y))
            id += 1
        }
        // Art-Net universe of each controller output
        for (f in fx) {
            val su = f.studioUniverse
            val old = Studio.routes[su]
            Studio.routes[su] = Route(old?.ip ?: "", f.universe)
        }
        Studio.save()
    }

    fun controllerIp(b: Int, c: Int): String {
        val u = forBuilding(b).firstOrNull { it.controller == c }?.studioUniverse ?: return ""
        return Studio.routes[u]?.ip ?: ""
    }

    fun setControllerIp(b: Int, c: Int, ip: String) {
        for (f in forBuilding(b).filter { it.controller == c }) {
            val su = f.studioUniverse
            Studio.routes[su] = Route(ip, Studio.routes[su]?.port ?: f.universe)
        }
        Studio.save()
    }
}
