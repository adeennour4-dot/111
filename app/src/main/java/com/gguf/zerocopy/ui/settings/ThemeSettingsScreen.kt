package com.gguf.zerocopy.ui.settings

import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ThemeManagerInstance
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.theme.AnimationIntensity
import com.gguf.zerocopy.ui.theme.CustomColors
import com.gguf.zerocopy.ui.theme.DensityLevel
import com.gguf.zerocopy.ui.theme.PresetThemes
import com.gguf.zerocopy.ui.theme.ShapeStyle
import com.gguf.zerocopy.ui.theme.ShapeTokens
import com.gguf.zerocopy.ui.theme.ThemeConfig
import com.gguf.zerocopy.ui.theme.ThemeManager
import com.gguf.zerocopy.ui.theme.ZcShape
import com.gguf.zerocopy.ui.theme.ZcSpace
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = ThemeManagerInstance.instance
    val config by manager.config.collectAsState()
    val colors = currentPalette()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var showCustomColorPicker by remember { mutableStateOf(false) }
    var customColorTarget by remember { mutableStateOf<CustomColorTarget>(CustomColorTarget.Primary) }
    var showShapeEditor by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    enum class CustomColorTarget {
        Primary, Secondary, Tertiary, Error, Background, Surface, Outline
    }

    @Composable
    fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = colors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.Accent, letterSpacing = 1.5.sp)
        }
    }

    @Composable
    fun SettingCard(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit
    ) {
        Card(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 0.dp,
            shape = ZcShape.Lg,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.Border.copy(alpha = 0.3f)),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colors.Card,
                contentColor = colors.Text
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) { content() }
        }
    }

    @Composable
    fun SliderRow(
        label: String,
        subtitle: String?,
        value: Float,
        onValueChange: (Float) -> Unit,
        min: Float = 0f,
        max: Float = 1f,
        steps: Int = 100
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 14.sp, color = colors.Text)
                    subtitle?.let { Text(it, fontSize = 11.sp, color = colors.Text3) }
                }
                Text("%.0f%%".format(value * 100), fontSize = 12.sp, color = colors.Accent, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = onValueChange,
                valueRange = min..max,
                steps = steps,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = colors.Accent,
                    trackColor = colors.Accent.copy(alpha = 0.3f),
                    activeTrackColor = colors.Accent,
                    inactiveTrackColor = colors.Border
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance", fontWeight = FontWeight.Bold, color = colors.Text) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text) } },
                actions = {
                    IconButton(onClick = { showPresetDialog = true }) {
                        Icon(Icons.Filled.Palette, "Presets", tint = colors.Accent)
                    }
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Filled.Restore, "Reset", tint = colors.Text3)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.Bg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ════════════════════════════════════════════════════════════════════════
            // THEME MODE
            // ═════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Theme Mode", Icons.Filled.Brightness7)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf(
                            "System" to "system" to Icons.Filled.BrightnessAuto,
                            "Light" to "light" to Icons.Filled.Brightness7,
                            "Dark" to "dark" to Icons.Filled.Brightness4
                        ).forEach { (label, mode, icon) ->
                            val selected = SettingsManager.themeMode == mode
                            Card(
                                modifier = Modifier.weight(1f).height(90.dp).fillMaxWidth(),
                                onClick = { SettingsManager.themeMode = mode },
                                elevation = if (selected) 4.dp else 0.dp,
                                shape = ZcShape.Lg,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 0.5.dp,
                                    if (selected) colors.Accent else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) colors.Accent.copy(alpha = 0.1f) else colors.Card
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(icon, null, tint = if (selected) colors.Accent else colors.Text2, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) colors.Accent else colors.Text)
                                }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // PRESETS
            // ═══════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SectionHeader("Presets", Icons.Filled.Palette)
                        TextButton(onClick = { showPresetDialog = true }) {
                            Text("View All", color = colors.Accent)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(
                        modifier = Modifier.height(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled = true
                    ) {
                        items(PresetThemes.presets) { preset ->
                            val selected = config.presetIndex == PresetThemes.presets.indexOf(preset) && !config.useCustomColors
                            Card(
                                modifier = Modifier.width(100.dp).height(120.dp).fillMaxHeight(),
                                onClick = { manager.setPreset(PresetThemes.presets.indexOf(preset)) },
                                elevation = if (selected) 4.dp else 0.dp,
                                shape = ZcShape.Lg,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 0.5.dp,
                                    if (selected) preset.colors.primary else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) preset.colors.primary.copy(alpha = 0.1f) else preset.colors.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(preset.colors.primary)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(preset.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) preset.colors.primary else preset.colors.onSurface)
                                        }
                                        if (selected) {
                                            Icon(Icons.Filled.Check, null, tint = preset.colors.primary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(
                                            preset.colors.primary,
                                            preset.colors.secondary,
                                            preset.colors.tertiary,
                                            preset.colors.error
                                        ).forEach { c ->
                                            Box(
                                                modifier = Modifier.size(16.dp).clip(CircleShape).background(c)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // CUSTOM COLORS
            // ═══════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Custom Colors", Icons.Filled.Colorize)
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showCustomColorPicker = true; customColorTarget = CustomColorTarget.Primary }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Edit, null, tint = colors.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Primary", color = colors.Text)
                            }
                        }
                        OutlinedButton(onClick = { showCustomColorPicker = true; customColorTarget = CustomColorTarget.Secondary }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Edit, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Secondary", color = colors.Text)
                            }
                        }
                        OutlinedButton(onClick = { showCustomColorPicker = true; customColorTarget = CustomColorTarget.Tertiary }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Edit, null, tint = colors.Purple, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tertiary", color = colors.Text)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Custom colors apply to both light and dark modes. Use the picker to create your own palette.",
                        fontSize = 11.sp,
                        color = colors.Text3
                    )
                    if (config.useCustomColors && config.customColors != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorSwatch("Primary", config.customColors!!.primary) { }
                            ColorSwatch("Secondary", config.customColors!!.secondary) { }
                            ColorSwatch("Background", config.customColors!!.background) { }
                            ColorSwatch("Surface", config.customColors!!.surface) { }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // DENSITY
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Layout Density", Icons.Filled.Tune)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DensityLevel.values().forEach { level ->
                            val selected = config.density == level
                            Card(
                                modifier = Modifier.weight(1f).height(100.dp).fillMaxWidth(),
                                onClick = { manager.setDensity(level) },
                                elevation = if (selected) 4.dp else 0.dp,
                                shape = ZcShape.Lg,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 0.5.dp,
                                    if (selected) colors.Accent else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) colors.Accent.copy(alpha = 0.1f) else colors.Card
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Filled.TextFields, null, tint = if (selected) colors.Accent else colors.Text2, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(level.label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) colors.Accent else colors.Text)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${(level.factor * 100).toInt()}%", fontSize = 11.sp, color = colors.Text3)
                                }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // ANIMATION INTENSITY
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Animations", Icons.Filled.Speed)
                    Spacer(Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimationIntensity.values().forEach { level ->
                            val selected = config.animationIntensity == level
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { manager.setAnimationIntensity(level) },
                                elevation = if (selected) 2.dp else 0.dp,
                                shape = ZcShape.Md,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 1.5.dp else 0.5.dp,
                                    if (selected) colors.Accent else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) colors.Accent.copy(alpha = 0.08f) else colors.Card
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Speed, null, tint = if (selected) colors.Accent else colors.Text2, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(level.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) colors.Accent else colors.Text)
                                        Text(
                                            when (level) {
                                                AnimationIntensity.NONE -> "No animations"
                                                AnimationIntensity.SUBTLE -> "Minimal, functional only"
                                                AnimationIntensity.NORMAL -> "Balanced, smooth transitions"
                                                AnimationIntensity.PLAYFUL -> "Expressive, delightful"
                                            },
                                            fontSize = 11.sp,
                                            color = colors.Text3
                                        )
                                    }
                                    if (selected) {
                                        Icon(Icons.Filled.Check, null, tint = colors.Accent, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // SHAPES
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Shapes", Icons.Filled.Edit)
                    Spacer(Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShapeStyle.values().filter { it != ShapeStyle.CUSTOM }.forEach { style ->
                            val selected = config.shapeStyle == style
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { manager.setShapeStyle(style) },
                                elevation = if (selected) 2.dp else 0.dp,
                                shape = ZcShape.Md,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 1.5.dp else 0.5.dp,
                                    if (selected) colors.Accent else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) colors.Accent.copy(alpha = 0.08f) else colors.Card
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(style.tokens.small.dp.toShape())
                                            .background(colors.Accent)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(style.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) colors.Accent else colors.Text)
                                        Text(
                                            "Radius: ${style.tokens.small.toInt()}dp",
                                            fontSize = 11.sp,
                                            color = colors.Text3
                                        )
                                    }
                                    if (selected) {
                                        Icon(Icons.Filled.Check, null, tint = colors.Accent, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { showShapeEditor = true }) {
                            Row {
                                Icon(Icons.Filled.Edit, null, tint = colors.Accent)
                                Spacer(Modifier.width(8.dp))
                                Text("Custom Shapes", color = colors.Accent)
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // TYPOGRAPHY SCALE
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Text Size", Icons.Filled.TextFields)
                    Spacer(Modifier.height(16.dp))
                    SliderRow(
                        label = "Typography Scale",
                        subtitle = "Adjust all text sizes proportionally",
                        value = config.typographyScale,
                        onValueChange = { manager.setTypographyScale(it) },
                        min = 0.8f,
                        max = 1.5f
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(0.85f to "Small", 1.0f to "Default", 1.15f to "Large", 1.3f to "Extra Large").forEach { (scale, label) ->
                            val selected = Math.abs(config.typographyScale - scale) < 0.02f
                            Card(
                                modifier = Modifier.weight(1f).height(60.dp),
                                onClick = { manager.setTypographyScale(scale) },
                                elevation = if (selected) 2.dp else 0.dp,
                                shape = ZcShape.Md,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 1.5.dp else 0.5.dp,
                                    if (selected) colors.Accent else colors.Border.copy(alpha = 0.3f)
                                ),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (selected) colors.Accent.copy(alpha = 0.08f) else colors.Card
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Aa", fontSize = 24.sp * scale, fontWeight = FontWeight.Bold, color = colors.Text)
                                    Spacer(Modifier.height(4.dp))
                                    Text(label, fontSize = 11.sp, color = colors.Text3)
                                }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // ACCESSIBILITY
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Accessibility", Icons.Filled.Restore)
                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        label = "Reduce Motion",
                        subtitle = "Disable non-essential animations",
                        checked = config.reducedMotion,
                        onCheckedChange = { manager.setReducedMotion(it) },
                        colors = colors
                    )
                    ToggleRow(
                        label = "High Contrast",
                        subtitle = "Increase color contrast for readability",
                        checked = config.highContrast,
                        onCheckedChange = { manager.setHighContrast(it) },
                        colors = colors
                    )
                    ToggleRow(
                        label = "Dynamic Color (Android 12+)",
                        subtitle = "Match system wallpaper colors",
                        checked = config.dynamicColor,
                        onCheckedChange = { manager.setDynamicColor(it) },
                        colors = colors
                    )
                }
            }

            // ════════════════════════════════════════════════════════════════════════
            // ACCENT COLORS
            // ════════════════════════════════════════════════════════════════════════
            item {
                SettingCard {
                    SectionHeader("Accent Colors", Icons.Filled.Palette)
                    Spacer(Modifier.height(16.dp))
                    Text("Choose your primary accent color family", fontSize = 12.sp, color = colors.Text3)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val accentFamilies = listOf(
                            Color(0xFF6750A4) to "Purple",
                            Color(0xFF2E7D32) to "Green",
                            Color(0xFF0288D1) to "Blue",
                            Color(0xFFF57C00) to "Orange",
                            Color(0xFFE91E63) to "Pink",
                            Color(0xFF00695C) to "Teal",
                            Color(0xFF5D4037) to "Brown",
                            Color(0xFF455A64) to "Blue Grey"
                        )
                        accentFamilies.forEachIndexed { index, (color, name) ->
                            val selected = config.accentColorIndex == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clickable { manager.setAccentColorIndex(index) }
                                    .clip(CircleShape)
                                    .border(if (selected) 3.dp else 0.dp, if (selected) color else colors.Border.copy(alpha = 0.3f))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().background(color))
                                if (selected) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Check, null, tint = if (color.luminance() > 0.5) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Custom color picker dialog
    if (showCustomColorPicker) {
        AlertDialog(
            onDismissRequest = { showCustomColorPicker = false },
            title = { Text("Pick ${customColorTarget.name} Color", color = colors.Text) },
            text = {
                ColorPicker(
                    initialColor = when (customColorTarget) {
                        CustomColorTarget.Primary -> config.customColors?.primary ?: colors.Accent
                        CustomColorTarget.Secondary -> config.customColors?.secondary ?: colors.Accent2
                        CustomColorTarget.Tertiary -> config.customColors?.tertiary ?: colors.Purple
                        CustomColorTarget.Error -> config.customColors?.error ?: colors.Red
                        CustomColorTarget.Background -> config.customColors?.background ?: colors.Bg
                        CustomColorTarget.Surface -> config.customColors?.surface ?: colors.Surface
                        CustomColorTarget.Outline -> config.customColors?.outline ?: colors.Border
                    },
                    onColorSelected = { color ->
                        val newColors = config.customColors ?: (if (config.isDark) PresetThemes.presets[0].colors else PresetThemes.presets[1].colors)
                        val updated = when (customColorTarget) {
                            CustomColorTarget.Primary -> newColors.copy(primary = color)
                            CustomColorTarget.Secondary -> newColors.copy(secondary = color)
                            CustomColorTarget.Tertiary -> newColors.copy(tertiary = color)
                            CustomColorTarget.Error -> newColors.copy(error = color)
                            CustomColorTarget.Background -> newColors.copy(background = color)
                            CustomColorTarget.Surface -> newColors.copy(surface = color)
                            CustomColorTarget.Outline -> newColors.copy(outline = color)
                        }
                        manager.setCustomColors(updated)
                        showCustomColorPicker = false
                    }
                )
            },
            confirmButton = { TextButton(onClick = { showCustomColorPicker = false }) { Text("Done", color = colors.Accent) } },
            containerColor = colors.Card
        )
    }

    // Shape editor dialog
    if (showShapeEditor) {
        AlertDialog(
            onDismissRequest = { showShapeEditor = false },
            title = { Text("Custom Shapes", color = colors.Text) },
            text = {
                ShapeEditor(
                    initialTokens = config.customShapeTokens,
                    onSave = { tokens ->
                        manager.setCustomShapeTokens(tokens)
                        showShapeEditor = false
                    },
                    onCancel = { showShapeEditor = false }
                )
            },
            containerColor = colors.Card
        )
    }

    // Reset confirm dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Theme?", color = colors.Text) },
            text = { Text("This will restore all appearance settings to defaults.", color = colors.Text2) },
            confirmButton = { TextButton(onClick = {
                manager.updateConfig { ThemeConfig() }
                SettingsManager.themeMode = "system"
                showResetConfirm = false
            }) { Text("Reset", color = colors.Red) } },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", color = colors.Text2) } },
            containerColor = colors.Card
        )
    }

    // Preset dialog
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("Choose Preset", color = colors.Text) },
            text = {
                Column(modifier = Modifier.padding(16.dp)) {
                    PresetThemes.presets.forEachIndexed { index, preset ->
                        val selected = config.presetIndex == index && !config.useCustomColors
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { manager.setPreset(index); showPresetDialog = false },
                            elevation = if (selected) 2.dp else 0.dp,
                            shape = ZcShape.Md,
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected) 1.5.dp else 0.5.dp,
                                if (selected) preset.colors.primary else colors.Border.copy(alpha = 0.3f)
                            ),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (selected) preset.colors.primary.copy(alpha = 0.1f) else colors.Card
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        listOf(preset.colors.primary, preset.colors.secondary, preset.colors.tertiary, preset.colors.error).forEach { c ->
                                            Box(modifier = Modifier.size(36.dp / 4).clip(RoundedCornerShape(4.dp)).background(c))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(preset.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.Text)
                                    Text(preset.shapeStyle.label, fontSize = 11.sp, color = colors.Text3)
                                }
                                if (selected) {
                                    Icon(Icons.Filled.Check, null, tint = preset.colors.primary, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            },
            containerColor = colors.Card
        )
    }
}

