# Fixes for ZeroCopy - Output & Messages Issues

## Issues Fixed

### 1. No Output on UI (llama.cpp with Gemma 3 1B)

**Root Cause:** The callback object in `LlamaCppEngine` was being garbage collected before the native code could call it. The JNI global reference was being stored, but the Kotlin callback object wasn't being held strongly enough.

**Fixes Applied:**

#### LlamaCppEngine.kt
- Added `activeCallback` field to hold strong reference to prevent GC
- Added comprehensive logging to track inference flow
- Store callback reference before calling native code
- Log when tokens are received, when inference starts/ends, and on errors

#### ipc-bridge.cpp (Native Code)
- Added logging when `executeWithCallbackNative` and `executeWithImageNative` are called
- Log when callback is stored as global reference
- Log engine state (model, context, sampler) when errors occur

### 2. Messages Disappear When New Message Appears

**Root Cause:** 
1. `loadSessions()` was called after every `addMessage()`, triggering unnecessary UI recomposition
2. The `chatId` state was using `rememberSaveable(sessionId)` which could reset on recomposition
3. Session name wasn't reactive to session list changes

**Fixes Applied:**

#### ChatRepository.kt
- Removed `loadSessions()` call from `addMessage()` and `deleteMessage()` to prevent unnecessary recomposition
- Added `refreshSessions()` method for explicit refresh when needed
- Added logging to `selectSession()`, `createSession()`, and `addMessage()`
- Fixed `deleteSession()` to properly clear current session state

#### ChatScreen.kt
- Changed `chatId` from `rememberSaveable(sessionId)` to `remember` to prevent reset on recomposition
- Added `LaunchedEffect(sessionId)` to properly initialize chatId from parameter
- Captured `chatId` at inference time to avoid race conditions
- Added comprehensive logging throughout the inference flow
- Made session name reactive by observing `sessions` StateFlow

## Key Changes Summary

### LlamaCppEngine.kt
```kotlin
// Added strong reference to prevent GC
private var activeCallback: NativeBridge.TokenCallback? = null

// Store reference before native call
activeCallback = cb
NativeBridge.executeWithCallbackNative(prompt, cb)

// Added logging
android.util.Log.d("LlamaCppEngine", "onToken: ${token.take(50)}")
```

### ChatScreen.kt
```kotlin
// Changed from rememberSaveable to remember
var chatId by remember { mutableStateOf(sessionId) }

// Capture chatId at inference time
val currentChatId = id

// Use captured ID in callback
override fun onDone() {
    app.chatRepository.addMessage(currentChatId, ...)
}

// Reactive session name
val sessions by app.chatRepository.sessions.collectAsState()
val sessionName = remember(chatId, sessions) { ... }
```

### ChatRepository.kt
```kotlin
// Removed loadSessions() from addMessage
fun addMessage(sessionId: String, message: ChatMessage) {
    lock.write {
        // ... add message ...
        if (sessionId == currentSessionId) _currentMessages.value = messages
    }
    // Don't call loadSessions() - causes unnecessary recomposition
}

// Added refresh method
fun refreshSessions() { loadSessions() }
```

### ipc-bridge.cpp
```cpp
// Added logging
LOGI("executeWithCallbackNative called");
LOGI("Callback stored as global reference: %p", g_callback);
LOGE("Engine not ready: model=%p, ctx=%p, sampler=%p", g_model, g_ctx, g_sampler);
```

## Testing Recommendations

1. **Test Output Generation:**
   - Load a GGUF model (e.g., Gemma 3 1B)
   - Send a message
   - Check logcat for "onToken" messages
   - Verify tokens appear in UI

2. **Test Message Persistence:**
   - Send multiple messages
   - Verify all messages remain visible
   - Switch to session list and back
   - Verify messages are still there

3. **Check Logcat:**
   ```bash
   adb logcat -s LlamaCppEngine:D ChatScreen:D ChatRepository:D ZeroCopy_v8:I
   ```

## Build Instructions

```bash
cd "111 (2)"
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## What to Look For in Logs

When sending a message, you should see:
```
D/ChatScreen: sendMessage called with chatId: session_xxx
D/ChatRepository: addMessage to session: session_xxx, role: USER
D/LlamaCppEngine: Starting inference with prompt length: xxx
I/ZeroCopy_v8: executeWithCallbackNative called
I/ZeroCopy_v8: Callback stored as global reference: 0x...
D/LlamaCppEngine: onToken: Hello... (total: 1)
D/LlamaCppEngine: onToken:  world... (total: 2)
...
D/LlamaCppEngine: onDone
D/ChatScreen: onDone called, rawResponse length: xxx
D/ChatRepository: addMessage to session: session_xxx, role: ASSISTANT
```

If you don't see "onToken" messages, the native callback isn't being called.
If you see "Engine not ready", the model isn't loaded properly.
