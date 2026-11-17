package com.example.dmxstudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

val FX_COLORS = listOf(
    0xFF0000, 0xFF8000, 0xFFFF00, 0x80FF00, 0x00FF00, 0x00FFFF, 0x0080FF,
    0x0000FF, 0x8000FF, 0xFF00FF, 0xFF0060, 0xFFB060, 0xFFFFFF, 0x000000
)

fun rgbColor(c: Int): Color = Color((c shr 16) and 255, (c shr 8) and 255, c and 255)

@Composable
fun Choice(selected: Boolean, text: String, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(text) } else OutlinedButton(onClick = onClick) { Text(text) }
}

@Composable
fun ChoiceRow(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, n -> Choice(selected == i, n) { onPick(i) } }
    }
}

fun periodToSlider(p: Float): Float = (ln(p / 0.1f) / ln(600f)).coerceIn(0f, 1f)
fun sliderToPeriod(s: Float): Float = 0.1f * 600f.pow(s)

fun fmtSec(p: Float): String = if (p < 10f) "%.1f s".format(p) else "%.0f s".format(p)

fun startBackground(ctx: Context) {
    try {
        DmxService.start(ctx)
    } catch (e: Exception) {
        Studio.lastError = e.message ?: "service"
    }
}

@Composable
fun rememberNotifAsk(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ---------------- status strip (always visible) ----------------
@Composable
fun StatusStrip() {
    val parts = ArrayList<String>()
    if (Engine.playingShow >= 0) parts.add(L("Shou: ", "Шоу: ", "Show: ") + (Engine.shows.getOrNull(Engine.playingShow)?.name ?: ""))
    if (Engine.activeEffect >= 0) parts.add(L("Effekt: ", "Эффект: ", "Effect: ") + (Engine.effects.getOrNull(Engine.activeEffect)?.name ?: ""))
    if (Studio.playing >= 0) parts.add("Chase: " + (Studio.chases.getOrNull(Studio.playing)?.name ?: ""))
    if (Studio.testing) parts.add("Test")
    if (Studio.blackout) parts.add("BLACKOUT")
    if (parts.isEmpty() && !Engine.schedOn && !Studio.background) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFE3F2FD)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val extra = (if (Engine.schedOn) L(" [Jadval]", " [Расписание]", " [Schedule]") else "") +
            (if (Studio.background) L(" [Fon]", " [Фон]", " [BG]") else "")
        Text(parts.joinToString(" | ") + extra, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (parts.isNotEmpty()) {
            Button(onClick = {
                Engine.stopAll()
                Studio.blackout = false
            }) { Text(L("STOP hammasi", "СТОП всё", "STOP all")) }
        }
    }
}

// ---------------- EFFECTS (ColorPlay) ----------------
@Composable
fun EffectsScreen() {
    var edit by remember { mutableStateOf(-2) }
    if (edit != -2) {
        EffectEditor(edit) { edit = -2 }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Effektlar (ColorPlay kabi)", "Эффекты (как ColorPlay)", "Effects (like ColorPlay)"), fontWeight = FontWeight.Bold)
                Text(
                    L(
                        "Effekt Pult ustidan ishlaydi. Xarita bo'limidagi joylashuvga qarab to'lqin va kamalak fasad bo'ylab yuradi.",
                        "Эффект работает поверх пульта. Волна и радуга идут по фасаду согласно расположению на вкладке «Карта».",
                        "Effects run on top of the console. Wave and rainbow follow the fixture layout on the Map tab."
                    ),
                    fontSize = 12.sp
                )
                Button(onClick = { edit = -1 }) { Text(L("+ Yangi effekt", "+ Новый эффект", "+ New effect")) }
            }
        }
        itemsIndexed(Engine.effects.toList()) { i, e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.name, fontWeight = FontWeight.Bold)
                    Text(Engine.typeName(e.type) + " · " + fmtSec(e.period) + " · " + e.level + "% · " + Engine.targetName(e.target, e.tu), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in e.colors) Swatch(rgbColor(c), 20)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Engine.activeEffect == i) {
                            Button(onClick = { Engine.stopEffect() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
                        } else {
                            Button(onClick = {
                                Studio.blackout = false
                                Engine.runEffect(i)
                            }) { Text(L("Yoqish", "Пуск", "Play")) }
                        }
                        OutlinedButton(onClick = { edit = i }) { Text(L("Sozlash", "Настроить", "Edit")) }
                        OutlinedButton(onClick = {
                            Engine.saveEffect(e.copy(name = e.name + " 2"), -1)
                        }) { Text(L("Nusxa", "Копия", "Copy")) }
                        OutlinedButton(onClick = { Engine.deleteEffect(i) }) { Text("X") }
                    }
                }
            }
        }
    }
}

