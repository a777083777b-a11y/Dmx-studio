package com.example.dmxstudio

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }
    val names = listOf(L("Pult", "Пульт", "Console"), "Patch", L("Sahnalar", "Сцены", "Scenes"), L("Chase", "Чейз", "Chase"), "Test", L("Sozlama", "Настройки", "Settings"))
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            names.forEachIndexed { i, n ->
                if (tab == i) {
                    Button(onClick = { tab = i }) { Text(n) }
                } else {
                    OutlinedButton(onClick = { tab = i }) { Text(n) }
                }
            }
        }
        when (tab) {
            0 -> ConsoleScreen()
            1 -> PatchScreen()
            2 -> ScenesScreen()
            3 -> ChaseScreen()
            4 -> TestScreen()
            else -> SettingsScreen()
        }
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

fun palColor(p: Pal): Color = if (p.r + p.g + p.b == 0) Color.White else Color(p.r, p.g, p.b)

fun palName(p: Pal): String = L(p.uz, p.ru, p.en)

@Composable
fun Swatch(color: Color, size: Int, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    var m = Modifier.size(size.dp).clip(CircleShape).background(color)
        .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF1565C0) else Color.Gray, CircleShape)
    if (onClick != null) m = m.clickable { onClick() }
    Box(m)
}

// ---------------- PULT ----------------
@Composable
fun ConsoleScreen() {
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("Master: " + Studio.master + "%")
                Slider(
                    value = Studio.master.toFloat(),
                    onValueChange = { Studio.master = it.toInt() },
                    valueRange = 0f..100f
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (Studio.blackout) {
                        Button(onClick = { Studio.blackout = false }) { Text(L("Blackout: YOQILGAN", "Blackout: ВКЛ", "Blackout: ON")) }
                    } else {
                        OutlinedButton(onClick = { Studio.blackout = true }) { Text("Blackout") }
                    }
                    OutlinedButton(onClick = { Studio.allSet(255) }) { Text(L("Hammasi 100%", "Все 100%", "All 100%")) }
                    OutlinedButton(onClick = { Studio.allSet(0) }) { Text(L("Hammasi 0", "Все 0", "All 0")) }
                }
                Text(L("Palitra (hammasi)", "Палитра (все)", "Palette (all)"), fontSize = 12.sp)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (pc in PALETTE) Swatch(palColor(pc), 40) { Studio.setColorAll(pc) }
                }
                Text(L("Paketlar: ", "Пакеты: ", "Packets: ") + Studio.packets + "  ->  " + Studio.ip, fontSize = 12.sp)
                if (Studio.lastError.isNotEmpty()) {
                    Text(L("Xato: ", "Ошибка: ", "Error: ") + Studio.lastError, color = Color.Red, fontSize = 12.sp)
                }
            }
        }
        if (Studio.fixtures.isEmpty()) {
            item { Text(L("Avval Patch bo'limida chiroq qo'shing.", "Сначала добавьте приборы во вкладке Patch.", "First add fixtures in the Patch tab.")) }
        }
        items(Studio.fixtures.toList(), key = { it.id }) { f -> FixtureCard(f) }
    }
}

