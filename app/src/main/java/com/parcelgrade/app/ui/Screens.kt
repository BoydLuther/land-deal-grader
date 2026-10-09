@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.parcelgrade.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parcelgrade.app.data.AiResearch
import com.parcelgrade.app.data.Settings
import com.parcelgrade.app.data.Step
import com.parcelgrade.app.data.StepStatus
import com.parcelgrade.app.domain.Factor
import com.parcelgrade.app.domain.Report
import com.parcelgrade.app.domain.Scoring
import com.parcelgrade.app.domain.Severity
import com.parcelgrade.app.ui.theme.GradeColors

// ---------- Shared bits -----------------------------------------------------------------------

@Composable
private fun gradeColor(letter: String): Color = GradeColors.forLetter(letter, isSystemInDarkTheme())

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun GradeBadge(letter: String, size: Int = 40) {
    val c = gradeColor(letter)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f))
            .border(1.5.dp, c, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = c, fontWeight = FontWeight.Bold, fontSize = (size * 0.42).sp,
            fontFamily = MaterialTheme.typography.titleLarge.fontFamily)
    }
}

@Composable
private fun openUrl(): (String) -> Unit {
    val h = LocalUriHandler.current
    return { url -> runCatching { h.openUri(url) } }
}

private fun parseNumber(s: String): Double? = s.replace(Regex("[$,\\s]"), "").toDoubleOrNull()?.takeIf { it > 0 }

