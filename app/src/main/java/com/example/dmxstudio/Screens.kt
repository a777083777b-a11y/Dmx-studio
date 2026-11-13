package com.example.dmxstudio

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
    val names = listOf("Pult", "Patch", "Sahnalar", "Chase", "Sozlama")
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
                        Button(onClick = { Studio.blackout = false }) { Text("Blackout: YOQILGAN") }
                    } else {
                        OutlinedButton(onClick = { Studio.blackout = true }) { Text("Blackout") }
                    }
                    OutlinedButton(onClick = { Studio.allSet(255) }) { Text("Hammasi 100%") }
                    OutlinedButton(onClick = { Studio.allSet(0) }) { Text("Hammasi 0") }
                }
                Text("Paketlar: " + Studio.packets + "  ->  " + Studio.ip, fontSize = 12.sp)
                if (Studio.lastError.isNotEmpty()) {
                    Text("Xato: " + Studio.lastError, color = Color.Red, fontSize = 12.sp)
                }
            }
        }
        if (Studio.fixtures.isEmpty()) {
            item { Text("Avval Patch bo'limida chiroq qo'shing.") }
        }
        items(Studio.fixtures.toList(), key = { it.id }) { f -> FixtureCard(f) }
    }
}

@Composable
fun FixtureCard(f: Fixture) {
    val labels: List<String> = when (f.channels) {
        1 -> listOf("Yorug'lik")
        3 -> listOf("R", "G", "B")
        4 -> listOf("R", "G", "B", "W")
        else -> (1..f.channels).map { "K" + it }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(f.name + "  |  U" + f.universe + "." + f.address, fontWeight = FontWeight.Bold)
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
    var name by remember { mutableStateOf("Chiroq") }
    var uni by remember { mutableStateOf("1") }
    var addr by remember { mutableStateOf("1") }
    var ch by remember { mutableStateOf("4") }
    var cnt by remember { mutableStateOf("1") }
    var msg by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Yangi chiroq qo'shish", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nomi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Universe", uni, Modifier.weight(1f)) { uni = it }
                    NumField("Manzil", addr, Modifier.weight(1f)) { addr = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Kanal soni", ch, Modifier.weight(1f)) { ch = it }
                    NumField("Nechta", cnt, Modifier.weight(1f)) { cnt = it }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { ch = "1" }) { Text("1 kanal") }
                    OutlinedButton(onClick = { ch = "3" }) { Text("RGB (3)") }
                    OutlinedButton(onClick = { ch = "4" }) { Text("RGBW (4)") }
                }
                Button(onClick = {
                    val u = uni.toIntOrNull() ?: 1
                    val a = addr.toIntOrNull() ?: 1
                    val c = ch.toIntOrNull() ?: 1
                    val n = cnt.toIntOrNull() ?: 1
                    if (u < 1 || u > 32768 || a < 1 || c < 1 || n < 1 || a + c - 1 > 512) {
                        msg = "Qiymatlar noto'g'ri (manzil 1..512)"
                    } else {
                        Studio.addFixtures(if (name.isBlank()) "Chiroq" else name, u, a, c, n)
                        addr = Studio.nextFreeAddress(u).toString()
                        msg = ""
                    }
                }) { Text("Qo'shish") }
                if (msg.isNotEmpty()) Text(msg, color = Color.Red)
                Text("Qizil = manzillar bir-biriga tushib qolgan.", fontSize = 12.sp)
            }
        }
        items(Studio.fixtures.toList(), key = { it.id }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.name + "   U" + f.universe + "   " + f.address + "-" + (f.address + f.channels - 1),
                    modifier = Modifier.weight(1f),
                    color = if (Studio.overlaps(f)) Color.Red else Color.Unspecified
                )
                TextButton(onClick = { Studio.removeFixture(f) }) { Text("X") }
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
                    label = { Text("Sahna nomi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                NumField("Fade (ms)", fade, Modifier.fillMaxWidth()) { fade = it }
                Button(onClick = {
                    Studio.saveScene(name, -1)
                    name = ""
                }) { Text("Joriy holatni saqlash") }
            }
        }
        itemsIndexed(Studio.scenes.toList()) { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name + "  (" + s.values.size + " kanal)", fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(onClick = { Studio.recall(i, fade.toIntOrNull() ?: 0) }) { Text("Yoqish") }
                        OutlinedButton(onClick = { Studio.saveScene("", i) }) { Text("Yangilash") }
                        OutlinedButton(onClick = { Studio.deleteScene(i) }) { Text("O'chirish") }
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
                    label = { Text("Chase nomi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Ushlab turish (ms)", hold, Modifier.weight(1f)) { hold = it }
                    NumField("Fade (ms)", fade, Modifier.weight(1f)) { fade = it }
                }
                Button(onClick = {
                    Studio.addChase(name)
                    name = ""
                }) { Text("Chase yaratish") }
                if (Studio.scenes.isEmpty()) Text("Avval Sahnalar bo'limida sahna yarating.")
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
                    Text("Qadam qo'shish:", fontSize = 12.sp)
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
                            Button(onClick = { Studio.stopChase() }) { Text("To'xtatish") }
                        } else {
                            Button(onClick = { Studio.playChase(ci) }) { Text("Boshlash") }
                        }
                        OutlinedButton(onClick = { Studio.removeChase(ci) }) { Text("O'chirish") }
                    }
                }
            }
        }
    }
}

// ---------------- SOZLAMA ----------------
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Art-Net sozlamalari", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = Studio.ip,
            onValueChange = {
                Studio.ip = it
                Studio.save()
            },
            label = { Text("IP (hammaga: 255.255.255.255)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        NumField("Universe siljishi (Art-Net 0 dan boshlanadi)", Studio.u0.toString(), Modifier.fillMaxWidth()) {
            Studio.u0 = it.toIntOrNull() ?: 0
            Studio.save()
        }
        Text(
            "Eslatma: Art-Net faqat Wi-Fi/Ethernet orqali ishlaydi. Qurilma (yoki Art-Net -> DMX node) " +
                "telefon bilan bir tarmoqda bo'lishi kerak. Universe 1 = Art-Net port-address 0 (siljish 0 bo'lsa).",
            fontSize = 12.sp
        )
        Button(onClick = {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, Studio.toJson().toString(2))
            ctx.startActivity(Intent.createChooser(i, "Loyihani ulashish"))
        }) { Text("Loyihani eksport (ulashish)") }
        OutlinedButton(onClick = {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                Studio.load(t)
                Studio.save()
                msg = "Yuklandi"
            } catch (e: Exception) {
                msg = "Xato: " + e.message
            }
        }) { Text("Importni clipboarddan") }
        if (msg.isNotEmpty()) Text(msg)
    }
}