@Composable
fun FixtureCard(f: Fixture) {
    val labels: List<String> = when (f.channels) {
        1 -> listOf(L("Yorug'lik", "Яркость", "Dimmer"))
        3 -> listOf("R", "G", "B")
        4 -> listOf("R", "G", "B", "W")
        else -> (1..f.channels).map { "K" + it }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val c0 = Studio.get(f.universe, f.address)
            val c1 = Studio.get(f.universe, f.address + 1)
            val c2 = Studio.get(f.universe, f.address + 2)
            val c3 = Studio.get(f.universe, f.address + 3)
            val shown = if (f.channels >= 4) Color(minOf(255, c0 + c3), minOf(255, c1 + c3), minOf(255, c2 + c3))
            else if (f.channels == 3) Color(c0, c1, c2)
            else Color(c0, c0, c0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(shown, 32)
                Text(f.name + "  |  U" + f.universe + "." + f.address, fontWeight = FontWeight.Bold)
            }
            if (f.channels >= 3) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (pc in PALETTE) Swatch(palColor(pc), 28) { Studio.setColor(f, pc) }
                }
            }
            for (i in 0 until f.channels) {
                val v = Studio.get(f.universe, f.address + i)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(labels[i] + " " + v, modifier = Modifier.width(90.dp), fontSize = 12.sp)
                    Slider(
                        value = v.toFloat(),
                        onValueChange = { Studio.set(f.universe, f.address + i, it.toInt()) },
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ---------------- PATCH ----------------
@Composable
fun PatchScreen() {
    var name by remember { mutableStateOf("") }
    var uni by remember { mutableStateOf("1") }
    var addr by remember { mutableStateOf("1") }
    var ch by remember { mutableStateOf("4") }
    var cnt by remember { mutableStateOf("1") }
    var msg by remember { mutableStateOf("") }
    var editId by remember { mutableStateOf(-1) }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (editId >= 0) L("Chiroqni tahrirlash (manzil yozish)", "Изменить прибор (адрес)", "Edit fixture (address)") else L("Yangi chiroq qo'shish", "Добавить прибор", "Add fixture"), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(L("Nomi", "Название", "Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Universe", uni, Modifier.weight(1f)) { uni = it }
                    NumField(L("Manzil", "Адрес", "Address"), addr, Modifier.weight(1f)) { addr = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(L("Kanal soni", "Каналов", "Channels"), ch, Modifier.weight(1f)) { ch = it }
                    NumField(L("Nechta", "Сколько", "Count"), cnt, Modifier.weight(1f)) { cnt = it }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { ch = "1" }) { Text(L("1 kanal", "1 канал", "1 channel")) }
                    OutlinedButton(onClick = { ch = "3" }) { Text("RGB (3)") }
                    OutlinedButton(onClick = { ch = "4" }) { Text("RGBW (4)") }
                }
                Button(onClick = {
                    val u = uni.toIntOrNull() ?: 1
                    val a = addr.toIntOrNull() ?: 1
                    val c = ch.toIntOrNull() ?: 1
                    val n = cnt.toIntOrNull() ?: 1
                    if (u < 1 || u > 32768 || a < 1 || c < 1 || n < 1 || a + c - 1 > 512) {
                        msg = L("Qiymatlar noto'g'ri (manzil 1..512)", "Неверные значения (адрес 1..512)", "Invalid values (address 1..512)")
                    } else {
                        val nm = if (name.isBlank()) L("Chiroq", "Прибор", "Fixture") else name
                        if (editId >= 0) {
                            Studio.updateFixture(Fixture(editId, nm, u, a, c))
                            editId = -1
                        } else {
                            Studio.addFixtures(nm, u, a, c, n)
                        }
                        addr = Studio.nextFreeAddress(u).toString()
                        msg = ""
                    }
                }) { Text(if (editId >= 0) L("Saqlash", "Сохранить", "Save") else L("Qo'shish", "Добавить", "Add")) }
                if (editId >= 0) {
                    OutlinedButton(onClick = { editId = -1 }) { Text(L("Bekor qilish", "Отмена", "Cancel")) }
                }
                if (msg.isNotEmpty()) Text(msg, color = Color.Red)
                Text(L("Qizil = manzillar bir-biriga tushib qolgan.", "Красный = адреса пересекаются.", "Red = addresses overlap."), fontSize = 12.sp)
            }
        }
        items(Studio.fixtures.toList(), key = { it.id }) { f ->
            Column {
                Text(
                    f.name + "   U" + f.universe + "   " + f.address + "-" + (f.address + f.channels - 1),
                    color = if (Studio.overlaps(f)) Color.Red else Color.Unspecified
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        editId = f.id
                        name = f.name
                        uni = f.universe.toString()
                        addr = f.address.toString()
                        ch = f.channels.toString()
                        cnt = "1"
                    }) { Text(L("Tahrir", "Изм.", "Edit")) }
                    TextButton(onClick = { Studio.identify(f) }) { Text(L("Aniqlash", "Найти", "Identify")) }
                    TextButton(onClick = { Studio.removeFixture(f) }) { Text("X") }
                }
            }
        }
    }
}

