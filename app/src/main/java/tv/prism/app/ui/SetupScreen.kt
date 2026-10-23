package tv.prism.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import tv.prism.app.data.Source
import tv.prism.app.data.StbProfile
import kotlin.random.Random

private enum class Kind(val label: String, val hint: String) {
    Stalker("Portal (MAC)", "The portal URL and MAC address you'd type into STB Emu or a MAG box."),
    Xtream("Xtream login", "Server address, username and password from your provider."),
    M3u("M3U playlist", "A link to an .m3u or .m3u8 playlist."),
}

@Composable
fun SetupScreen(vm: AppViewModel, state: UiState) {
    // New portal sources start blank: a random MAG-style MAC, auto serial, and a current MAG model.
    val defaultPortal = ""
    val defaultSerial = ""
    val defaultModel = "MAG424"
    // Non-null while an existing source is being edited; saving keeps its id (and so its favorites).
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(Kind.Stalker) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(defaultPortal) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf(randomMac()) }
    var serial by rememberSaveable { mutableStateOf(defaultSerial) }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(defaultModel) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun startNew() {
        editingId = null; kind = Kind.Stalker; name = ""; url = defaultPortal; user = ""; pass = ""; mac = randomMac()
        serial = defaultSerial; deviceId = ""; model = defaultModel; error = null
    }

    fun startEdit(s: Source) {
        editingId = s.id; name = s.name; error = null
        when (s) {
            is Source.Stalker -> {
                kind = Kind.Stalker; url = s.portal; mac = s.mac
                serial = s.serial.ifBlank { defaultSerial }; deviceId = s.deviceId; model = s.model
            }
            is Source.Xtream -> { kind = Kind.Xtream; url = s.server; user = s.username; pass = s.password }
            is Source.M3u -> { kind = Kind.M3u; url = s.url }
        }
    }

    BackHandler(enabled = editingId != null || state.active != null) {
        if (editingId != null) startNew() else state.active?.let(vm::switchSource)
    }

    fun save() {
        error = null
        val label = name.ifBlank { url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { kind.label } }
        val id = editingId ?: java.util.UUID.randomUUID().toString()
        val source = when (kind) {
            Kind.Stalker -> {
                if (url.isBlank()) { error = "Enter the portal URL"; return }
                if (!MacAddress.isValid(mac)) { error = "A MAC address has 12 characters, 0–9 and A–F, like 00:1A:79:12:34:56"; return }
                Source.Stalker(id = id, name = label, portal = url.trim(), mac = mac, serial = serial.trim(), deviceId = deviceId.trim(), model = model)
            }
            Kind.Xtream -> {
                if (url.isBlank() || user.isBlank() || pass.isBlank()) { error = "Fill in server, username and password"; return }
                Source.Xtream(id = id, name = label, server = url.trim(), username = user.trim(), password = pass)
            }
            Kind.M3u -> {
                if (url.isBlank()) { error = "Enter the playlist URL"; return }
                Source.M3u(id = id, name = label, url = url.trim())
            }
        }
        vm.addSource(source)
    }

    Row(Modifier.fillMaxSize().appBackground().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(560.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text(if (editingId != null) "Edit source" else "Add a source", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text("Use a service you subscribe to or one that is legally free.", color = Palette.Muted, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        // A saved source keeps its type; to change type, add a new one.
                        enabled = editingId == null || kind == k,
                        onClick = {
                            // The default portal only makes sense for portal sources.
                            if (editingId == null) {
                                if (k != Kind.Stalker && url == defaultPortal) url = ""
                                if (k == Kind.Stalker && url.isBlank()) url = defaultPortal
                            }
                            kind = k; error = null
                        },
                        colors = FilterChipDefaults.colors(
                            selectedContainerColor = Palette.Accent,
                            selectedContentColor = Color.White,
                            focusedSelectedContainerColor = Palette.Accent,
                            focusedSelectedContentColor = Color.White,
                        ),
                    ) { Text(k.label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(kind.hint, color = Palette.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))

            when (kind) {
                Kind.Stalker -> {
                    TvTextField("Portal URL", url, { url = it }, placeholder = "http://portal.example.com:8080/c/")
                    Spacer(Modifier.height(10.dp))
                    TvTextField(
                        label = "MAC address  ·  type the 12 characters, colons are added for you",
                        value = mac,
                        onValueChange = { mac = MacAddress.format(it) },
                        placeholder = "00:1A:79:00:00:00",
                        keyboardType = KeyboardType.Ascii,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { mac = "" }) { Text("Clear") }
                        OutlinedButton(onClick = { mac = "00:1A:79:" }) { Text("MAG prefix 00:1A:79") }
                        OutlinedButton(onClick = { mac = randomMac() }) { Text("New random") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("STB model (match STB Emu → STB configuration → STB Model)", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StbProfile.MODELS) { m ->
                            FilterChip(
                                selected = model == m,
                                onClick = { model = m },
                                colors = FilterChipDefaults.colors(
                                    selectedContainerColor = Palette.Accent,
                                    selectedContentColor = Color.White,
                                    focusedSelectedContainerColor = Palette.Accent,
                                    focusedSelectedContentColor = Color.White,
                                ),
                            ) { Text(m) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            "Serial number (as in STB Emu)", serial, { serial = it.uppercase() },
                            placeholder = "Leave blank to auto-generate", keyboardType = KeyboardType.Ascii,
                            modifier = Modifier.weight(1f),
                        )
                        TvTextField(
                            "Device ID (optional)", deviceId, { deviceId = it },
                            placeholder = "Blank, like STB Emu", keyboardType = KeyboardType.Ascii,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Kind.Xtream -> {
                    TvTextField("Server", url, { url = it }, placeholder = "http://example.com:8080")
                    Spacer(Modifier.height(10.dp))
                    TvTextField("Username", user, { user = it }, keyboardType = KeyboardType.Text)
                    Spacer(Modifier.height(10.dp))
                    TvTextField("Password", pass, { pass = it }, password = true)
                }
                Kind.M3u -> TvTextField("Playlist URL", url, { url = it }, placeholder = "http://example.com/playlist.m3u")
            }
            Spacer(Modifier.height(10.dp))
            TvTextField("Name (optional)", name, { name = it }, placeholder = "Living room IPTV", keyboardType = KeyboardType.Text)

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Palette.Error, fontSize = 15.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::save) { Text(if (editingId != null) "Save changes" else "Save and load channels") }
                if (editingId != null) {
                    OutlinedButton(onClick = ::startNew) { Text("Cancel") }
                    val target = state.sources.firstOrNull { it.id == editingId }
                    if (target != null) {
                        Button(
                            onClick = { vm.removeSource(target); startNew() },
                            colors = ButtonDefaults.colors(containerColor = Palette.Error.copy(alpha = 0.25f), focusedContainerColor = Palette.Error),
                        ) { Text("Remove") }
                    }
                } else if (state.active != null) {
                    OutlinedButton(onClick = { vm.switchSource(state.active) }) { Text("Cancel") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (state.sources.isNotEmpty()) {
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text("Your sources", fontSize = 22.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("OK to watch · Hold OK to edit (MAC, URL, name)", color = Palette.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                state.sources.forEach { s ->
                    ListItem(
                        selected = s.id == editingId || (editingId == null && s.id == state.active?.id),
                        onClick = { vm.switchSource(s) },
                        onLongClick = { startEdit(s) },
                        headlineContent = { Text(s.name) },
                        supportingContent = {
                            Text(
                                when (s) {
                                    is Source.Stalker -> "${s.model} · ${s.mac}" + if (s.serial.isNotBlank()) " · SN ${s.serial}" else ""
                                    is Source.Xtream -> "Xtream · ${s.username}"
                                    is Source.M3u -> "M3U playlist"
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (editingId != null) OutlinedButton(onClick = ::startNew) { Text("+ Add another source") }
            }
        }
    }
}

/** MAG boxes use the 00:1A:79 prefix; many portals only accept it. */
private fun randomMac(): String =
    "00:1A:79:" + (1..3).joinToString(":") { "%02X".format(Random.nextInt(256)) }

/** Lenient MAC entry for a remote: keep hex digits, uppercase them, insert the colons. */
object MacAddress {
    fun format(input: String): String {
        val hex = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(12)
        val pairs = hex.chunked(2).joinToString(":")
        // Typing "00:1A:7" should read naturally, and a trailing colon lets a just-finished pair stand alone.
        return if (hex.length in 2..10 && hex.length % 2 == 0 && input.endsWith(":")) "$pairs:" else pairs
    }

    fun isValid(mac: String): Boolean = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)
}
