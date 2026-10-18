@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravemanagementllc.ravephotoeditor.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravemanagementllc.ravephotoeditor.edit.Adjust
import com.ravemanagementllc.ravephotoeditor.edit.Backdrop
import com.ravemanagementllc.ravephotoeditor.edit.EditorViewModel
import com.ravemanagementllc.ravephotoeditor.edit.Filter
import com.ravemanagementllc.ravephotoeditor.edit.UiState
import kotlin.math.min
import kotlin.math.roundToInt

private val TOOLS = listOf("AI Cutout", "Portrait", "Backdrop", "Enhance", "Filters", "Adjust")

private val SWATCHES = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Black" to 0xFF000000.toInt(),
    "Slate" to 0xFF334155.toInt(),
    "Sky" to 0xFF38BDF8.toInt(),
    "Blush" to 0xFFFBCFE8.toInt(),
    "Mint" to 0xFFA7F3D0.toInt(),
    "Sun" to 0xFFFDE68A.toInt(),
    "Violet" to 0xFF7C3AED.toInt()
)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val pick: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAVE Photo Editor", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (ui.source != null) {
                        IconButton(onClick = pick, enabled = ui.busy == null) {
                            Icon(Icons.Filled.Add, contentDescription = "Open another photo")
                        }
                        IconButton(onClick = { vm.share { context.startActivity(it) } }, enabled = ui.busy == null) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        TextButton(onClick = { vm.save() }, enabled = ui.busy == null) {
                            Text("Save", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ui.source == null) EmptyState(onPick = pick, busy = ui.busy)
            else Editor(ui, vm)
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, busy: String?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Edit photos with on-device AI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Remove or blur backgrounds, swap in a new colour, enhance in one tap and add filters. " +
                "Your photos are processed on your phone and never uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, enabled = busy == null) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose a photo")
        }
        if (busy != null) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Editor(ui: UiState, vm: EditorViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            val shown = if (showOriginal) ui.source else ui.output
            if (shown != null) {
                PhotoView(shown, checker = !showOriginal && ui.edit.backdrop == Backdrop.Transparent)
            }
            if (showOriginal) {
                Text(
                    "Original",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (ui.busy != null) BusyOverlay(ui.busy)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Press and hold the photo to compare",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { vm.resetAll() }, enabled = !ui.edit.isOriginal && ui.busy == null) {
                Text("Reset all")
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            TOOLS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp)
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> CutoutPanel(ui, vm)
                1 -> PortraitPanel(ui, vm)
                2 -> BackdropPanel(ui, vm)
                3 -> EnhancePanel(ui, vm)
                4 -> FiltersPanel(ui, vm)
                else -> AdjustPanel(ui, vm)
            }
        }
    }
}

@Composable
private fun PhotoView(bitmap: Bitmap, checker: Boolean) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / image.width, size.height / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        if (checker) {
            val cell = 12.dp.toPx()
            clipRect(left, top, left + w, top + h) {
                var y = top
                var row = 0
                while (y < top + h) {
                    var x = left
                    var col = row % 2
                    while (x < left + w) {
                        drawRect(
                            color = if (col % 2 == 0) Color(0xFF2A2D36) else Color(0xFF3A3E4A),
                            topLeft = Offset(x, y),
                            size = Size(cell, cell)
                        )
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            }
        }
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.High
        )
    }
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CutoutPanel(ui: UiState, vm: EditorViewModel) {
    val removed = ui.edit.backdrop == Backdrop.Transparent
    Column {
        PanelHint("On-device AI finds your subject and removes everything else. Saves as a transparent PNG.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.setBackdrop(Backdrop.Transparent) }, enabled = !removed && ui.busy == null) {
                Text("Remove background")
            }
            OutlinedButton(onClick = { vm.setBackdrop(Backdrop.Keep) }, enabled = ui.edit.backdrop != Backdrop.Keep) {
                Text("Restore")
            }
        }
    }
}

@Composable
private fun PortraitPanel(ui: UiState, vm: EditorViewModel) {
    val current = (ui.edit.backdrop as? Backdrop.Blur)?.strength ?: 0
    val levels = listOf("Off" to 0, "Light" to 1, "Medium" to 2, "Strong" to 3)
    Column {
        PanelHint("Blur the background behind your subject, like a portrait lens.")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEach { (label, level) ->
                FilterChip(
                    selected = current == level,
                    onClick = { vm.setBackdrop(if (level == 0) Backdrop.Keep else Backdrop.Blur(level)) },
                    label = { Text(label) },
                    enabled = ui.busy == null
                )
            }
        }
    }
}

@Composable
private fun BackdropPanel(ui: UiState, vm: EditorViewModel) {
    val selected = (ui.edit.backdrop as? Backdrop.Color)?.argb
    Column {
        PanelHint("Swap the background for a clean colour.")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { vm.setBackdrop(Backdrop.Keep) },
                label = { Text("Off") },
                enabled = ui.busy == null
            )
            SWATCHES.forEach { (name, argb) ->
                val isSelected = selected == argb
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .semantics {
                            contentDescription = "$name background"
                            this.selected = isSelected
                        }
                        .clickable(enabled = ui.busy == null, role = Role.Button) {
                            vm.setBackdrop(Backdrop.Color(argb))
                        }
                )
            }
        }
    }
}

@Composable
private fun EnhancePanel(ui: UiState, vm: EditorViewModel) {
    // The whole row toggles, so the label is a tap target too.
    Row(
        Modifier.toggleable(
            value = ui.edit.enhance,
            enabled = ui.busy == null,
            role = Role.Switch,
            onValueChange = { vm.setEnhance(it) }
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Auto enhance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Balances exposure, contrast and colour in one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = ui.edit.enhance, onCheckedChange = null, enabled = ui.busy == null)
    }
}

@Composable
private fun FiltersPanel(ui: UiState, vm: EditorViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Filter.entries) { f ->
            val selected = ui.edit.filter == f
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { vm.setFilter(f) }
            ) {
                val thumb = ui.thumbnails[f]
                if (thumb != null) {
                    val image = remember(thumb) { thumb.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = f.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    f.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdjustPanel(ui: UiState, vm: EditorViewModel) {
    val a = ui.edit.adjust
    Column {
        AdjustSlider("Brightness", a.brightness) { vm.setAdjust(a.copy(brightness = it)) }
        AdjustSlider("Contrast", a.contrast) { vm.setAdjust(a.copy(contrast = it)) }
        AdjustSlider("Saturation", a.saturation) { vm.setAdjust(a.copy(saturation = it)) }
        AdjustSlider("Warmth", a.warmth) { vm.setAdjust(a.copy(warmth = it)) }
        TextButton(onClick = { vm.setAdjust(Adjust()) }, enabled = !a.isNeutral) { Text("Reset adjustments") }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(84.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f)
        )
    }
}