// ---------------- SAHNALAR ----------------
@Composable
fun ScenesScreen() {
    var name by remember { mutableStateOf("") }
    var fade by remember { mutableStateOf("1000") }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(L("Sahna nomi", "Название сцены", "Scene name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                NumField(L("Fade (ms)", "Затухание (мс)", "Fade (ms)"), fade, Modifier.fillMaxWidth()) { fade = it }
                Button(onClick = {
                    Studio.saveScene(name, -1)
                    name = ""
                }) { Text(L("Joriy holatni saqlash", "Сохранить текущее", "Save current state")) }
            }
        }
        itemsIndexed(Studio.scenes.toList()) { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name + "  (" + s.values.size + L(" kanal)", " каналов)", " channels)"), fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(onClick = { Studio.recall(i, fade.toIntOrNull() ?: 0) }) { Text(L("Yoqish", "Включить", "Recall")) }
                        OutlinedButton(onClick = { Studio.saveScene("", i) }) { Text(L("Yangilash", "Обновить", "Update")) }
                        OutlinedButton(onClick = { Studio.deleteScene(i) }) { Text(L("O'chirish", "Удалить", "Delete")) }
                    }
                }
            }
        }
    }
}

// ---------------- CHASE ----------------
@Composable
fun ChaseScreen() {
    var name by remember { mutableStateOf("") }
    var hold by remember { mutableStateOf("1000") }
    var fade by remember { mutableStateOf("500") }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(L("Chase nomi", "Название чейза", "Chase name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(L("Ushlab turish (ms)", "Удержание (мс)", "Hold (ms)"), hold, Modifier.weight(1f)) { hold = it }
                    NumField(L("Fade (ms)", "Затухание (мс)", "Fade (ms)"), fade, Modifier.weight(1f)) { fade = it }
                }
                Button(onClick = {
                    Studio.addChase(name)
                    name = ""
                }) { Text(L("Chase yaratish", "Создать чейз", "Create chase")) }
                if (Studio.scenes.isEmpty()) Text(L("Avval Sahnalar bo'limida sahna yarating.", "Сначала создайте сцену во вкладке «Сцены».", "First create a scene in the Scenes tab."))
            }
        }
        itemsIndexed(Studio.chases.toList()) { ci, c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    c.steps.forEachIndexed { si, st ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val sn = Studio.scenes.getOrNull(st.scene)?.name ?: "?"
                            Text(
                                (si + 1).toString() + ". " + sn + "  " + st.holdMs + "ms / fade " + st.fadeMs + "ms",
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { Studio.removeStep(ci, si) }) { Text("X") }
                        }
                    }
                    Text(L("Qadam qo'shish:", "Добавить шаг:", "Add step:"), fontSize = 12.sp)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Studio.scenes.forEachIndexed { si, s ->
                            OutlinedButton(onClick = {
                                Studio.addStep(ci, si, hold.toIntOrNull() ?: 1000, fade.toIntOrNull() ?: 500)
                            }) { Text(s.name) }
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (Studio.playing == ci) {
                            Button(onClick = { Studio.stopChase() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
                        } else {
                            Button(onClick = { Studio.playChase(ci) }) { Text(L("Boshlash", "Старт", "Play")) }
                        }
                        OutlinedButton(onClick = { Studio.removeChase(ci) }) { Text(L("O'chirish", "Удалить", "Delete")) }
                    }
                }
            }
        }
    }
}

