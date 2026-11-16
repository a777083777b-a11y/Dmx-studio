package com.example.dmxstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private const val TOWER_H = 4.2f

// Corner points of each side (x, z), going round the tower: А front, Б right, В back, Г left
private val SIDE_START = listOf(Pair(-0.5f, 0.5f), Pair(0.5f, 0.5f), Pair(0.5f, -0.5f), Pair(-0.5f, -0.5f))
private val SIDE_END = listOf(Pair(0.5f, 0.5f), Pair(0.5f, -0.5f), Pair(-0.5f, -0.5f), Pair(-0.5f, 0.5f))
private val SIDE_NORMAL = listOf(Pair(0f, 1f), Pair(1f, 0f), Pair(0f, -1f), Pair(-1f, 0f))

class Cam {
    var yaw by mutableStateOf(-0.6f)
    var pitch by mutableStateOf(0.18f)
    var zoom by mutableStateOf(1f)
}

private class Proj(val sx: Float, val sy: Float, val depth: Float)

private fun project(cam: Cam, w: Float, h: Float, x: Float, y: Float, z: Float): Proj {
    // rotate around Y (yaw)
    val cy = cos(cam.yaw)
    val sy = sin(cam.yaw)
    val x1 = x * cy + z * sy
    val z1 = -x * sy + z * cy
    val y0 = y - TOWER_H / 2f
    // rotate around X (pitch)
    val cp = cos(cam.pitch)
    val sp = sin(cam.pitch)
    val y2 = y0 * cp - z1 * sp
    val z2 = y0 * sp + z1 * cp
    val dist = 9f
    val f = minOf(h * 0.92f / TOWER_H, w * 0.9f / 2.2f) * dist * cam.zoom
    val k = f / (dist - z2)
    return Proj(w / 2f + x1 * k, h / 2f - y2 * k, z2)
}

// Is a side facing the camera?
private fun facing(cam: Cam, side: Int): Boolean {
    val (nx, nz) = SIDE_NORMAL[side]
    val z1 = -nx * sin(cam.yaw) + nz * cos(cam.yaw)
    return z1 * cos(cam.pitch) > -0.02f
}

private fun pointOn(side: Int, u: Float, hh: Float, out: Float = 0.02f): Triple<Float, Float, Float> {
    val (x0, z0) = SIDE_START[side]
    val (x1, z1) = SIDE_END[side]
    val (nx, nz) = SIDE_NORMAL[side]
    return Triple(x0 + (x1 - x0) * u + nx * out, hh * TOWER_H, z0 + (z1 - z0) * u + nz * out)
}

private class Hit(val f: ZFix, val x: Float, val y: Float)

private class HitStore {
    var list: List<Hit> = emptyList()
}

