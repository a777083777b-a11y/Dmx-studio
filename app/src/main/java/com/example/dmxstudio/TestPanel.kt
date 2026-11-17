package com.example.dmxstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

// Vertical fader: drag up = brighter, drag down = darker
@Composable
fun Fader(label: String, value: Int, color: Color, onChange: (Int) -> Unit) {
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Text((value * 100 / 255).toString() + "%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.width(46.dp).height(190.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.dp, Color.Gray, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var acc = cur.toFloat()
                        var lastY = down.position.y
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val dy = ch.position.y - lastY
                            lastY = ch.position.y
                            acc = (acc - dy / h * 255f).coerceIn(0f, 255f)
                            val v = acc.toInt()
                            if (v != cur) cb(v)
                            ch.consume()
                        }
                    }
                }
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .fillMaxHeight(value / 255f)
                    .background(color)
            )
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("0", fontSize = 11.sp, modifier = Modifier.clickable { onChange(0) }.padding(4.dp))
            Text("F", fontSize = 11.sp, modifier = Modifier.clickable { onChange(255) }.padding(4.dp))
        }
    }
}

private fun textOn(c: Int): Color {
    val lum = 0.3 * rOf(c) + 0.59 * gOf(c) + 0.11 * bOf(c)
    return if (lum > 140) Color.Black else Color.White
}

@Composable
fun TestScreen() {
    var mode by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Chips(listOf(L("Faderlar", "Фейдеры", "Faders"), L("Avto test", "Авто тест", "Auto test")), mode) {
                mode = it
                Studio.stopTest()
            }
        }
        if (mode == 0) FaderTest() else AutoTestScreen()
    }
}


// Mixed output color of the faders (what the fixture will show)
fun mixColor(): Int {
    val d = Studio.fDim
    val w = Studio.fW
    return rgbOf(
        minOf(255, (Studio.fR + w) * d / 255),
        minOf(255, (Studio.fG + w) * d / 255),
        minOf(255, (Studio.fB + w) * d / 255)
    )
}

class TestUi {
    var view by mutableStateOf(0)
    var selChan by mutableStateOf(-1)
    var uIdx by mutableStateOf(0)
    var found by mutableStateOf(-1) // highlighted fixture id from search
}

@Composable
fun FaderTest() {
    val ui = remember { TestUi() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            tick += 1
        }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val unis = Studio.fixtures.map { it.universe }.distinct().sorted().ifEmpty { listOf(1) }
    val u = unis[ui.uIdx.coerceIn(0, unis.size - 1)]

    // Jump to a fixture or channel found by search
    val goTo: (Int, Int) -> Unit = { uni, ch ->
        val ix = unis.indexOf(uni)
        if (ix >= 0) ui.uIdx = ix
        val f = Studio.fixtureAt(uni, ch)?.first
        ui.found = f?.id ?: -1
        val target = if (ui.view == 0) {
            if (f == null) -1 else Studio.fixtures.filter { it.universe == uni }.sortedBy { it.address }.indexOfFirst { it.id == f.id }
        } else {
            ui.selChan = ch
            ch - 1
        }
        if (target >= 0) scope.launch { grid.animateScrollToItem(target) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Left: faders (+ color mix, controls)
                Column(
                    Modifier.width(470.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            FaderBank(ui, u)
                        }
                    }
                    MixPanel()
                    ControlPanel(ui, u)
                }
                // Right: fixtures / addresses grid (3) and search (2)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UniverseChips(ui, unis)
                    Box(Modifier.weight(1f).fillMaxWidth()) { CellGrid(ui, u, tick, grid) }
                    SearchPanel(goTo)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FaderBank(ui, u)
                MixPanel()
                ControlPanel(ui, u)
                SearchPanel(goTo)
                UniverseChips(ui, unis)
                Box(Modifier.weight(1f).fillMaxWidth()) { CellGrid(ui, u, tick, grid) }
            }
        }
    }
}

private fun upd(block: () -> Unit) {
    block()
    Studio.applyManual()
}

@Composable
fun FaderBank(ui: TestUi, u: Int) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Fader("R", Studio.fR, Color(0xFFE53935)) { v -> upd { Studio.fR = v } }
        Fader("G", Studio.fG, Color(0xFF43A047)) { v -> upd { Studio.fG = v } }
        Fader("B", Studio.fB, Color(0xFF1E88E5)) { v -> upd { Studio.fB = v } }
        Fader("W", Studio.fW, Color(0xFFEEEEEE)) { v -> upd { Studio.fW = v } }
        Fader(L("Yorug'", "Ярк.", "Dim"), Studio.fDim, Color(0xFFFFC107)) { v -> upd { Studio.fDim = v } }
        if (ui.selChan > 0) {
            val k = Studio.key(u, ui.selChan)
            Fader("K" + ui.selChan, Studio.chanOver[k] ?: 0, Color(0xFF9C27B0)) { v ->
                upd { Studio.chanOver[k] = v }
            }
        }
    }
}