// ---------------- TEST ----------------
@Composable
fun TestScreen() {
    val effects = listOf(
        L("Miltillash", "Мигание", "Blink"),
        L("Yugurish (1 -> oxirgi)", "Бегущий (с 1 до конца)", "Run (1 to last)"),
        L("To'ldirish", "Заполнение", "Fill"),
        L("Ranglar aylanishi", "Смена цветов", "Color cycle")
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(L("Test rejimi", "Тестовый режим", "Test mode"), fontWeight = FontWeight.Bold)
        Text(
            L(
                "Test vaqtida Pult o'chib turadi, faqat test chiqadi. Chiroqlar universe va manzil bo'yicha tartiblanadi.",
                "Во время теста пульт отключён, выводится только тест. Приборы идут по порядку universe и адреса.",
                "While testing, the console is bypassed and only the test is output. Fixtures run in universe/address order."
            ),
            fontSize = 12.sp
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            effects.forEachIndexed { i, n ->
                if (Studio.testEffect == i) {
                    Button(onClick = { Studio.testEffect = i }) { Text(n) }
                } else {
                    OutlinedButton(onClick = { Studio.testEffect = i }) { Text(n) }
                }
            }
        }
        Text(L("Rang", "Цвет", "Color") + ": " + palName(PALETTE[Studio.testColor.coerceIn(0, PALETTE.size - 1)]), fontSize = 12.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PALETTE.forEachIndexed { i, pc ->
                Swatch(palColor(pc), 40, Studio.testColor == i) { Studio.testColor = i }
            }
        }
        Text(L("Yorqinlik: ", "Яркость: ", "Level: ") + Studio.testLevel + "%")
        Slider(
            value = Studio.testLevel.toFloat(),
            onValueChange = { Studio.testLevel = it.toInt() },
            valueRange = 1f..100f
        )
        Text(L("Tezlik (qadam): ", "Скорость (шаг): ", "Speed (step): ") + Studio.testSpeed + " ms")
        Slider(
            value = Studio.testSpeed.toFloat(),
            onValueChange = { Studio.testSpeed = it.toInt() },
            valueRange = 100f..2000f
        )
        NumField(L("Universe (0 = hammasi)", "Universe (0 = все)", "Universe (0 = all)"), Studio.testUniverse.toString(), Modifier.fillMaxWidth()) {
            Studio.testUniverse = it.toIntOrNull() ?: 0
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = Studio.testColumns, onCheckedChange = { Studio.testColumns = it })
            Text(
                L("1 kanalli chiroqlar (kolonna) oq rangda yonsin", "1-канальные (колонны) светят белым", "1-channel fixtures (columns) light white"),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp
            )
        }
        if (Studio.testing) {
            Button(onClick = { Studio.stopTest() }) { Text(L("To'xtatish", "Стоп", "Stop")) }
        } else {
            Button(onClick = { Studio.startTest() }) { Text(L("Testni boshlash", "Запустить тест", "Start test")) }
        }
        if (Studio.fixtures.isEmpty()) {
            Text(L("Avval Patch bo'limida chiroq qo'shing.", "Сначала добавьте приборы во вкладке Patch.", "First add fixtures in the Patch tab."), color = Color.Red)
        }
    }
}

