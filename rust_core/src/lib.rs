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

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_init(
    _env: JNIEnv, _class: JClass,
    total_ram_mb: jint, cpu_cores: jint
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("ZeroCopy-Rust"),
    );
    let mut sched = SCHEDULER.lock().unwrap();
    *sched = Some(InferenceScheduler::new(cpu_cores as usize, total_ram_mb as u64));
    let mut mem = MEMORY.lock().unwrap();
    *mem = Some(MemoryMonitor::new(total_ram_mb as u64));
    log::info!("Rust core initialized: {} cores, {} MB RAM", cpu_cores, total_ram_mb);
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_optimizeThreadConfig<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>,
    model_size_mb: jint, gpu_layers: jint
) -> jstring {
    let sched = SCHEDULER.lock().unwrap();
    let config = sched.as_ref()
        .map(|s| s.optimize_threads(model_size_mb as u64, gpu_layers as u32))
        .unwrap_or_default();
    let json = serde_json::to_string(&config).unwrap_or_default();
    let output = env.new_string(&json).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_getMemoryAdvice<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>
) -> jstring {
    let mem = MEMORY.lock().unwrap();
    let advice = mem.as_ref()
        .map(|m| m.get_advice())
        .unwrap_or_default();
    let json = serde_json::to_string(&advice).unwrap_or_default();
    let output = env.new_string(&json).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_gguf_zerocopy_domain_inference_RustCore_shouldReduceContext(
    _env: JNIEnv, _class: JClass
) -> jboolean {
    let mem = MEMORY.lock().unwrap();
    if let Some(ref m) = *mem {
        if m.is_under_pressure() { JNI_TRUE } else { JNI_FALSE }
    } else {
        JNI_FALSE
    }
}