// (1) Resulting color + save
@Composable
fun MixPanel() {
    val c = mixColor()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.width(90.dp).height(60.dp).clip(RoundedCornerShape(10.dp))
                .background(cColor(c)).border(1.dp, Color.Gray, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(String.format("#%06X", c), color = textOn(c), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(L("Hosil bo'lgan rang", "Получившийся цвет", "Resulting color"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "R " + Studio.fR + "  G " + Studio.fG + "  B " + Studio.fB + "  W " + Studio.fW + "  " +
                    L("Yorug' ", "Ярк ", "Dim ") + (Studio.fDim * 100 / 255) + "%",
                fontSize = 11.sp
            )
        }
        Button(onClick = {
            Studio.userColors.add(listOf(Studio.fR, Studio.fG, Studio.fB, Studio.fW))
            Studio.save()
        }) { Text(L("Saqlash", "Сохранить", "Save")) }
    }
    if (Studio.userColors.isNotEmpty()) {
        Text(L("Saqlangan ranglar (bosing = qo'llash, × = o'chirish)", "Сохранённые цвета (нажать = применить, × = удалить)", "Saved colors (tap = apply, × = delete)"), fontSize = 11.sp, color = Color.Gray)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Studio.userColors.toList().forEachIndexed { i, uc ->
                val w = uc[3]
                val sc = rgbOf(minOf(255, uc[0] + w), minOf(255, uc[1] + w), minOf(255, uc[2] + w))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Swatch(cColor(sc), 34) {
                        upd { Studio.fR = uc[0]; Studio.fG = uc[1]; Studio.fB = uc[2]; Studio.fW = uc[3] }
                    }
                    Text("×", fontSize = 14.sp, modifier = Modifier.clickable {
                        if (i < Studio.userColors.size) Studio.userColors.removeAt(i)
                        Studio.save()
                    }.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

// (4) Palette, test on/off, view and selection
@Composable
fun ControlPanel(ui: TestUi, u: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (p in PALETTE) Swatch(palColor(p), 30) {
            upd { Studio.fR = p.r; Studio.fG = p.g; Studio.fB = p.b; Studio.fW = p.w }
        }
        Swatch(Color.Black, 30) { upd { Studio.fR = 0; Studio.fG = 0; Studio.fB = 0; Studio.fW = 0 } }
        if (Studio.manual) {
            Button(onClick = { Studio.stopTest() }) { Text(L("Testni o'chirish", "Выкл. тест", "Stop test")) }
        } else {
            Button(onClick = { Studio.applyManual() }) { Text(L("Testni yoqish", "Вкл. тест", "Start test")) }
        }
    }
    Chips(listOf(L("Chiroqlar", "Приборы", "Fixtures"), L("Kanallar 1-512", "Каналы 1-512", "Channels 1-512")), ui.view) { ui.view = it }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(onClick = {
            Studio.selected.clear()
            Studio.selected.addAll(Studio.fixtures.filter { it.universe == u }.map { it.id })
            if (Studio.manual) Studio.applyManual()
        }) { Text(L("Hammasi", "Все", "All")) }
        OutlinedButton(onClick = {
            Studio.selected.clear()
            Studio.chanOver.clear()
            ui.selChan = -1
            ui.found = -1
            if (Studio.manual) Studio.applyManual()
        }) { Text(L("Tozalash", "Сброс", "Clear")) }
    }
    Text(
        if (Studio.selected.isEmpty()) L("Tanlanmagan: faderlar hamma chiroqqa ta'sir qiladi", "Ничего не выбрано: фейдеры на все приборы", "Nothing selected: faders drive all fixtures")
        else L("Tanlangan: ", "Выбрано: ", "Selected: ") + Studio.selected.size,
        fontSize = 11.sp, color = Color.Gray
    )
}

@Composable
fun UniverseChips(ui: TestUi, unis: List<Int>) {
    if (unis.size > 1) Chips(unis.map { "U$it" }, ui.uIdx) { ui.uIdx = it }
}

// (2) Search address / fixture
@Composable
fun SearchPanel(goTo: (Int, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    val results: List<Fixture> = remember(q, Studio.fixtures.size) {
        val t = q.trim()
        if (t.isEmpty()) emptyList() else {
            val parts = t.split('.', ',', ' ', '/').filter { it.isNotEmpty() }
            val nums = parts.mapNotNull { it.toIntOrNull() }
            if (nums.size == parts.size && nums.size == 2) {
                // "2.45" = universe 2, address 45
                listOfNotNull(Studio.fixtureAt(nums[0], nums[1])?.first)
            } else if (nums.size == parts.size && nums.size == 1) {
                Studio.fixtures.filter { nums[0] >= it.address && nums[0] < it.address + it.channels }
                    .sortedWith(compareBy({ it.universe }, { it.address }))
            } else {
                Studio.fixtures.filter { it.name.contains(t, ignoreCase = true) }
                    .sortedWith(compareBy({ it.universe }, { it.address }))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            label = { Text(L("Qidirish: adres (45 yoki 2.45) yoki nom", "Поиск: адрес (45 или 2.45) или имя", "Search: address (45 or 2.45) or name")) },
            modifier = Modifier.fillMaxWidth()
        )
        if (q.isNotBlank() && results.isEmpty()) {
            Text(L("Topilmadi", "Не найдено", "Not found"), fontSize = 12.sp, color = Color.Red)
        }
        for (f in results.take(5)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(cColor(Studio.outColor(f)), 18)
                Text(
                    "  " + f.name + "   U" + f.universe + "  " + f.address + "-" + (f.address + f.channels - 1),
                    modifier = Modifier.weight(1f), fontSize = 13.sp
                )
                TextButton(onClick = {
                    val num = q.trim().split('.', ',', ' ', '/').lastOrNull()?.toIntOrNull()
                    val ch = if (num != null && num >= f.address && num < f.address + f.channels) num else f.address
                    goTo(f.universe, ch)
                }) { Text(L("Ko'rsatish", "Показать", "Show")) }
                TextButton(onClick = {
                    if (f.id !in Studio.selected) Studio.selected.add(f.id)
                    if (Studio.manual) Studio.applyManual()
                }) { Text(L("Tanlash", "Выбрать", "Select")) }
                TextButton(onClick = { Studio.identify(f) }) { Text(L("Miltillat", "Мигнуть", "Flash")) }
            }
        }
        if (results.size > 5) Text("+" + (results.size - 5), fontSize = 11.sp, color = Color.Gray)
    }
}

// (3) Fixtures / addresses grid
@Composable
fun CellGrid(ui: TestUi, u: Int, tick: Int, grid: LazyGridState) {
    val t = tick
    if (ui.view == 0) {
        val fx = Studio.fixtures.filter { it.universe == u }.sortedBy { it.address }
        if (fx.isEmpty()) {
            Text(L("Bu universe'da chiroq yo'q. Patch bo'limida qo'shing.", "В этом universe нет приборов. Добавьте в Patch.", "No fixtures in this universe. Add them in Patch."))
            return
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(68.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(fx, key = { it.id }) { f ->
                val c = Studio.outColor(f) + t * 0
                val sel = f.id in Studio.selected
                val hit = f.id == ui.found
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                        .background(cColor(c))
                        .border(
                            if (sel || hit) 3.dp else 1.dp,
                            if (hit) Color(0xFFFF9800) else if (sel) Color(0xFF2196F3) else Color(0xFF555555),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { Studio.toggleSelect(f.id) }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(f.address.toString(), color = textOn(c), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (f.channels > 1) f.address.toString() + "-" + (f.address + f.channels - 1) else "1ch",
                            color = textOn(c), fontSize = 9.sp, textAlign = TextAlign.Center
                        )
                        Text(f.name, color = textOn(c), fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(46.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items((1..512).toList(), key = { it }) { ch ->
                val v = Studio.outValue(u, ch) + t * 0
                val fr = Studio.fixtureAt(u, ch)
                val base = when (fr?.second) {
                    0 -> rgbOf(255, 0, 0)
                    1 -> rgbOf(0, 255, 0)
                    2 -> rgbOf(0, 90, 255)
                    3 -> rgbOf(255, 255, 255)
                    -1 -> rgbOf(200, 160, 0)
                    else -> rgbOf(70, 70, 70)
                }
                val k = 0.15f + 0.85f * v / 255f
                val c = if (fr == null) rgbOf(30, 30, 30) else rgbOf((rOf(base) * k).toInt(), (gOf(base) * k).toInt(), (bOf(base) * k).toInt())
                val isSel = ch == ui.selChan
                val ownerSel = fr != null && (fr.first.id in Studio.selected || fr.first.id == ui.found)
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                        .background(cColor(c))
                        .border(
                            if (isSel || ownerSel) 2.dp else 1.dp,
                            if (isSel) Color(0xFFE040FB) else if (ownerSel) Color(0xFF2196F3) else Color(0xFF444444),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { ui.selChan = if (ui.selChan == ch) -1 else ch },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ch.toString(), color = textOn(c), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(v.toString(), color = textOn(c), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
