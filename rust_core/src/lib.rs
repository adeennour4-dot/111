use jni::JNIEnv;
use jni::objects::{JClass};
use jni::sys::{jint, jboolean, jstring, JNI_TRUE, JNI_FALSE};
use std::sync::Mutex;

mod scheduler;
mod memory;

use scheduler::InferenceScheduler;
use memory::MemoryMonitor;

static SCHEDULER: Mutex<Option<InferenceScheduler>> = Mutex::new(None);
static MEMORY: Mutex<Option<MemoryMonitor>> = Mutex::new(None);

/// Lock a static mutex, recovering from poison by logging and using the inner value.
macro_rules! lock_or_recover {
    ($mutex:expr) => {{
        match $mutex.lock() {
            Ok(guard) => guard,
            Err(poisoned) => {
                log::warn!("Rust mutex poisoned at {}:{}", file!(), line!());
                poisoned.into_inner()
            }
        }
    }};
}

/// Helper: create a JNI string, returning an empty string on failure.
fn jni_string_or_empty<'local>(env: &mut JNIEnv<'local>, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            log::error!("Failed to create JNI string: {}", e);
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
    let mut sched = lock_or_recover!(SCHEDULER);
    *sched = Some(InferenceScheduler::new(cpu_cores as usize, total_ram_mb as u64));
    let mut mem = lock_or_recover!(MEMORY);
    *mem = Some(MemoryMonitor::new(total_ram_mb as u64));
    log::info!("Rust core initialized: {} cores, {} MB RAM", cpu_cores, total_ram_mb);
}

/// Safe symmetric shutdown — drops scheduler and memory, freeing resources.
/// Safe to call multiple times.
#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeShutdown(
    _env: JNIEnv, _class: JClass
) {
    let mut sched = lock_or_recover!(SCHEDULER);
    *sched = None;
    let mut mem = lock_or_recover!(MEMORY);
    *mem = None;
    log::info!("Rust core shut down");
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeOptimizeThreadConfig<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>,
    model_size_mb: jint, gpu_layers: jint
) -> jstring {
    let sched = lock_or_recover!(SCHEDULER);
    let config = sched.as_ref()
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
    let mem = lock_or_recover!(MEMORY);
    let advice = mem.as_ref()
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
    let mem = lock_or_recover!(MEMORY);
    if let Some(ref m) = *mem {
        if m.is_under_pressure() { JNI_TRUE } else { JNI_FALSE }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_nativeRecordUsage(
    _env: JNIEnv, _class: JClass,
    used_mb: jni::sys::jlong
) {
    // Clamp negative values to 0 before casting to u64
    let safe_used = if used_mb < 0 { 0u64 } else { used_mb as u64 };
    let mut mem = lock_or_recover!(MEMORY);
    if let Some(ref mut m) = *mem {
        m.record_usage(safe_used);
    }
}
