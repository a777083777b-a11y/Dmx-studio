package com.example.dmxstudio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.sqrt

// ---------------- common helpers ----------------
@Composable
fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { i, n ->
            if (selected == i) Button(onClick = { onSelect(i) }) { Text(n) }
            else OutlinedButton(onClick = { onSelect(i) }) { Text(n) }
        }
    }
}

@Composable
fun DecField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == '-' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = modifier
    )
}

fun cColor(c: Int): Color = Color(rOf(c), gOf(c), bOf(c))

fun palRgb(p: Pal): Int = if (p.r + p.g + p.b == 0) rgbOf(255, 255, 255) else rgbOf(p.r, p.g, p.b)

@Composable
fun ColorPick(label: String, c: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(cColor(c), 28)
            Text(label + "  R" + rOf(c) + " G" + gOf(c) + " B" + bOf(c), fontSize = 12.sp)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (p in PALETTE) Swatch(palColor(p), 30) { onChange(palRgb(p)) }
            Swatch(Color(255, 128, 0), 30) { onChange(rgbOf(255, 128, 0)) }
            Swatch(Color(128, 0, 255), 30) { onChange(rgbOf(128, 0, 255)) }
            Swatch(Color(255, 180, 100), 30) { onChange(rgbOf(255, 180, 100)) }
            Swatch(Color.Black, 30) { onChange(0) }
        }
        val names = listOf("R", "G", "B")
        for (k in 0 until 3) {
            val v = when (k) { 0 -> rOf(c); 1 -> gOf(c); else -> bOf(c) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(names[k], modifier = Modifier.width(20.dp), fontSize = 12.sp)
                Slider(
                    value = v.toFloat(),
                    onValueChange = {
                        val x = it.toInt()
                        onChange(
                            when (k) {
                                0 -> rgbOf(x, gOf(c), bOf(c))
                                1 -> rgbOf(rOf(c), x, bOf(c))
                                else -> rgbOf(rOf(c), gOf(c), x)
                            }
                        )
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun RunningBar() {
    if (Engine.status.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFE3F2FD)).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("▶ " + Engine.status, modifier = Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { Engine.stop() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
        }
    }
}

// ---------------- ADRES (RDM, QuickPlay-like) ----------------
@Composable
fun RdmScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uni by remember { mutableStateOf("1") }
    var nodeIp by remember { mutableStateOf(Studio.ip) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var devs by remember { mutableStateOf(listOf<RdmDev>()) }
    val ident = remember { mutableStateMapOf<String, Boolean>() }
    val addrEdit = remember { mutableStateMapOf<String, String>() }
    var autoStart by remember { mutableStateOf("1") }

    fun replace(d: RdmDev) {
        devs = devs.map { if (it.uid == d.uid) d else it }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Adres berish (RDM orqali)", "Адресация (через RDM)", "Addressing (via RDM)"), fontWeight = FontWeight.Bold)
        Text(
            L(
                "Art-Net node RDM'ni qo'llashi kerak. Chiroqlar topiladi, adresi o'qiladi va yoziladi.",
                "Узел Art-Net должен поддерживать RDM. Приборы находятся, адрес читается и записывается.",
                "The Art-Net node must support RDM. Fixtures are discovered, addresses read and written."
            ),
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Universe", uni, Modifier.weight(1f)) { uni = it }
            OutlinedTextField(
                value = nodeIp, onValueChange = { nodeIp = it },
                label = { Text("Node IP") }, singleLine = true, modifier = Modifier.weight(2f)
            )
        }
        Button(enabled = !busy, onClick = {
            busy = true
            status = ""
            scope.launch {
                try {
                    val u = uni.toIntOrNull() ?: 1
                    devs = withContext(Dispatchers.IO) {
                        Rdm.scan(ctx, nodeIp, u) { s -> status = s }
                    }
                    status = L("Topilgan chiroqlar: ", "Найдено приборов: ", "Devices found: ") + devs.size
                } catch (e: Exception) {
                    status = L("Xato: ", "Ошибка: ", "Error: ") + e.message
                }
                busy = false
            }
        }) { Text(if (busy) L("Kuting...", "Подождите...", "Please wait...") else L("RDM qidirish", "RDM поиск", "RDM discovery")) }
        if (status.isNotEmpty()) Text(status, fontSize = 12.sp)

        if (devs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L("Avtomatik adres", "Автоадресация", "Auto addressing"), fontWeight = FontWeight.Bold)
                    NumField(L("Boshlang'ich adres", "Начальный адрес", "Start address"), autoStart, Modifier.fillMaxWidth()) { autoStart = it }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                var a = autoStart.toIntOrNull() ?: 1
                                var ok = 0
                                for (d in devs) {
                                    val fp = if (d.footprint > 0) d.footprint else 1
                                    if (a + fp - 1 > 512) break
                                    val r = withContext(Dispatchers.IO) { Rdm.setAddress(ctx, d, a) }
                                    if (r) {
                                        ok += 1
                                        replace(d.copy(start = a))
                                    }
                                    a += fp
                                }
                                status = L("Yozildi: ", "Записано: ", "Written: ") + ok + "/" + devs.size
                                busy = false
                            }
                        }) { Text(L("Ketma-ket adres berish", "Адреса по порядку", "Address in sequence")) }
                        OutlinedButton(onClick = {
                            val u = uni.toIntOrNull() ?: 1
                            for (d in devs) {
                                if (d.start in 1..512) {
                                    val ch = if (d.footprint > 0) d.footprint else 3
                                    Studio.addFixtures(if (d.label.isNotBlank()) d.label else "RDM " + d.uid.takeLast(4), u, d.start, ch, 1)
                                }
                            }
                            status = L("Patchga qo'shildi", "Добавлено в Patch", "Added to Patch")
                        }) { Text(L("Hammasini Patchga", "Все в Patch", "All to Patch")) }
                    }
                }
            }
        }

        for (d in devs) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.uid + (if (d.label.isNotBlank()) "  " + d.label else ""), fontWeight = FontWeight.Bold)
                    Text(
                        L("Adres: ", "Адрес: ", "Address: ") + (if (d.start > 0) d.start.toString() else "?") +
                            "   " + L("Kanallar: ", "Каналов: ", "Channels: ") + (if (d.footprint > 0) d.footprint.toString() else "?") +
                            "   " + L("Rejim: ", "Режим: ", "Mode: ") + d.personality +
                            (if (d.model >= 0) "   Model: 0x" + Integer.toHexString(d.model).uppercase() else ""),
                        fontSize = 12.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField(L("Yangi adres", "Новый адрес", "New address"), addrEdit[d.uid] ?: (if (d.start > 0) d.start.toString() else "1"), Modifier.weight(1f)) {
                            addrEdit[d.uid] = it
                        }
                        Button(enabled = !busy, onClick = click@{
                            val a = (addrEdit[d.uid] ?: "").toIntOrNull() ?: return@click
                            if (a < 1 || a > 512) return@click
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Rdm.setAddress(ctx, d, a) }
                                if (ok) replace(d.copy(start = a))
                                status = if (ok) L("Adres yozildi", "Адрес записан", "Address written") else L("Javob yo'q", "Нет ответа", "No response")
                                busy = false
                            }
                        }) { Text(L("Yozish", "Записать", "Write")) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val on = ident[d.uid] == true
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Rdm.identify(ctx, d, !on) }
                                if (ok) ident[d.uid] = !on
                                busy = false
                            }
                        }) { Text(if (on) L("Aniqlash: YONIQ", "Найти: ВКЛ", "Identify: ON") else L("Aniqlash", "Найти", "Identify")) }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val nd = withContext(Dispatchers.IO) { Rdm.refresh(ctx, d) }
                                replace(nd)
                                busy = false
                            }
                        }) { Text(L("Yangilash", "Обновить", "Refresh")) }
                        OutlinedButton(onClick = {
                            val u = uni.toIntOrNull() ?: 1
                            if (d.start in 1..512) {
                                Studio.addFixtures(if (d.label.isNotBlank()) d.label else "RDM " + d.uid.takeLast(4), u, d.start, if (d.footprint > 0) d.footprint else 3, 1)
                                status = L("Patchga qo'shildi", "Добавлено в Patch", "Added to Patch")
                            }
                        }) { Text("Patch +") }
                    }
                }
            }
        }
        Text(
            L(
                "Eslatma: asl Color Kinetics (KiNET) chiroqlari adresi QuickPlay Pro orqali beriladi; bu yerda RDM qo'llaydigan DMX chiroqlar uchun.",
                "Примечание: оригинальные приборы Color Kinetics (KiNET) адресуются через QuickPlay Pro; здесь — для DMX-приборов с RDM.",
                "Note: native Color Kinetics (KiNET) fixtures are addressed with QuickPlay Pro; this tab is for DMX fixtures with RDM."
            ),
            fontSize = 11.sp, color = Color.Gray
        )
    }
}