// ---------- Home ------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: AppViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var acres by rememberSaveable { mutableStateOf("") }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val submit = { vm.analyze(address, parseNumber(price), parseNumber(acres)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Parcel Grade", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { vm.go(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                Text("Grade a land deal", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter the property address. You'll get an A–F grade covering access, wetlands, flood zone, slope, soils, price against recent sales, and city plans.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Property address") },
                    placeholder = { Text("123 County Rd, Suffolk, VA 23434") },
                    supportingText = { Text("No street number? Enter coordinates, e.g. 36.72, -76.58") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showMore = !showMore }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showMore) "Hide price & acreage" else "Know the price or acreage? (optional)")
                    Icon(if (showMore) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
                AnimatedVisibility(showMore) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = price, onValueChange = { price = it }, label = { Text("Asking price") },
                            prefix = { Text("$") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = acres, onValueChange = { acres = it }, label = { Text("Acres") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = submit,
                    enabled = address.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Grade this land", fontSize = 16.sp) }

                if (vm.settings.anthropicKey.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().clickable { vm.go(Screen.SETTINGS) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Add your Claude API key in Settings to find the listing, recent land sales and city plans. Without it you still get the site checks (flood, wetlands, access, slope, soils).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (vm.recents.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionLabel("Recent searches") }
                        TextButton(onClick = { vm.clearRecents() }) { Text("Clear") }
                    }
                }
            }
            items(vm.recents) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { address = r.address; vm.analyze(r.address, null, null) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GradeBadge(r.grade, 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.address, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("Score ${r.score}/100 · tap to re-run", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------- Progress --------------------------------------------------------------------------

@Composable
fun ProgressScreen(vm: AppViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Analyzing", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = { vm.cancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(vm.currentAddress, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.settings.anthropicKey.isNotBlank()) "Web research on listings and sales can take a minute or two."
                else "Running site checks with public data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Step.values().forEach { s ->
                val (status, note) = vm.steps[s] ?: (StepStatus.WAITING to null)
                Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when (status) {
                            StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            StepStatus.DONE -> Icon(Icons.Default.Check, "Done", tint = gradeColor("A"))
                            StepStatus.FAILED -> Icon(Icons.Default.Close, "Failed", tint = MaterialTheme.colorScheme.error)
                            StepStatus.SKIPPED -> Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StepStatus.WAITING -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outline))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            s.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (status == StepStatus.WAITING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        if (note != null && status != StepStatus.DONE || (s == Step.LOCATE && note != null)) {
                            Text(note ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            vm.error?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(msg, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.go(Screen.HOME) }) { Text("Back to search") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- Report ----------------------------------------------------------------------------

private fun shareText(r: Report): String = buildString {
    appendLine("Land deal grade: ${r.grade} (${r.overallScore.toInt()}/100)")
    appendLine(r.geocode.matchedAddress)
    appendLine(r.verdict)
    r.factors.forEach { f ->
        appendLine("• ${f.name}: ${f.score?.let { Scoring.letter(it) } ?: "n/a"} – ${f.headline}")
    }
    r.listing?.url?.let { appendLine("Listing: $it") }
    append("Graded with Parcel Grade")
}

@Composable
fun ReportScreen(vm: AppViewModel) {
    val r = vm.report ?: return
    val context = LocalContext.current
    val open = openUrl()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Report card", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = { vm.go(Screen.HOME) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(r))
                        context.startActivity(Intent.createChooser(send, "Share report"))
                    }) { Icon(Icons.Default.Share, "Share") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            item { ReportHeader(r) }
            item { KeyNumbers(r) }
            if (r.flags.isNotEmpty()) {
                item { SectionLabel("What to watch") }
                items(r.flags) { f ->
                    val tint = when (f.severity) {
                        Severity.BAD -> gradeColor("F")
                        Severity.WARN -> gradeColor("C")
                        Severity.GOOD -> gradeColor("A")
                        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (f.severity == Severity.INFO || f.severity == Severity.GOOD) Icons.Default.Info else Icons.Default.Warning,
                            null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(f.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { SectionLabel("Grades by factor") }
            items(r.factors) { FactorCard(it) }

            r.aiSummary?.let { s ->
                item { SectionLabel("Analyst summary") }
                item { Text(s, style = MaterialTheme.typography.bodyMedium) }
            }

            if (r.listing != null || r.comps.isNotEmpty()) {
                item { SectionLabel("Listing & recent sales") }
                r.listing?.let { l ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                .clickable(enabled = l.url != null) { l.url?.let(open) },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("This property${l.source?.let { " on $it" } ?: ""}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        l.askingPrice?.let { Scoring.money(it) },
                                        l.acres?.let { "${Scoring.fmtAcres(it)} ac" },
                                        l.status, l.listedDate,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                l.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (l.url != null) Text("Open listing ↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                items(r.comps) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = c.url != null) { c.url?.let(open) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.address, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(c.acres?.let { "${Scoring.fmtAcres(it)} ac" }, Scoring.money(c.price), c.status, c.date).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${Scoring.money(c.pricePerAcre)}/ac", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
            }

            if (r.cityPlans.isNotEmpty()) {
                item { SectionLabel("City & county plans") }
                items(r.cityPlans) { p ->
                    val c = when (p.impact.lowercase()) { "positive" -> gradeColor("A"); "negative" -> gradeColor("F"); else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    Column(Modifier.fillMaxWidth().clickable(enabled = p.url != null) { p.url?.let(open) }.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.impact.uppercase(), color = c, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Text(p.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(p.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionLabel("Look it up yourself") }
            items(r.links) { l ->
                Row(Modifier.fillMaxWidth().clickable { open(l.url) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text("↗", color = MaterialTheme.colorScheme.primary)
                }
            }

            if (r.sources.isNotEmpty()) {
                item { SectionLabel("Sources used in research") }
                items(r.sources.take(15)) { (title, url) ->
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { open(url) }.padding(vertical = 6.dp))
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Screening tool only. Data comes from public maps (FEMA, USFWS, USGS, USDA, OpenStreetMap) and web research, which can be incomplete or out of date. Confirm boundaries, access, wetlands and zoning with a survey, title search and the county before you buy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportHeader(r: Report) {
    val c = gradeColor(r.grade)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).background(c.copy(alpha = 0.12f))
                .border(2.dp, c, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(r.grade, style = MaterialTheme.typography.displayLarge, color = c, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("${r.overallScore.toInt()} / 100", style = MaterialTheme.typography.titleLarge)
            Text(r.verdict, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(r.geocode.matchedAddress, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(
        listOfNotNull(r.geocode.county, r.geocode.state, "%.5f, %.5f".format(r.geocode.point.lat, r.geocode.point.lon)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    r.gradeCap?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = gradeColor("F"))
    }
    if (r.siteOnly) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Site-only grade: no asking price or comparable sales were found, so price isn't part of this grade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyNumbers(r: Report) {
    val items = listOfNotNull(
        (if (r.parcel.isEstimated) "Acres (est.)" else "Acres") to Scoring.fmtAcres(r.acres),
        "Usable acres" to Scoring.fmtAcres(r.usableAcres),
        "Asking" to Scoring.money(r.askingPrice),
        "Price / acre" to Scoring.money(r.pricePerAcre),
        "Comps / acre" to Scoring.money(r.compMedianPerAcre),
        r.parcel.zoning?.let { "Zoning" to it },
    )
    SectionLabel("Key numbers")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, value) ->
            Column(
                Modifier.widthIn(min = 100.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FactorCard(f: Factor) {
    var open by remember { mutableStateOf(false) }
    val letter = f.score?.let { Scoring.letter(it) } ?: "–"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(enabled = f.details.isNotEmpty()) { open = !open },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeBadge(letter, 42)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Text("${f.weight}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(f.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (f.details.isNotEmpty()) {
                    Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (open) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 10.dp, start = 54.dp)) {
                    f.details.forEach { d ->
                        Text("• $d", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// ---------- Settings --------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var key by rememberSaveable { mutableStateOf(vm.settings.anthropicKey) }
    var model by rememberSaveable { mutableStateOf(vm.settings.model) }
    var regrid by rememberSaveable { mutableStateOf(vm.settings.regridToken) }
    val open = openUrl()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = { vm.go(Screen.HOME) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            SectionLabel("Listings, sales & city plans")
            Text(
                "Claude searches the web for the listing, comparable land sales, utilities and planning news. Uses your own Anthropic API key; each report typically costs a few cents to about 20¢.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, label = { Text("Claude API key") },
                placeholder = { Text("sk-ant-…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { open("https://console.anthropic.com/settings/keys") }, contentPadding = PaddingValues(0.dp)) {
                Text("Get a key at console.anthropic.com ↗")
            }
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true,
                supportingText = { Text("Default: ${AiResearch.DEFAULT_MODEL}") },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Exact parcel boundaries (optional)")
            Text(
                "Without this, the app estimates the parcel as a square of the listed acreage around the address. A Regrid API token loads the real county boundary, acreage, zoning and assessed value.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = regrid, onValueChange = { regrid = it }, label = { Text("Regrid API token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { open("https://regrid.com/api") }, contentPadding = PaddingValues(0.dp)) {
                Text("About the Regrid parcel API ↗")
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.saveSettings(Settings(key, model.ifBlank { AiResearch.DEFAULT_MODEL }, regrid)) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Save") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Keys are stored only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