@Composable
fun ColorSwatch(label: String, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.weight(1f).height(60.dp).fillMaxWidth().clickable { onClick() },
        shape = ZcShape.Md,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = if (color.luminance() > 0.5) Color.Black else Color.White, fontWeight = FontWeight.Medium)
            Text("#${color.toArgb().toString(16).uppercase().substring(2)}", fontSize = 9.sp, color = (if (color.luminance() > 0.5) Color.Black else Color.White).copy(alpha = 0.7f))
        }
    }
}

@Composable
fun ToggleRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: ZcPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = colors.Text)
            subtitle?.let { Text(it, fontSize = 11.sp, color = colors.Text3) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.Accent,
                checkedThumbColor = colors.Bg,
                uncheckedTrackColor = colors.Border,
                uncheckedThumbColor = colors.Text3
            )
        )
    }
}

@Composable
fun ColorPicker(
    initialColor: Color,
    onColorSelected: (Color) -> Unit
) {
    var hue by remember { mutableStateOf(initialColor.hue) }
    var saturation by remember { mutableStateOf(initialColor.saturation) }
    var lightness by remember { mutableStateOf(initialColor.lightness) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }

    val color = Color.hsv(hue, saturation, lightness, alpha)

    Column(modifier = Modifier.padding(16.dp).width(280.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(ZcShape.Lg)
                .background(Brush.horizontalGradient(listOf(Color.hsv(0, 1f, 0.5f, 1f), Color.hsv(360, 1f, 0.5f, 1f))))
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, Color.White)
                    .offset(x = (hue / 360f * 280).dp - 12.dp, y = 48.dp)
            )
        }
        SliderRow("Hue", null, hue / 360f, { hue = it * 360 }, min = 0f, max = 1f)
        SliderRow("Saturation", null, saturation, { saturation = it }, min = 0f, max = 1f)
        SliderRow("Lightness", null, lightness, { lightness = it }, min = 0f, max = 1f)
        SliderRow("Alpha", null, alpha, { alpha = it }, min = 0f, max = 1f)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { onColorSelected(Color.Transparent) }) {
                Text("Transparent", color = colors.Text)
            }
            Button(onClick = { onColorSelected(color) }) {
                Text("Apply", color = Color.White)
            }
        }
    }
}