// ---------------- XARITA + MEDIA (VSM-like) ----------------
@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            tick += 1
        }
    }
    var selId by remember { mutableStateOf(-1) }
    var lu by remember { mutableStateOf("0") }
    var perLine by remember { mutableStateOf("10") }
    var snake by remember { mutableStateOf(false) }
    var byCols by remember { mutableStateOf(false) }
    var bottomUp by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf("15") }
    var pendingVideo by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            val nm = (uri.lastPathSegment ?: "media").substringAfterLast('/').substringAfterLast(':')
            Studio.clips.add(MediaClip(nm, uri.toString(), (fps.toIntOrNull() ?: 15).coerceIn(1, 30), pendingVideo))
            Studio.save()
        }
    }

    val aspects = listOf(1f, 2f, 3f, 4f, 16f / 9f)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Fasad xaritasi", "Карта фасада", "Facade map"), fontWeight = FontWeight.Bold)
        Text(
            L(
                "Chiroqni barmoq bilan surib joyiga qo'ying. Effektlar va video shu joylashuv bo'yicha chiqadi.",
                "Перетаскивайте приборы пальцем. Эффекты и видео выводятся по этой раскладке.",
                "Drag fixtures with your finger. Effects and video follow this layout."
            ),
            fontSize = 12.sp
        )
        Chips(listOf("1:1", "2:1", "3:1", "4:1", "16:9"), aspects.indexOfFirst { kotlin.math.abs(it - Studio.mapAspect) < 0.01f }) {
            Studio.mapAspect = aspects[it]
            Studio.save()
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(Studio.mapAspect).background(Color(0xFF101418))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val fx = Studio.sortedFixtures(0)
                        val pos = Studio.positions(fx)
                        var best = -1
                        var bd = 60f * 60f
                        for (i in fx.indices) {
                            val dx = pos[i].first * w - down.position.x
                            val dy = pos[i].second * h - down.position.y
                            val d = dx * dx + dy * dy
                            if (d < bd) {
                                bd = d
                                best = i
                            }
                        }
                        if (best < 0) return@awaitEachGesture
                        val id = fx[best].id
                        selId = id
                        down.consume()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            Studio.moveFixture(id, ch.position.x / w, ch.position.y / h)
                            moved = true
                            ch.consume()
                        }
                        if (moved) Studio.save()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = tick
                val fx = Studio.sortedFixtures(0)
                val pos = Studio.positions(fx)
                val n = fx.size.coerceAtLeast(1)
                val r = (minOf(size.width, size.height) / (sqrt(n.toFloat()) * 2.5f)).coerceIn(4f, 22f) + (t % 1) * 0f
                for ((i, f) in fx.withIndex()) {
                    val c = Studio.outColor(f)
                    val center = Offset(pos[i].first * size.width, pos[i].second * size.height)
                    drawCircle(Color(0xFF333333), r + 2f, center)
                    drawCircle(cColor(c), r, center)
                    if (f.id == selId) drawCircle(Color.White, r + 4f, center, style = Stroke(2f))
                }
            }
        }
        val sel = Studio.fixtures.firstOrNull { it.id == selId }
        if (sel != null) {
            Text(sel.name + "  U" + sel.universe + "." + sel.address + "   x=" + String.format("%.2f", sel.x) + " y=" + String.format("%.2f", sel.y), fontSize = 12.sp)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Avtomatik joylashtirish", "Авто-раскладка", "Auto layout"), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(L("Universe (0=hammasi)", "Universe (0=все)", "Universe (0=all)"), lu, Modifier.weight(1f)) { lu = it }
                    NumField(L("Bir qatorda", "В линии", "Per line"), perLine, Modifier.weight(1f)) { perLine = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Switch(checked = byCols, onCheckedChange = { byCols = it })
                    Text(L("Vertikal (ustunlar)", "Вертикально (колонны)", "Vertical (columns)"), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Switch(checked = snake, onCheckedChange = { snake = it })
                    Text(L("Ilon (zigzag)", "Змейка", "Snake (zigzag)"), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Switch(checked = bottomUp, onCheckedChange = { bottomUp = it })
                    Text(L("Pastdan boshlash", "Начать снизу", "Start at bottom"), fontSize = 12.sp)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        Studio.layoutGrid(lu.toIntOrNull() ?: 0, perLine.toIntOrNull() ?: 10, snake, byCols, bottomUp)
                    }) { Text(L("Joylashtirish", "Разложить", "Apply layout")) }
                    OutlinedButton(onClick = { Studio.clearLayout() }) { Text(L("Tozalash", "Сбросить", "Reset")) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Rasm va video (pixel mapping)", "Картинки и видео (пиксель-маппинг)", "Images and video (pixel mapping)"), fontWeight = FontWeight.Bold)
                NumField(L("Video kadr/sek (1-30)", "Видео кадр/с (1-30)", "Video fps (1-30)"), fps, Modifier.fillMaxWidth()) { fps = it }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        pendingVideo = false
                        picker.launch(arrayOf("image/*"))
                    }) { Text(L("+ Rasm", "+ Картинка", "+ Image")) }
                    Button(onClick = {
                        pendingVideo = true
                        picker.launch(arrayOf("video/*"))
                    }) { Text(L("+ Video", "+ Видео", "+ Video")) }
                }
                if (Media.progress.isNotEmpty()) Text(Media.progress, fontSize = 12.sp)
                Studio.clips.toList().forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (c.isVideo) "🎬 " else "🖼 ") + c.name + (if (c.isVideo) "  " + c.fps + "fps" else ""), modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { Engine.playClip(i) }) { Text("▶") }
                        TextButton(onClick = { Studio.deleteClip(i) }) { Text("X") }
                    }
                }
                Text(
                    L(
                        "Video oldin kichraytirilib xotiraga tayyorlanadi (bir necha soniya), keyin silliq o'ynaydi va takrorlanadi.",
                        "Видео сначала уменьшается и готовится в памяти (несколько секунд), затем играет плавно по кругу.",
                        "Video is first scaled down and prepared in memory (a few seconds), then plays smoothly in a loop."
                    ),
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        }
    }
}