@Composable
fun EffectEditor(index: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val src = Engine.effects.getOrNull(index)
    var name by remember { mutableStateOf(src?.name ?: "") }
    var type by remember { mutableStateOf(src?.type ?: Engine.T_WAVE) }
    val colors = remember { mutableStateListOf<Int>().also { it.addAll(src?.colors ?: listOf(0x0000FF, 0xFFFFFF)) } }
    var sel by remember { mutableStateOf(0) }
    var period by remember { mutableStateOf(src?.period ?: 5f) }
    var size by remember { mutableStateOf(src?.size ?: 1f) }
    var dir by remember { mutableStateOf(src?.dir ?: 0) }
    var level by remember { mutableStateOf(src?.level ?: 100) }
    var target by remember { mutableStateOf(src?.target ?: 0) }
    var tu by remember { mutableStateOf((src?.tu ?: 1).toString()) }
    var imgW by remember { mutableStateOf(src?.imgW ?: 0) }
    var imgH by remember { mutableStateOf(src?.imgH ?: 0) }
    var img by remember { mutableStateOf(src?.img ?: emptyList()) }
    var msg by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf(index) }

    fun build(): Effect = Effect(
        if (name.isBlank()) Engine.typeName(type) else name, type, colors.toList(), period, size, dir, level,
        target, tu.toIntOrNull() ?: 1, imgW, imgH, img
    )

    // live tuning: while this effect is playing, changes show at once
    LaunchedEffect(type, colors.toList(), period, size, dir, level, target, tu, img) {
        if (cur >= 0 && Engine.activeEffect == cur && cur < Engine.effects.size) Engine.effects[cur] = build()
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val bmp = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    val maxW = 96
                    val maxH = 48
                    val k = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f)
                    val w = (bmp.width * k).toInt().coerceAtLeast(1)
                    val h = (bmp.height * k).toInt().coerceAtLeast(1)
                    val sc: Bitmap = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val px = IntArray(w * h)
                    sc.getPixels(px, 0, w, 0, 0, w, h)
                    imgW = w
                    imgH = h
                    img = px.map { it and 0xFFFFFF }
                    type = Engine.T_IMAGE
                    msg = L("Rasm yuklandi: ", "Картинка загружена: ", "Image loaded: ") + w + "x" + h
                }
            } catch (e: Exception) {
                msg = L("Xato: ", "Ошибка: ", "Error: ") + e.message
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onDone) { Text(L("< Orqaga", "< Назад", "< Back")) }
            Button(onClick = {
                cur = Engine.saveEffect(build(), cur)
                onDone()
            }) { Text(L("Saqlash", "Сохранить", "Save")) }
            OutlinedButton(onClick = {
                cur = Engine.saveEffect(build(), cur)
                Studio.blackout = false
                Engine.runEffect(cur)
            }) { Text(L("Sinash", "Проверить", "Try")) }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(L("Nomi", "Название", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text(L("Turi", "Тип", "Type"), fontWeight = FontWeight.Bold)
        ChoiceRow((0..9).map { Engine.typeName(it) }, type) { type = it }

        if (type != Engine.T_RAINBOW && type != Engine.T_IMAGE) {
            Text(L("Ranglar (bosib tanlang, keyin pastdan rang bering)", "Цвета (выберите ячейку, затем цвет ниже)", "Colors (pick a slot, then a color below)"), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEachIndexed { i, c -> Swatch(rgbColor(c), 40, sel == i) { sel = i } }
                if (colors.size < 6) TextButton(onClick = {
                    colors.add(0xFFFFFF)
                    sel = colors.size - 1
                }) { Text("+") }
                if (colors.size > 1) TextButton(onClick = {
                    colors.removeAt(sel.coerceIn(0, colors.size - 1))
                    sel = 0
                }) { Text("−") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in FX_COLORS) Swatch(rgbColor(c), 32) { if (sel < colors.size) colors[sel] = c }
            }
            var hue by remember { mutableStateOf(0f) }
            Text(L("Istalgan rang (ton)", "Любой цвет (тон)", "Any color (hue)"), fontSize = 12.sp)
            Slider(value = hue, onValueChange = {
                hue = it
                if (sel < colors.size) colors[sel] = Engine.hsv(it.toDouble())
            }, valueRange = 0f..1f)
            if (type == Engine.T_CHASE || type == Engine.T_SPARKLE || type == Engine.T_STROBE) {
                Text(L("2-rang = fon", "2-й цвет = фон", "2nd color = background"), fontSize = 12.sp)
            }
        }
        if (type == Engine.T_IMAGE) {
            Text(
                L(
                    "Rasm fasad bo'ylab suriladi (VSM kabi). Rasm 96x48 gacha kichraytiriladi.",
                    "Картинка прокручивается по фасаду (как VSM). Уменьшается до 96x48.",
                    "The image scrolls across the facade (like VSM). It is scaled to 96x48."
                ),
                fontSize = 12.sp
            )
            Button(onClick = { pick.launch("image/*") }) { Text(L("Rasm tanlash", "Выбрать картинку", "Pick image")) }
            if (imgW > 0) Text("$imgW x $imgH", fontSize = 12.sp)
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)

        Text(L("Davr (tezlik): ", "Период (скорость): ", "Period (speed): ") + fmtSec(period), fontWeight = FontWeight.Bold)
        Slider(value = periodToSlider(period), onValueChange = { period = sliderToPeriod(it) }, valueRange = 0f..1f)
        if (type in listOf(Engine.T_CHASE, Engine.T_WAVE, Engine.T_RAINBOW, Engine.T_SPARKLE, Engine.T_STROBE, Engine.T_IMAGE)) {
            val lbl = when (type) {
                Engine.T_SPARKLE -> L("Zichlik", "Плотность", "Density")
                Engine.T_STROBE -> L("Yonish ulushi", "Доля вспышки", "Duty")
                Engine.T_CHASE -> L("Dum uzunligi", "Длина хвоста", "Tail length")
                else -> L("To'lqin uzunligi", "Длина волны", "Wavelength")
            }
            Text(lbl + ": " + (size * 100).toInt() + "%")
            Slider(value = size, onValueChange = { size = it }, valueRange = 0.02f..1f)
        }
        if (type in listOf(Engine.T_CHASE, Engine.T_WAVE, Engine.T_RAINBOW, Engine.T_IMAGE)) {
            Text(L("Yo'nalish", "Направление", "Direction"), fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("→", "←", "↓", "↑"), dir) { dir = it }
        }
        Text(L("Yorqinlik: ", "Яркость: ", "Level: ") + level + "%", fontWeight = FontWeight.Bold)
        Slider(value = level.toFloat(), onValueChange = { level = it.toInt() }, valueRange = 0f..100f)
        Text(L("Qaysi chiroqlarga", "На какие приборы", "Which fixtures"), fontWeight = FontWeight.Bold)
        ChoiceRow((0..3).map { Engine.targetName(it, tu.toIntOrNull() ?: 1) }, target) { target = it }
        if (target == 3) NumField("Universe", tu, Modifier.fillMaxWidth()) { tu = it }
    }
}

