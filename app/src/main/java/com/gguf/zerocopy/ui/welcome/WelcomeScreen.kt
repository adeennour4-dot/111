package com.gguf.zerocopy.ui.welcome

import android.Manifest
import android.content.pm.PackageManager

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gguf.zerocopy.domain.device.DeviceUtils
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.delay

/**
 * First-run onboarding screen.
 *
 * Explains privacy, guides the user to import a model or continue later,
 * requests RECORD_AUDIO permission with rationale, and checks available RAM.
 *
 * @param onImportModel  Called when user chooses to import a model file.
 * @param onContinue     Called when user skips model selection (continues later).
 */
@Composable
fun WelcomeScreen(
    onImportModel: () -> Unit,
    onContinue: () -> Unit
) {
    val colors = currentPalette()
    val context = LocalContext.current

    // ── Animations ──
    val titleAlpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val featuresAlpha = remember { Animatable(0f) }
    val actionsAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        titleAlpha.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        delay(200)
        taglineAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(200)
        featuresAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(300)
        actionsAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }

    // ── RAM check ──
    val deviceInfo = remember {
        try { DeviceUtils(context).detect() } catch (_: Exception) { null }
    }
    val ramWarning = remember {
        val di = deviceInfo
        if (di != null && di.totalRamMB < 4096) {
            "Your device has ${di.totalRamMB / 1024} GB RAM. Consider using smaller models (≤3B parameters) for best performance."
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            // ── Logo icon ──
            Box(modifier = Modifier.alpha(titleAlpha.value)) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = "ZeroCopy",
                    modifier = Modifier.size(56.dp),
                    tint = colors.Accent
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── Title ──
            Box(modifier = Modifier.alpha(titleAlpha.value)) {
                Text(
                    "ZeroCopy",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(
                        brush = Brush.linearGradient(
                            listOf(colors.GradientStart, colors.GradientEnd)
                        )
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Tagline ──
            Box(modifier = Modifier.alpha(taglineAlpha.value)) {
                Text(
                    "Private AI. On Your Device.",
                    fontSize = 15.sp,
                    color = colors.Text2,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(36.dp))

            // ── Features ──
            Box(modifier = Modifier.alpha(featuresAlpha.value)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeatureItem(
                        icon = Icons.Outlined.Lock,
                        title = "100% Private",
                        subtitle = "All models run on-device. Nothing leaves your phone."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.Hub,
                        title = "Local Inference",
                        subtitle = "llama.cpp, MNN, LiteRT — no cloud needed"
                    )
                    FeatureItem(
                        icon = Icons.Outlined.Mic,
                        title = "Voice Input",
                        subtitle = "Speak your prompts (microphone permission needed)"
                    )
                    FeatureItem(
                        icon = Icons.Outlined.Psychology,
                        title = "Multi-Agent Invent",
                        subtitle = "Generate full projects from an idea"
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── RAM warning ──
            if (ramWarning != null) {
                Box(modifier = Modifier.alpha(actionsAlpha.value)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                colors.Amber.copy(alpha = 0.12f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = colors.Amber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ramWarning,
                            fontSize = 11.sp,
                            color = colors.Amber,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Actions ──
            Box(modifier = Modifier.alpha(actionsAlpha.value)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Browse models button — navigates to the Models tab where
                    // the user can import a model.  The actual file-picker lives
                    // in ModelListScreen; duplicating it here would be complex and
                    // fragile, so this button honestly says what it does.
                    Button(
                        onClick = { onImportModel() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
                    ) {
                        Icon(
                            Icons.Outlined.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Browse Models",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Demo / continue later
                    OutlinedButton(
                        onClick = { onContinue() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, colors.Text3.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            "Continue Later",
                            color = colors.Text3,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        "You can import a model anytime from the Models tab.",
                        fontSize = 10.sp,
                        color = colors.Text3.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    val colors = currentPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.GradientStart.copy(alpha = 0.15f),
                            colors.GradientEnd.copy(alpha = 0.15f)
                        )
                    ),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = colors.Accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.Text,
                fontFamily = FontFamily.Monospace
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = colors.Text3,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
