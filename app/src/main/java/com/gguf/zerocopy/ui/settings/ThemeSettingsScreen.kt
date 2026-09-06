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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.theme.ThemeConfig
import com.gguf.zerocopy.ui.theme.ThemeManager
import com.gguf.zerocopy.ui.theme.ZcShape
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.launch
import java.util.Locale

private val Color.hsvArray: FloatArray
  get() {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    return hsv
  }

val Color.hue: Float get() = hsvArray[0]
val Color.saturation: Float get() = hsvArray[1]
val Color.lightness: Float get() = hsvArray[2]

// Placeholder enums and data classes for theme settings
enum class AnimationIntensity(val label: String) {
  NONE("No Animations"),
  SUBTLE("Subtle"),
  NORMAL("Normal"),
  PLAYFUL("Playful")
}

enum class DensityLevel(val label: String, val factor: Float) {
  COMPACT("Compact", 0.9f),
  DEFAULT("Default", 1.0f),
  EXPANDED("Expanded", 1.1f)
}

enum class CustomColorTarget {
  Primary, Secondary, Tertiary, Error, Background, Surface, Outline
}

enum class ShapeStyle(val label: String, val tokens: ShapeTokens) {
  ROUNDED("Rounded", ShapeTokens(8f, 12f, 16f, 20f, 24f)),
  SQUARE("Square", ShapeTokens(0f, 4f, 8f, 12f, 16f)),
  SMOOTH("Smooth", ShapeTokens(12f, 16f, 20f, 28f, 32f)),
  CUSTOM("Custom", ShapeTokens(8f, 12f, 16f, 20f, 24f))
}

data class ShapeTokens(
  val extraSmall: Float = 8f,
  val small: Float = 12f,
  val medium: Float = 16f,
  val large: Float = 20f,
  val extraLarge: Float = 24f
)

data class CustomColors(
  val primary: Color = Color(0xFF6750A4),
  val secondary: Color = Color(0xFF625B71),
  val tertiary: Color = Color(0xFF7D5260),
  val error: Color = Color(0xFFB3261E),
  val background: Color = Color(0xFFFFFBFE),
  val surface: Color = Color(0xFFFFFBFE),
  val outline: Color = Color(0xFF79747E)
)

data class PresetTheme(
  val name: String,
  val colors: CustomColors,
  val shapeStyle: ShapeStyle
)

object PresetThemes {
  val presets = listOf(
    PresetTheme("Aurora", CustomColors(), ShapeStyle.ROUNDED),
    PresetTheme("Minimal", CustomColors(primary = Color(0xFF000000)), ShapeStyle.SQUARE)
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val manager = ThemeManager()
  val colors = currentPalette()
  val snackbarHostState = remember { SnackbarHostState() }
  val scrollState = rememberScrollState()
  var showCustomColorPicker by remember { mutableStateOf(false) }
  var customColorTarget by remember { mutableStateOf<CustomColorTarget>(CustomColorTarget.Primary) }
  var showShapeEditor by remember { mutableStateOf(false) }
  var showPresetDialog by remember { mutableStateOf(false) }
  var showResetConfirm by remember { mutableStateOf(false) }
  var config by remember { mutableStateOf(ThemeConfig()) }

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
      elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
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
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(padding)
        .verticalScroll(scrollState),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      SettingCard {
        SectionHeader("Theme Mode", Icons.Filled.Brightness7)
      }
      Spacer(Modifier.height(32.dp))
    }
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
  onColorSelected: (Color) -> Unit,
  colors: ZcPalette = currentPalette()
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
        .background(Brush.horizontalGradient(listOf(Color.hsv(0f, 1f, 0.5f, 1f), Color.hsv(360f, 1f, 0.5f, 1f))))
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
  onCancel: () -> Unit,
  colors: ZcPalette = currentPalette()
) {
  var xs by remember { mutableStateOf(initialTokens.extraSmall) }
  var sm by remember { mutableStateOf(initialTokens.small) }
  var md by remember { mutableStateOf(initialTokens.medium) }
  var lg by remember { mutableStateOf(initialTokens.large) }
  var xl by remember { mutableStateOf(initialTokens.extraLarge) }

  Column(modifier = Modifier.padding(16.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(onClick = { onCancel() }) { Text("Cancel", color = colors.Text) }
      Button(onClick = { onSave(ShapeTokens(xs, sm, md, lg, xl)) }) { Text("Save", color = Color.White) }
    }
  }
}

private fun Float.toShape(): Shape = RoundedCornerShape(this.dp)