// ---------------- MAP (VSM) ----------------
fun fixtureColor(f: Fixture, out: Map<Int, Int>): Color {
    fun v(i: Int): Int = (out[Studio.key(f.universe, f.address + i)] ?: 0).coerceIn(0, 255)
    return when {
        f.channels >= 4 -> Color(minOf(255, v(0) + v(3)), minOf(255, v(1) + v(3)), minOf(255, v(2) + v(3)))
        f.channels == 3 -> Color(v(0), v(1), v(2))
        else -> Color(v(0), v(0), v(0))
    }
}

@Composable
fun MapScreen() {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            tick += 1
        }
    }
    var sel by remember { mutableStateOf(-1) }
    var editMode by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val fx = Studio.fixtures.toList()
    val pos = Engine.positions(fx)
    val padPx = 18f

    fun nearest(o: Offset, w: Float, h: Float): Int {
        val list = Studio.fixtures.toList()
        val pp = Engine.positions(list)
        var best = -1
        var bd = Float.MAX_VALUE
        for (f in list) {
            val p = pp[f.id] ?: continue
            val dx = padPx + p[0] * (w - 2 * padPx) - o.x
            val dy = padPx + p[1] * (h - 2 * padPx) - o.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < bd) {
                bd = d
                best = f.id
            }
        }
        return if (bd < 60f) best else -1
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(L("Fasad xaritasi (VSM kabi)", "Карта фасада (как VSM)", "Facade map (like VSM)"), fontWeight = FontWeight.Bold)
        Text(
            L(
                "Chiroqlar hozirgi rangida jonli ko'rinadi. Bosing: chiroqni tanlash. Tahrir yoqilsa: barmoq bilan surish.",
                "Приборы показаны в текущем цвете. Нажмите: выбрать прибор. В режиме правки: перетаскивание.",
                "Fixtures show their live color. Tap to select. In edit mode: drag to move."
            ),
            fontSize = 12.sp
        )
        Box(Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF101010))) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { o -> sel = nearest(o, size.width.toFloat(), size.height.toFloat()) }
                    }
                    .pointerInput(editMode) {
                        if (editMode) {
                            detectDragGestures(
                                onDragStart = { o -> sel = nearest(o, size.width.toFloat(), size.height.toFloat()) },
                                onDragEnd = { Studio.save() },
                                onDrag = { change, d ->
                                    change.consume()
                                    val list = Studio.fixtures.toList()
                                    val f = list.firstOrNull { it.id == sel }
                                    if (f != null && list.size > 1) {
                                        val minX = list.minOf { it.x }
                                        val maxX = list.maxOf { it.x }
                                        val minY = list.minOf { it.y }
                                        val maxY = list.maxOf { it.y }
                                        val sx = (maxX - minX).coerceAtLeast(1f) / (size.width - 2 * padPx)
                                        val sy = (maxY - minY).coerceAtLeast(1f) / (size.height - 2 * padPx)
                                        Engine.moveFixture(f.id, f.x + d.x * sx, f.y + d.y * sy)
                                    }
                                }
                            )
                        }
                    }
            ) {
                @Suppress("UNUSED_VARIABLE") val t = tick
                val out = Studio.outFrame
                val w = size.width - 2 * padPx
                val h = size.height - 2 * padPx
                val r = when {
                    fx.size > 600 -> 3.5f
                    fx.size > 200 -> 5f
                    else -> 9f
                }
                for (f in fx) {
                    val p = pos[f.id] ?: continue
                    val c = Offset(padPx + p[0] * w, padPx + p[1] * h)
                    drawCircle(Color(0xFF333333), r + 1.5f, c)
                    drawCircle(fixtureColor(f, out), r, c)
                    if (f.id == sel) drawCircle(Color.Yellow, r + 6f, c, style = Stroke(width = 3f))
                }
            }
        }
        val sf = fx.firstOrNull { it.id == sel }
        if (sf != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sf.name + "  U" + sf.universe + "." + sf.address, modifier = Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = { Studio.identify(sf) }) { Text(L("Aniqlash", "Найти", "Identify")) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(editMode, L("Tahrir (surish)", "Правка (перетаскивание)", "Edit (drag)")) { editMode = !editMode }
            OutlinedButton(onClick = {
                msg = if (Engine.layoutFacade()) L("Fasad bo'yicha joylandi", "Расставлено по фасаду", "Laid out by facade")
                else L("Nomlardan fasad topilmadi", "По названиям фасад не найден", "No facade names found")
            }) { Text(L("Avto: fasad", "Авто: фасад", "Auto: facade")) }
            OutlinedButton(onClick = {
                Engine.layoutGrid()
                msg = L("Universe bo'yicha qatorlar", "Ряды по universe", "Rows by universe")
            }) { Text(L("Avto: qatorlar", "Авто: ряды", "Auto: rows")) }
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
        Text(L("Tez effekt", "Быстрый эффект", "Quick effect"), fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Engine.effects.forEachIndexed { i, e ->
                Choice(Engine.activeEffect == i, e.name) {
                    if (Engine.activeEffect == i) Engine.stopEffect() else {
                        Studio.blackout = false
                        Engine.runEffect(i)
                    }
                }
            }
        }
        if (fx.isEmpty()) Text(L("Avval Patch bo'limida chiroq qo'shing.", "Сначала добавьте приборы во вкладке Patch.", "First add fixtures in the Patch tab."), color = Color.Red)
    }
}