// ---------------- SOZLAMA ----------------
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf(listOf<NodeInfo>()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(L("Til", "Язык", "Language"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val langs = listOf("O'zbek", "Русский", "English")
            langs.forEachIndexed { i, n ->
                if (Studio.lang == i) {
                    Button(onClick = { Studio.lang = i }) { Text(n) }
                } else {
                    OutlinedButton(onClick = {
                        Studio.lang = i
                        Studio.save()
                    }) { Text(n) }
                }
            }
        }
        Text(L("Protokol (tarmoq / RJ-45)", "Протокол (сеть / RJ-45)", "Protocol (network / RJ-45)"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Art-Net", "sACN (E1.31)").forEachIndexed { i, n ->
                if (Studio.protocol == i) {
                    Button(onClick = { Studio.protocol = i }) { Text(n) }
                } else {
                    OutlinedButton(onClick = {
                        Studio.protocol = i
                        Studio.save()
                    }) { Text(n) }
                }
            }
        }
        Text(L("Art-Net sozlamalari", "Настройки Art-Net", "Art-Net settings"), fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = Studio.ip,
            onValueChange = {
                Studio.ip = it
                Studio.save()
            },
            label = { Text(L("IP (hammaga: 255.255.255.255)", "IP (всем: 255.255.255.255)", "IP (broadcast: 255.255.255.255)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        NumField(L("Universe siljishi (Art-Net 0 dan boshlanadi)", "Смещение universe (Art-Net с 0)", "Universe offset (Art-Net starts at 0)"), Studio.u0.toString(), Modifier.fillMaxWidth()) {
            Studio.u0 = it.toIntOrNull() ?: 0
            Studio.save()
        }
        Text(
            L(
                "Eslatma: Art-Net faqat Wi-Fi/Ethernet orqali ishlaydi. Qurilma (yoki Art-Net -> DMX node) telefon bilan bir tarmoqda bo'lishi kerak. Universe 1 = Art-Net port-address 0 (siljish 0 bo'lsa).",
                "Примечание: Art-Net работает только по Wi-Fi/Ethernet. Устройство (или узел Art-Net -> DMX) должно быть в одной сети с телефоном. Universe 1 = port-address 0 (при смещении 0).",
                "Note: Art-Net works over Wi-Fi/Ethernet only. The device (or an Art-Net -> DMX node) must be on the same network as the phone. Universe 1 = port-address 0 (with offset 0)."
            ),
            fontSize = 12.sp
        )
        Button(onClick = {
            scanning = true
            scope.launch {
                nodes = withContext(Dispatchers.IO) {
                    try {
                        Studio.discover(ctx)
                    } catch (e: Exception) {
                        emptyList<NodeInfo>()
                    }
                }
                scanning = false
            }
        }) { Text(if (scanning) L("Qidirilmoqda...", "Поиск...", "Scanning...") else L("Tarmoqdagi qurilmalarni qidirish (Art-Net)", "Найти устройства в сети (Art-Net)", "Scan network for devices (Art-Net)")) }
        for (nd in nodes) {
            OutlinedButton(onClick = {
                Studio.ip = nd.ip
                Studio.save()
            }) { Text(nd.ip + "  " + nd.name) }
        }
        if (!scanning && nodes.isEmpty()) {
            Text(
                L(
                    "Hech narsa topilmasa: qurilma Art-Net'ni qo'llamaydi yoki boshqa tarmoqda. Arlight HX-801RC/HX-803TC uchun Art-Net qo'llab-quvvatlashi tasdiqlanmagan; kerak bo'lsa Art-Net -> DMX node ishlating.",
                    "Если ничего не найдено: устройство не поддерживает Art-Net или в другой сети. Для Arlight HX-801RC/HX-803TC поддержка Art-Net не подтверждена; при необходимости используйте узел Art-Net -> DMX.",
                    "If nothing is found: the device does not support Art-Net or is on another network. Art-Net support on Arlight HX-801RC/HX-803TC is unconfirmed; use an Art-Net -> DMX node if needed."
                ),
                fontSize = 12.sp
            )
        }
        Button(onClick = {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, Studio.toJson().toString(2))
            ctx.startActivity(Intent.createChooser(i, L("Loyihani ulashish", "Поделиться проектом", "Share project")))
        }) { Text(L("Loyihani eksport (ulashish)", "Экспорт проекта (поделиться)", "Export project (share)")) }
        OutlinedButton(onClick = {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                Studio.load(t)
                Studio.save()
                msg = L("Yuklandi", "Загружено", "Loaded")
            } catch (e: Exception) {
                msg = L("Xato: ", "Ошибка: ", "Error: ") + e.message
            }
        }) { Text(L("Importni clipboarddan", "Импорт из буфера обмена", "Import from clipboard")) }
        if (msg.isNotEmpty()) Text(msg)
    }
}