private fun DrawScope.drawTower(cam: Cam, b: Int, sel: ZFix?, hits: HitStore) {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float, z: Float) = project(cam, w, h, x, y, z)

    // ground shadow
    val g = p(0f, 0f, 0f)
    drawOval(
        Brush.radialGradient(listOf(Color(0x55000000), Color.Transparent), Offset(g.sx, g.sy), w * 0.25f),
        topLeft = Offset(g.sx - w * 0.25f, g.sy - w * 0.06f),
        size = androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.12f)
    )

    // faces, far ones first
    val order = (0 until 4).sortedBy { s ->
        val (nx, nz) = SIDE_NORMAL[s]
        p(nx * 0.5f, TOWER_H / 2f, nz * 0.5f).depth
    }
    for (s in order) {
        if (!facing(cam, s)) continue
        val (x0, z0) = SIDE_START[s]
        val (x1, z1) = SIDE_END[s]
        val a = p(x0, 0f, z0)
        val bb = p(x1, 0f, z1)
        val c = p(x1, TOWER_H, z1)
        val d = p(x0, TOWER_H, z0)
        val path = Path().apply {
            moveTo(a.sx, a.sy); lineTo(bb.sx, bb.sy); lineTo(c.sx, c.sy); lineTo(d.sx, d.sy); close()
        }
        val shade = if (s % 2 == 0) Color(0xFF2A3038) else Color(0xFF222830)
        drawPath(path, shade)
        // floors
        for (fl in 1 until 48) {
            val hh = fl / 48f * TOWER_H
            val l = p(x0, hh, z0)
            val r = p(x1, hh, z1)
            drawLine(Color(0x22FFFFFF), Offset(l.sx, l.sy), Offset(r.sx, r.sy), 1f)
        }
        // window columns
        for (wc in 1 until 8) {
            val u = wc / 8f
            val lo = p(x0 + (x1 - x0) * u, 0f, z0 + (z1 - z0) * u)
            val hi = p(x0 + (x1 - x0) * u, TOWER_H * 0.93f, z0 + (z1 - z0) * u)
            drawLine(Color(0x18FFFFFF), Offset(lo.sx, lo.sy), Offset(hi.sx, hi.sy), 1f)
        }
        drawPath(path, Color(0x55FFFFFF), style = Stroke(1.5f))
        // side letter at the base
        val lb = p(x0 + (x1 - x0) * 0.5f, -0.15f, z0 + (z1 - z0) * 0.5f)
        drawContext.canvas.nativeCanvas.drawText(
            Zhukova.SIDES[s], lb.sx, lb.sy,
            android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 34f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
        )
    }
    // roof
    if (cam.pitch > 0f) {
        val r = (0 until 4).map { val (x, z) = SIDE_START[it]; p(x, TOWER_H, z) }
        val path = Path().apply {
            moveTo(r[0].sx, r[0].sy); for (i in 1 until 4) lineTo(r[i].sx, r[i].sy); close()
        }
        drawPath(path, Color(0xFF353C46))
        drawPath(path, Color(0x55FFFFFF), style = Stroke(1.5f))
    }

    // fixtures
    val scale = (minOf(w, h) / 900f).coerceIn(0.5f, 2f) * cam.zoom
    val newHits = ArrayList<Hit>()
    for (pl in Zhukova.places(b)) {
        val f = pl.f
        if (!facing(cam, f.side)) continue
        val (x, y, z) = pointOn(f.side, pl.u, pl.h)
        val pr = p(x, y, z)
        val c = Zhukova.outRgb(f)
        val lum = maxOf(rOf(c), gOf(c), bOf(c))
        val center = Offset(pr.sx, pr.sy)
        newHits.add(Hit(f, pr.sx, pr.sy))
        val col = cColor(c)
        if (lum > 8) {
            val k = lum / 255f
            if (f.channels < 3) {
                // column light: shines up and down
                val len = 26f * scale * (0.4f + 0.6f * k)
                drawLine(
                    Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = 0.9f), Color.Transparent), pr.sy - len, pr.sy + len),
                    Offset(pr.sx, pr.sy - len), Offset(pr.sx, pr.sy + len), 5f * scale
                )
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f * k + 0.2f), Color.Transparent), center, 14f * scale), 14f * scale, center)
                drawCircle(col, 3.2f * scale, center)
            } else {
                // crown RGBW: wide glow
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.7f), Color.Transparent), center, 22f * scale), 22f * scale, center)
                drawRect(col, Offset(pr.sx - 2.5f * scale, pr.sy - 7f * scale), androidx.compose.ui.geometry.Size(5f * scale, 14f * scale))
            }
        } else {
            if (f.channels < 3) {
                drawCircle(Color(0xFF0B0D10), 3.2f * scale, center)
                drawCircle(Color(0xFF555B63), 3.2f * scale, center, style = Stroke(1f))
            } else {
                drawRect(Color(0xFF0B0D10), Offset(pr.sx - 2.5f * scale, pr.sy - 7f * scale), androidx.compose.ui.geometry.Size(5f * scale, 14f * scale))
            }
        }
        if (sel != null && sel.name == f.name) {
            drawCircle(Color(0xFFFFEB3B), 12f * scale, center, style = Stroke(2.5f))
        }
    }
    hits.list = newHits
}

@Composable
fun ZhukovaScreen() {
    val cam = remember { Cam() }
    val hits = remember { HitStore() }
    var b by remember { mutableStateOf(3) }
    var sel by remember { mutableStateOf<ZFix?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            tick += 1
        }
    }

    val view: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.background(Brush.verticalGradient(listOf(Color(0xFF05070D), Color(0xFF141B2B))))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        cam.yaw += pan.x / 300f
                        cam.pitch = (cam.pitch + pan.y / 400f).coerceIn(-0.2f, 1.2f)
                        cam.zoom = (cam.zoom * zoom).coerceIn(0.5f, 6f)
                    }
                }
                .pointerInput(b) {
                    detectTapGestures { o ->
                        var best: ZFix? = null
                        var bd = 40f * 40f
                        for (hh in hits.list) {
                            val dx = hh.x - o.x
                            val dy = hh.y - o.y
                            val d = dx * dx + dy * dy
                            if (d < bd) {
                                bd = d
                                best = hh.f
                            }
                        }
                        sel = best
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = tick
                if (t >= 0) drawTower(cam, b, sel, hits)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                view(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { SidePanel(b, { b = it; sel = null }, sel, cam, tick) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                view(Modifier.fillMaxWidth().height(440.dp))
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { SidePanel(b, { b = it; sel = null }, sel, cam, tick) }
            }
        }
    }
}