// ---------------- SHOWS (ColorPlay show / cue list) ----------------
@Composable
fun ShowScreen() {
    var name by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("30") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Shou: effekt va sahnalar ketma-ketligi", "Шоу: последовательность эффектов и сцен", "Show: a sequence of effects and scenes"), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(L("Shou nomi", "Название шоу", "Show name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    Engine.addShow(name)
                    name = ""
                }) { Text(L("Shou yaratish", "Создать шоу", "Create show")) }
                NumField(L("Qadam davomiyligi (soniya)", "Длительность шага (сек)", "Step length (s)"), sec, Modifier.fillMaxWidth()) { sec = it }
            }
        }
        itemsIndexed(Engine.shows.toList()) { si, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, fontWeight = FontWeight.Bold)
                    s.steps.forEachIndexed { k, st ->
                        val nm = when (st.kind) {
                            0 -> "E: " + (Engine.effects.getOrNull(st.ref)?.name ?: "?")
                            1 -> "S: " + (Studio.scenes.getOrNull(st.ref)?.name ?: "?")
                            else -> L("O'chiq", "Выкл", "Off")
                        }
                        val now = Engine.playingShow == si && Engine.showStep == k
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (now) "▶ " else "") + (k + 1) + ". " + nm + "  " + st.sec + " s",
                                modifier = Modifier.weight(1f), fontSize = 13.sp,
                                fontWeight = if (now) FontWeight.Bold else FontWeight.Normal
                            )
                            TextButton(onClick = {
                                Engine.shows[si] = s.copy(steps = s.steps.filterIndexed { x, _ -> x != k })
                                Studio.save()
                            }) { Text("X") }
                        }
                    }
                    Text(L("Qadam qo'shish (", "Добавить шаг (", "Add step (") + (sec.toIntOrNull() ?: 30) + " s):", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fun add(kind: Int, ref: Int) {
                            val cur = Engine.shows.getOrNull(si) ?: return
                            Engine.shows[si] = cur.copy(steps = cur.steps + ShowStep(kind, ref, sec.toIntOrNull() ?: 30))
                            Studio.save()
                        }
                        Engine.effects.forEachIndexed { i, e -> OutlinedButton(onClick = { add(0, i) }) { Text("E: " + e.name) } }
                        Studio.scenes.forEachIndexed { i, sc -> OutlinedButton(onClick = { add(1, i) }) { Text("S: " + sc.name) } }
                        OutlinedButton(onClick = { add(2, 0) }) { Text(L("O'chiq", "Выкл", "Off")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Switch(checked = s.loop, onCheckedChange = {
                            Engine.shows[si] = s.copy(loop = it)
                            Studio.save()
                        })
                        Text(L("Takrorlash", "Повтор", "Loop"), fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Engine.playingShow == si) {
                            Button(onClick = { Engine.stopShow() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
                        } else {
                            Button(onClick = { Engine.playShow(si) }) { Text(L("Boshlash", "Старт", "Play")) }
                        }
                        OutlinedButton(onClick = { Engine.deleteShow(si) }) { Text(L("O'chirish", "Удалить", "Delete")) }
                    }
                }
            }
        }
    }
}