// ---------------- EFFEKTLAR (ColorPlay-like) ----------------
@Composable
fun EffectsScreen() {
    var name by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf(-1) }
    val e = Engine.edit
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Effekt yaratish", "Создание эффекта", "Effect editor"), fontWeight = FontWeight.Bold)
        Chips(Engine.effectNames, e.type) { Engine.edit = e.copy(type = it) }
        ColorPick(L("1-rang", "Цвет 1", "Color 1"), e.c1) { Engine.edit = Engine.edit.copy(c1 = it) }
        ColorPick(L("2-rang (fon)", "Цвет 2 (фон)", "Color 2 (background)"), e.c2) { Engine.edit = Engine.edit.copy(c2 = it) }
        Text(L("Tezlik (davr): ", "Скорость (период): ", "Speed (period): ") + String.format("%.1f", e.periodMs / 1000f) + " s")
        Slider(value = e.periodMs.toFloat(), onValueChange = { Engine.edit = Engine.edit.copy(periodMs = it.toInt()) }, valueRange = 200f..30000f)
        Text(L("O'lcham / zichlik: ", "Размер / плотность: ", "Size / density: ") + (e.size * 100).toInt() + "%")
        Slider(value = e.size, onValueChange = { Engine.edit = Engine.edit.copy(size = it) }, valueRange = 0.02f..1f)
        Text(L("Yo'nalish", "Направление", "Direction"), fontSize = 12.sp)
        Chips(Engine.dirNames, e.dir) { Engine.edit = Engine.edit.copy(dir = it) }
        Text(L("Yorqinlik: ", "Яркость: ", "Level: ") + e.level + "%")
        Slider(value = e.level.toFloat(), onValueChange = { Engine.edit = Engine.edit.copy(level = it.toInt()) }, valueRange = 0f..100f)
        NumField(L("Universe (0 = hammasi)", "Universe (0 = все)", "Universe (0 = all)"), e.universe.toString(), Modifier.fillMaxWidth()) {
            Engine.edit = Engine.edit.copy(universe = it.toIntOrNull() ?: 0)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (Engine.editLive) Button(onClick = { Engine.stop() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
            else Button(onClick = { Engine.playEdit() }) { Text(L("▶ Jonli ko'rish", "▶ Просмотр", "▶ Live preview")) }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(L("Effekt nomi", "Название эффекта", "Effect name")) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val nm = if (name.isBlank()) Engine.effectNames[e.type] + " " + (Studio.effects.size + 1) else name
                Studio.effects.add(Engine.edit.copy(name = nm))
                Studio.save()
                name = ""
                editIndex = -1
            }) { Text(L("Yangi saqlash", "Сохранить как новый", "Save as new")) }
            if (editIndex >= 0 && editIndex < Studio.effects.size) {
                OutlinedButton(onClick = {
                    val old = Studio.effects[editIndex]
                    Studio.effects[editIndex] = Engine.edit.copy(name = if (name.isBlank()) old.name else name)
                    Studio.save()
                }) { Text(L("Yangilash: ", "Обновить: ", "Update: ") + Studio.effects[editIndex].name) }
            }
        }
        Text(L("Saqlangan effektlar", "Сохранённые эффекты", "Saved effects"), fontWeight = FontWeight.Bold)
        Studio.effects.toList().forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(cColor(p.c1), 18)
                Text(" " + p.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { Engine.playEffect(i) }) { Text("▶") }
                TextButton(onClick = {
                    Engine.edit = p
                    editIndex = i
                    name = p.name
                }) { Text(L("Tahrir", "Изм.", "Edit")) }
                TextButton(onClick = {
                    Studio.deleteEffect(i)
                    editIndex = -1
                }) { Text("X") }
            }
        }
    }
}

