use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jfloat, jboolean, jstring, JNI_TRUE, JNI_FALSE};
use std::collections::HashMap;
use std::sync::Mutex;

mod scheduler;
mod memory;

use scheduler::InferenceScheduler;
use memory::MemoryMonitor;

static SCHEDULER: Mutex<Option<InferenceScheduler>> = Mutex::new(None);
static MEMORY: Mutex<Option<MemoryMonitor>> = Mutex::new(None);

/// Helper: lock a static Mutex<Option<T>>, returning a reference
/// or logging and returning None on poison.
fn lock_scheduler() -> Option<std::sync::MutexGuard<'static, Option<InferenceScheduler>>> {
    match SCHEDULER.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => {
            log::warn!("Rust SCHEDULER mutex poisoned, recovering");
            Some(poisoned.into_inner())
        }
    }
}

fn lock_memory() -> Option<std::sync::MutexGuard<'static, Option<MemoryMonitor>>> {
    match MEMORY.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => {
            log::warn!("Rust MEMORY mutex poisoned, recovering");
            Some(poisoned.into_inner())
        }
    }
}

/// Helper: create a JNI string, returning empty string on failure.
fn jni_string_or_empty<'local>(env: &mut JNIEnv<'local>, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            log::error!("Failed to create JNI string: {}", e);
            // Return an empty jstring — Kotlin side should handle empty gracefully
            env.new_string("")
                .map(|js| js.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeInit(
    _env: JNIEnv, _class: JClass,
    total_ram_mb: jint, cpu_cores: jint
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("ZeroCopy-Rust"),
    );
    if let Some(mut sched) = lock_scheduler() {
        *sched = Some(InferenceScheduler::new(cpu_cores as usize, total_ram_mb as u64));
    }
    if let Some(mut mem) = lock_memory() {
        *mem = Some(MemoryMonitor::new(total_ram_mb as u64));
    }
    log::info!("Rust core initialized: {} cores, {} MB RAM", cpu_cores, total_ram_mb);
}

/// Safe symmetric shutdown — drops scheduler and memory, frees resources.
/// Safe to call multiple times.
#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeShutdown(
    _env: JNIEnv, _class: JClass
) {
    if let Some(mut sched) = lock_scheduler() {
        *sched = None;
    }
    if let Some(mut mem) = lock_memory() {
        *mem = None;
    }
    log::info!("Rust core shut down");
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeOptimizeThreadConfig<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>,
    model_size_mb: jint, gpu_layers: jint
) -> jstring {
    let config = lock_scheduler()
        .and_then(|s| s)
        .map(|s| s.optimize_threads(model_size_mb as u64, gpu_layers as u32))
        .unwrap_or_default();
    let json = serde_json::to_string(&config).unwrap_or_else(|e| {
        log::error!("Failed to serialize thread config: {}", e);
        "{}".to_string()
    });
    jni_string_or_empty(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeGetMemoryAdvice<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>
) -> jstring {
    let advice = lock_memory()
        .and_then(|m| m)
        .map(|m| m.get_advice())
        .unwrap_or_default();
    let json = serde_json::to_string(&advice).unwrap_or_else(|e| {
        log::error!("Failed to serialize memory advice: {}", e);
        "{}".to_string()
    });
    jni_string_or_empty(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeShouldReduceContext(
    _env: JNIEnv, _class: JClass
) -> jboolean {
    let under_pressure = lock_memory()
        .and_then(|m| m)
        .map(|m| m.is_under_pressure())
        .unwrap_or(false);
    if under_pressure { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeRecordUsage(
    _env: JNIEnv, _class: JClass,
    used_mb: jni::sys::jlong
) {
    // Clamp negative values to 0 before casting to u64
    let safe_used = if used_mb < 0 { 0u64 } else { used_mb as u64 };
    if let Some(mut mem) = lock_memory() {
        if let Some(ref mut m) = *mem {
            m.record_usage(safe_used);
        }
    }
}