// ---------------- SCHEDULE (LSM) ----------------
fun dayNames(): List<String> = when (Studio.lang) {
    1 -> listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    2 -> listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    else -> listOf("Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya")
}

fun schedText(s: Sched): String {
    val dn = dayNames()
    val days = if (s.days == 127) L("har kuni", "каждый день", "every day")
    else (0..6).filter { (s.days shr it) and 1 == 1 }.joinToString(",") { dn[it] }
    val sign = if (s.min >= 0) "+" else ""
    val time = when (s.mode) {
        1 -> L("Quyosh botishi ", "Закат ", "Sunset ") + sign + s.min + L(" daq", " мин", " min")
        2 -> L("Quyosh chiqishi ", "Восход ", "Sunrise ") + sign + s.min + L(" daq", " мин", " min")
        else -> Engine.hhmm(s.min)
    }
    val today = Engine.trigMinute(s, LocalDate.now())
    return time + (if (s.mode != 0) " (" + Engine.hhmm(today) + ")" else "") + " · " + days + " → " + Engine.actName(s)
}

@Composable
fun ScheduleScreen() {
    val ctx = LocalContext.current
    val askNotif = rememberNotifAsk()
    var mode by remember { mutableStateOf(0) }
    var hh by remember { mutableStateOf("19") }
    var mm by remember { mutableStateOf("00") }
    var off by remember { mutableStateOf("0") }
    var days by remember { mutableStateOf(127) }
    var act by remember { mutableStateOf(1) }
    var ref by remember { mutableStateOf(0) }
    var latT by remember { mutableStateOf(Engine.lat.toString()) }
    var lonT by remember { mutableStateOf(Engine.lon.toString()) }
    val today = LocalDate.now()

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Vaqt jadvali (LSM kabi)", "Расписание (как LSM)", "Schedule (like LSM)"), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = Engine.schedOn, onCheckedChange = {
                        Engine.schedOn = it
                        Studio.save()
                        if (it) {
                            askNotif()
                            startBackground(ctx)
                        } else if (!Studio.backgroundWanted) {
                            DmxService.stop(ctx)
                        }
                    })
                    Text(
                        L("Jadval yoqilgan (ilova fonda ishlaydi)", "Расписание включено (работает в фоне)", "Schedule on (runs in background)"),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    L(
                        "Telefon obyektda Wi-Fi'ga ulangan va quvvatga qo'yilgan holda qolishi kerak. Telefon o'chib-yonsa, jadval o'zi qayta ishga tushadi.",
                        "Телефон должен оставаться на объекте в Wi-Fi и на зарядке. После перезагрузки расписание запустится само.",
                        "The phone must stay on site, on Wi-Fi and charging. After a reboot the schedule starts by itself."
                    ),
                    fontSize = 12.sp
                )
                Text(
                    L("Bugun: quyosh chiqishi ", "Сегодня: восход ", "Today: sunrise ") + Engine.hhmm(Engine.sunMinutes(today, true)) +
                        L(", botishi ", ", закат ", ", sunset ") + Engine.hhmm(Engine.sunMinutes(today, false)),
                    fontSize = 13.sp
                )
                if (Engine.lastSchedEvent.isNotEmpty()) Text(L("Oxirgi: ", "Последнее: ", "Last: ") + Engine.lastSchedEvent, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = latT, onValueChange = {
                            latT = it
                            it.toDoubleOrNull()?.let { v -> Engine.lat = v; Studio.save() }
                        },
                        label = { Text(L("Kenglik", "Широта", "Latitude")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonT, onValueChange = {
                            lonT = it
                            it.toDoubleOrNull()?.let { v -> Engine.lon = v; Studio.save() }
                        },
                        label = { Text(L("Uzunlik", "Долгота", "Longitude")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(onClick = { Engine.applyCurrent() }) { Text(L("Hozirgi vaqtga mos holatni qo'llash", "Применить состояние на текущее время", "Apply what should be on now")) }

                Text(L("Yangi voqea", "Новое событие", "New event"), fontWeight = FontWeight.Bold)
                ChoiceRow(listOf(L("Soat", "Время", "Clock"), L("Quyosh botishi", "Закат", "Sunset"), L("Quyosh chiqishi", "Восход", "Sunrise")), mode) { mode = it }
                if (mode == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField(L("Soat", "Час", "Hour"), hh, Modifier.weight(1f)) { hh = it }
                        NumField(L("Daqiqa", "Мин", "Min"), mm, Modifier.weight(1f)) { mm = it }
                    }
                } else {
                    OutlinedTextField(
                        value = off,
                        onValueChange = { off = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text(L("Siljish, daqiqa (-30 = 30 daq oldin)", "Смещение, мин (-30 = за 30 мин)", "Offset, min (-30 = 30 min before)")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayNames().forEachIndexed { i, d ->
                        Choice((days shr i) and 1 == 1, d) { days = days xor (1 shl i) }
                    }
                }
                ChoiceRow(listOf(L("O'chirish", "Выключить", "Off"), L("Shou", "Шоу", "Show"), L("Effekt", "Эффект", "Effect"), L("Sahna", "Сцена", "Scene")), act) {
                    act = it
                    ref = 0
                }
                val refs: List<String> = when (act) {
                    1 -> Engine.shows.map { it.name }
                    2 -> Engine.effects.map { it.name }
                    3 -> Studio.scenes.map { it.name }
                    else -> emptyList()
                }
                if (act != 0) {
                    if (refs.isEmpty()) Text(L("Ro'yxat bo'sh", "Список пуст", "List is empty"), color = Color.Red, fontSize = 12.sp)
                    else ChoiceRow(refs, ref) { ref = it }
                }
                Button(onClick = {
                    val min = if (mode == 0) ((hh.toIntOrNull() ?: 0).coerceIn(0, 23) * 60 + (mm.toIntOrNull() ?: 0).coerceIn(0, 59))
                    else (off.toIntOrNull() ?: 0).coerceIn(-600, 600)
                    if (act == 0 || refs.isNotEmpty()) {
                        Engine.sched.add(Sched(true, mode, min, if (days == 0) 127 else days, act, if (act == 0) 0 else ref.coerceIn(0, refs.size - 1)))
                        Studio.save()
                    }
                }) { Text(L("Jadvalga qo'shish", "Добавить в расписание", "Add to schedule")) }
                Text(L("Voqealar", "События", "Events"), fontWeight = FontWeight.Bold)
            }
        }
        itemsIndexed(Engine.sched.toList()) { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.on, onCheckedChange = {
                    Engine.sched[i] = s.copy(on = it)
                    Studio.save()
                })
                Text(schedText(s), modifier = Modifier.weight(1f).padding(horizontal = 6.dp), fontSize = 13.sp)
                TextButton(onClick = {
                    Engine.sched.removeAt(i)
                    Studio.save()
                }) { Text("X") }
            }
        }
    }
}