// ---------------- SHOU (timeline) ----------------
@Composable
fun ShowScreen() {
    var name by remember { mutableStateOf("") }
    var dur by remember { mutableStateOf("10") }
    var fade by remember { mutableStateOf("2") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Shou (ketma-ketlik)", "Шоу (последовательность)", "Show (timeline)"), fontWeight = FontWeight.Bold)
        Text(
            L(
                "Sahna, effekt va videolarni ketma-ket qo'ying. Har biri belgilangan vaqt o'ynaydi, o'tishlar silliq (fade).",
                "Ставьте сцены, эффекты и видео по очереди. Каждый играет заданное время, переходы плавные.",
                "Put scenes, effects and videos in order. Each plays for its duration with smooth crossfades."
            ),
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(L("Shou nomi", "Название шоу", "Show name")) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            val nm = if (name.isBlank()) L("Shou ", "Шоу ", "Show ") + (Studio.shows.size + 1) else name
            Studio.shows.add(Show(nm, emptyList(), true))
            Studio.save()
            name = ""
        }) { Text(L("Shou yaratish", "Создать шоу", "Create show")) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DecField(L("Davomiyligi (s)", "Длительность (с)", "Duration (s)"), dur, Modifier.weight(1f)) { dur = it }
            DecField(L("O'tish (s)", "Переход (с)", "Crossfade (s)"), fade, Modifier.weight(1f)) { fade = it }
        }
        Studio.shows.toList().forEachIndexed { si, sh ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sh.name, fontWeight = FontWeight.Bold)
                    sh.items.forEachIndexed { ii, it ->
                        val nm = when (it.kind) {
                            0 -> L("Sahna: ", "Сцена: ", "Scene: ") + (Studio.scenes.getOrNull(it.index)?.name ?: "?")
                            1 -> L("Effekt: ", "Эффект: ", "Effect: ") + (Studio.effects.getOrNull(it.index)?.name ?: "?")
                            else -> L("Media: ", "Медиа: ", "Media: ") + (Studio.clips.getOrNull(it.index)?.name ?: "?")
                        }
                        val cur = Engine.showPlaying == si && Engine.showStep == ii
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (cur) "▶ " else "") + (ii + 1) + ". " + nm + "  " + (it.durMs / 1000f) + "s / " + (it.fadeMs / 1000f) + "s",
                                modifier = Modifier.weight(1f), fontSize = 12.sp,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal
                            )
                            TextButton(onClick = {
                                Studio.shows[si] = sh.copy(items = sh.items.filterIndexed { k, _ -> k != ii })
                                Studio.save()
                            }) { Text("X") }
                        }
                    }
                    fun add(kind: Int, idx: Int) {
                        val d = ((dur.toFloatOrNull() ?: 10f) * 1000).toInt().coerceAtLeast(100)
                        val f = ((fade.toFloatOrNull() ?: 0f) * 1000).toInt().coerceIn(0, d)
                        val cur = Studio.shows.getOrNull(si) ?: return
                        Studio.shows[si] = cur.copy(items = cur.items + ShowItem(kind, idx, d, f))
                        Studio.save()
                    }
                    if (Studio.scenes.isNotEmpty()) {
                        Text(L("+ Sahna:", "+ Сцена:", "+ Scene:"), fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Studio.scenes.forEachIndexed { k, s -> OutlinedButton(onClick = { add(0, k) }) { Text(s.name) } }
                        }
                    }
                    if (Studio.effects.isNotEmpty()) {
                        Text(L("+ Effekt:", "+ Эффект:", "+ Effect:"), fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Studio.effects.forEachIndexed { k, s -> OutlinedButton(onClick = { add(1, k) }) { Text(s.name) } }
                        }
                    }
                    if (Studio.clips.isNotEmpty()) {
                        Text(L("+ Media:", "+ Медиа:", "+ Media:"), fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Studio.clips.forEachIndexed { k, s -> OutlinedButton(onClick = { add(2, k) }) { Text(s.name) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Switch(checked = sh.loop, onCheckedChange = {
                            Studio.shows[si] = sh.copy(loop = it)
                            Studio.save()
                        })
                        Text(L("Takrorlash", "По кругу", "Loop"), fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Engine.showPlaying == si) Button(onClick = { Engine.stop() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
                        else Button(onClick = { Engine.playShow(si) }) { Text(L("Boshlash", "Старт", "Play")) }
                        OutlinedButton(onClick = { Studio.deleteShow(si) }) { Text(L("O'chirish", "Удалить", "Delete")) }
                    }
                }
            }
        }
    }
}