@Composable
private fun SidePanel(b: Int, setB: (Int) -> Unit, sel: ZFix?, cam: Cam, tick: Int) {
    Text(L("Marshala Jukova 11 — fasad", "Маршала Жукова 11 — фасад", "Marshala Zhukova 11 — facade"), fontWeight = FontWeight.Bold)
    Chips(listOf(L("Korpus 1", "Корпус 1", "Bldg 1"), L("Korpus 2", "Корпус 2", "Bldg 2"), L("Korpus 3", "Корпус 3", "Bldg 3")), b - 1) { setB(it + 1) }
    val fx = Zhukova.forBuilding(b)
    val t = tick
    val lit = fx.count { maxOf(rOf(Zhukova.outRgb(it)), gOf(Zhukova.outRgb(it)), bOf(Zhukova.outRgb(it))) > 8 } + t * 0
    val crown = fx.count { it.channels >= 3 }
    Text(
        L("Yoniq: ", "Горит: ", "On: ") + lit + "   " + L("O'chiq: ", "Не горит: ", "Off: ") + (fx.size - lit) +
            "   " + L("Jami: ", "Всего: ", "Total: ") + fx.size,
        fontWeight = FontWeight.Bold
    )
    Text(
        L("Toj (RGBW, 4 kanal): ", "Корона (RGBW, 4 канала): ", "Crown (RGBW, 4 ch): ") + crown +
            "   " + L("Kolonna (oq, 1 kanal): ", "Колонны (белые, 1 канал): ", "Columns (white, 1 ch): ") + (fx.size - crown),
        fontSize = 12.sp
    )
    Text(
        L(
            "Barmoq bilan aylantiring, ikki barmoq bilan kattalashtiring. Chiroqni bossangiz ma'lumoti chiqadi.",
            "Вращайте пальцем, масштаб — двумя пальцами. Нажмите на светильник для информации.",
            "Drag to rotate, pinch to zoom. Tap a fixture for info."
        ),
        fontSize = 11.sp, color = Color.Gray
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 1, 2, 3).forEach { s ->
            OutlinedButton(onClick = {
                cam.yaw = -s * (Math.PI.toFloat() / 2f)
                cam.pitch = 0.1f
            }) { Text(Zhukova.SIDES[s]) }
        }
    }

    if (sel != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val c = Zhukova.outRgb(sel)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(cColor(c), 22)
                    Text(sel.name, fontWeight = FontWeight.Bold)
                }
                Text(
                    L("Kontroller K", "Контроллер К", "Controller K") + sel.controller + "   Universe " + sel.universe +
                        "   " + L("Adres ", "Адрес ", "Address ") + sel.address +
                        (if (sel.channels > 1) "-" + (sel.address + sel.channels - 1) else ""),
                    fontSize = 12.sp
                )
                Text(
                    if (maxOf(rOf(c), gOf(c), bOf(c)) > 8) L("Holati: YONIQ", "Состояние: ГОРИТ", "State: ON") + "  (R" + rOf(c) + " G" + gOf(c) + " B" + bOf(c) + ")"
                    else L("Holati: o'chiq", "Состояние: не горит", "State: off"),
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = {
                    Studio.fixtures.firstOrNull { it.universe == sel.studioUniverse && it.address == sel.address }?.let { Studio.identify(it) }
                }) { Text(L("Miltillatish", "Мигнуть", "Flash")) }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("Ulash (Patch va kontrollerlar)", "Подключение (Patch и контроллеры)", "Connection (Patch and controllers)"), fontWeight = FontWeight.Bold)
            val patched = Zhukova.isPatched(b)
            Text(
                if (patched) L("Bu korpus Patch'da bor.", "Этот корпус есть в Patch.", "This building is in the Patch.")
                else L("Bu korpus hali Patch'da yo'q — chiroqlar boshqarilmaydi.", "Корпуса ещё нет в Patch — приборы не управляются.", "Not in the Patch yet — fixtures are not controlled."),
                fontSize = 12.sp, color = if (patched) Color(0xFF2E7D32) else Color.Red
            )
            Button(onClick = { Zhukova.importBuilding(b) }) {
                Text(if (patched) L("Patch'ni yangilash", "Обновить Patch", "Re-import to Patch") else L("Patch'ga qo'shish", "Добавить в Patch", "Add to Patch"))
            }
            for (c in 1..(Zhukova.controllers[b] ?: 1)) {
                var ip by remember(b, c) { mutableStateOf(Zhukova.controllerIp(b, c)) }
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        Zhukova.setControllerIp(b, c, it.trim())
                    },
                    label = { Text(L("Kontroller K", "Контроллер К", "Controller K") + c + " IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                L(
                    "Universe'lar Patch'da shunday raqamlanadi: korpus 1 → 1-28, korpus 2 → 31-58, korpus 3 → 61-88 (K1: +0, K2: +10, K3: +20). Har bir kontrollerga o'z universe raqami (1-8) yuboriladi.",
                    "В Patch universe нумеруются так: корпус 1 → 1-28, корпус 2 → 31-58, корпус 3 → 61-88 (К1: +0, К2: +10, К3: +20). Каждому контроллеру уходит его universe (1-8).",
                    "Universes in the Patch: building 1 → 1-28, 2 → 31-58, 3 → 61-88 (K1 +0, K2 +10, K3 +20). Each controller receives its own universe (1-8)."
                ),
                fontSize = 11.sp, color = Color.Gray
            )
            Text(
                L(
                    "3D ko'rinish sxematik: chiroqlar tomon va tartib raqami bo'yicha joylashtirilgan, aniq qavat joylari chizmadagidan farq qilishi mumkin.",
                    "3D-вид схематичный: приборы расставлены по стороне и порядковому номеру, точные этажи могут отличаться от чертежа.",
                    "The 3D view is schematic: fixtures are placed by side and number; exact floors may differ from the drawings."
                ),
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}
