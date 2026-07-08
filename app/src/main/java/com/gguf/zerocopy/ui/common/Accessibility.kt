package com.gguf.zerocopy.ui.common

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Announce a message for accessibility (TalkBack).
 * Uses [AccessibilityManager] to speak the message if accessibility is enabled.
 */
fun announceForAccessibility(context: Context, message: String) {
    try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null && am.isEnabled) {
            // Use the active window's accessibility delegate
            val announcement = android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT
            )
            announcement.text.add(message)
            announcement.contentDescription = message
            // Fire and forget — if no window is focused, the event is dropped silently
            try {
                am.sendAccessibilityEvent(announcement)
            } catch (_: Exception) {
                // Fallback: just log
                android.util.Log.d("Accessibility", "Announce: $message")
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("Accessibility", "Failed to announce: ${e.message}")
    }
}

/**
 * A composable that announces a message via TalkBack when [message] changes.
 */
@Composable
fun AccessibilityAnnouncement(message: String) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            announceForAccessibility(context, message)
        }
    }
}