// ---------------- JADVAL (LSM-like schedule) ----------------
@Composable
fun ScheduleScreen() {
    var latS by remember { mutableStateOf(Studio.lat.toString()) }
    var lonS by remember { mutableStateOf(Studio.lon.toString()) }
    var kind by remember { mutableStateOf(1) }
    var hh by remember { mutableStateOf("19") }
    var mm by remember { mutableStateOf("00") }
    var off by remember { mutableStateOf("0") }
    var days by remember { mutableStateOf(127) }
    var action by remember { mutableStateOf(1) }
    var target by remember { mutableStateOf(0) }
    val dayNames = listOf(L("Du", "Пн", "Mo"), L("Se", "Вт", "Tu"), L("Ch", "Ср", "We"), L("Pa", "Чт", "Th"), L("Ju", "Пт", "Fr"), L("Sh", "Сб", "Sa"), L("Ya", "Вс", "Su"))
    val kindNames = listOf(L("Aniq vaqt", "Точное время", "Fixed time"), L("Quyosh botishi", "Закат", "Sunset"), L("Quyosh chiqishi", "Восход", "Sunrise"))
    val today = Calendar.getInstance()
    val sun = Scheduler.sunTimes(today, Studio.lat, Studio.lon)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Jadval (avtomatik yoqish/o'chirish)", "Расписание (авто вкл/выкл)", "Schedule (auto on/off)"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DecField(L("Kenglik", "Широта", "Latitude"), latS, Modifier.weight(1f)) {
                latS = it
                it.toDoubleOrNull()?.let { v -> Studio.lat = v; Studio.save() }
            }
            DecField(L("Uzunlik", "Долгота", "Longitude"), lonS, Modifier.weight(1f)) {
                lonS = it
                it.toDoubleOrNull()?.let { v -> Studio.lon = v; Studio.save() }
            }
        }
        Text(
            if (sun != null) L("Bugun: quyosh chiqishi ", "Сегодня: восход ", "Today: sunrise ") + Scheduler.hhmm(sun.first) +
                L(", botishi ", ", закат ", ", sunset ") + Scheduler.hhmm(sun.second)
            else L("Quyosh vaqti hisoblanmadi", "Время солнца не рассчитано", "Sun times unavailable"),
            fontSize = 12.sp
        )
        if (Scheduler.lastAction.isNotEmpty()) Text(L("Oxirgi bajarilgan: ", "Последнее: ", "Last run: ") + Scheduler.lastAction, fontSize = 12.sp)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Yangi qoida", "Новое правило", "New rule"), fontWeight = FontWeight.Bold)
                Chips(kindNames, kind) { kind = it }
                if (kind == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField(L("Soat", "Час", "Hour"), hh, Modifier.weight(1f)) { hh = it }
                        NumField(L("Daqiqa", "Минута", "Minute"), mm, Modifier.weight(1f)) { mm = it }
                    }
                } else {
                    DecField(L("Siljish, daqiqa (+/-)", "Смещение, мин (+/-)", "Offset, minutes (+/-)"), off, Modifier.fillMaxWidth()) { off = it }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayNames.forEachIndexed { i, d ->
                        val on = (days and (1 shl i)) != 0
                        if (on) Button(onClick = { days = days xor (1 shl i) }) { Text(d) }
                        else OutlinedButton(onClick = { days = days xor (1 shl i) }) { Text(d) }
                    }
                }
                Chips(listOf(L("O'chirish", "Выключить", "Off"), L("Sahna", "Сцена", "Scene"), L("Effekt", "Эффект", "Effect"), L("Shou", "Шоу", "Show")), action) {
                    action = it
                    target = 0
                }
                val targets: List<String> = when (action) {
                    1 -> Studio.scenes.map { it.name }
                    2 -> Studio.effects.map { it.name }
                    3 -> Studio.shows.map { it.name }
                    else -> emptyList()
                }
                if (action != 0) {
                    if (targets.isEmpty()) Text(L("Ro'yxat bo'sh — avval yarating.", "Список пуст — сначала создайте.", "List is empty — create one first."), color = Color.Red, fontSize = 12.sp)
                    else Chips(targets, target) { target = it }
                }
                Button(onClick = click@{
                    if (action != 0 && targets.isEmpty()) return@click
                    val minutes = if (kind == 0) {
                        (hh.toIntOrNull() ?: 0).coerceIn(0, 23) * 60 + (mm.toIntOrNull() ?: 0).coerceIn(0, 59)
                    } else (off.toIntOrNull() ?: 0).coerceIn(-720, 720)
                    Studio.schedules.add(Sched(true, kind, minutes, days, action, target))
                    Studio.save()
                }) { Text(L("Qo'shish", "Добавить", "Add")) }
            }
        }

        Studio.schedules.toList().forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    val dl = dayNames.filterIndexed { k, _ -> (s.days and (1 shl k)) != 0 }.joinToString(",")
                    val whenS = when (s.kind) {
                        0 -> Scheduler.hhmm(s.minutes)
                        1 -> L("Quyosh botishi", "Закат", "Sunset") + (if (s.minutes >= 0) " +" else " ") + s.minutes + L(" daq", " мин", " min")
                        else -> L("Quyosh chiqishi", "Восход", "Sunrise") + (if (s.minutes >= 0) " +" else " ") + s.minutes + L(" daq", " мин", " min")
                    }
                    val tm = Scheduler.triggerMinute(s, today)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.enabled, onCheckedChange = {
                            Studio.schedules[i] = s.copy(enabled = it)
                            Studio.save()
                        })
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(whenS + "  →  " + Scheduler.actionName(s), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(dl + (if (tm != null) "   (" + L("bugun ", "сегодня ", "today ") + Scheduler.hhmm(tm) + ")" else ""), fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { Scheduler.run(s) }) { Text(L("Hozir bajarish", "Выполнить сейчас", "Run now")) }
                        TextButton(onClick = {
                            Studio.schedules.removeAt(i)
                            Scheduler.resetFired()
                            Studio.save()
                        }) { Text(L("O'chirish", "Удалить", "Delete")) }
                    }
                }
            }
        }
        Text(
            L(
                "Ilova fonda ishlaydi (bildirishnoma ko'rinadi). Telefon Wi-Fi'da va quvvatga ulangan bo'lishi kerak.",
                "Приложение работает в фоне (видно уведомление). Телефон должен быть в Wi-Fi и на зарядке.",
                "The app keeps running in the background (a notification is shown). Keep the phone on Wi-Fi and charging."
            ),
            fontSize = 11.sp, color = Color.Gray
        )
    }
}