@Composable
fun ShapeEditor(
    initialTokens: ShapeTokens,
    onSave: (ShapeTokens) -> Unit,
    onCancel: () -> Unit
) {
    var xs by remember { mutableStateOf(initialTokens.extraSmall) }
    var sm by remember { mutableStateOf(initialTokens.small) }
    var md by remember { mutableStateOf(initialTokens.medium) }
    var lg by remember { mutableStateOf(initialTokens.large) }
    var xl by remember { mutableStateOf(initialTokens.extraLarge) }

    Column(modifier = Modifier.padding(16.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SliderRow("Extra Small", null, xs / 32f, { xs = it * 32 }, min = 0f, max = 1f)
        SliderRow("Small", null, sm / 32f, { sm = it * 32 }, min = 0f, max = 1f)
        SliderRow("Medium", null, md / 32f, { md = it * 32 }, min = 0f, max = 1f)
        SliderRow("Large", null, lg / 32f, { lg = it * 32 }, min = 0f, max = 1f)
        SliderRow("Extra Large", null, xl / 32f, { xl = it * 32 }, min = 0f, max = 1f)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onCancel() }) { Text("Cancel", color = colors.Text) }
            Button(onClick = { onSave(ShapeTokens(xs, sm, md, lg, xl)) }) { Text("Save", color = Color.White) }
        }
    }
}

private fun Float.toShape(): Shape = RoundedCornerShape(this.dp)