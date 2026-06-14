use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ThreadConfig {
    pub prompt_threads: usize,
    pub decode_threads: usize,
    pub use_big_cores: bool,
    pub gpu_offload: bool,
}

#[derive(Debug)]
pub struct InferenceScheduler {
    total_cores: usize,
    big_cores: Vec<usize>,
    little_cores: Vec<usize>,
    total_ram_mb: u64,
    current_temp: f32,
}

impl InferenceScheduler {
    pub fn new(total_cores: usize, total_ram_mb: u64) -> Self {
        let big_cores = Self::detect_big_cores();
        let little_cores = (0..total_cores)
            .filter(|c| !big_cores.contains(c))
            .collect();

        Self {
            total_cores,
            big_cores,
            little_cores,
            total_ram_mb,
            current_temp: 25.0,
        }
    }

    fn detect_big_cores() -> Vec<usize> {
        let mut core_freqs: Vec<(usize, u64)> = Vec::new();
        for cpu in 0..8 {
            let path = format!("/sys/devices/system/cpu/cpu{}/cpufreq/cpuinfo_max_freq", cpu);
            if let Ok(content) = std::fs::read_to_string(&path) {
                if let Ok(freq) = content.trim().parse::<u64>() {
                    core_freqs.push((cpu, freq));
                }
            }
        }
        if core_freqs.is_empty() { return vec![]; }
        let max_freq = core_freqs.iter().map(|(_, f)| *f).max().unwrap_or(0);
        let threshold = max_freq * 80 / 100;
        core_freqs
            .into_iter()
            .filter(|(_, f)| *f >= threshold)
            .map(|(c, _)| c)
            .collect()
    }

    pub fn optimize_threads(&self, model_size_mb: u64, gpu_layers: u32) -> ThreadConfig {
        let available_ram = self.total_ram_mb.saturating_sub(512); // Reserve 512MB for system
        let big_count = self.big_cores.len().max(1);

        let prompt_threads = if gpu_layers > 0 {
            // GPU handles the heavy lifting, use fewer CPU threads
            (big_count / 2).max(1)
        } else {
            // CPU mode: use all big cores for prompt processing
            big_count.min(4)
        };

        let decode_threads = if gpu_layers > 0 {
            1 // Single thread for token-by-token decode on GPU
        } else {
            big_count.min(2) // 2 big cores for decode
        };

        let gpu_offload = gpu_layers > 0 && self.has_gpu_capability();

        ThreadConfig {
            prompt_threads,
            decode_threads,
            use_big_cores: true,
            gpu_offload,
        }
    }

    pub fn adjust_for_thermal(&mut self, temperature: f32) -> bool {
        self.current_temp = temperature;
        temperature > 60.0 // Throttle if > 60°C
    }

    fn has_gpu_capability(&self) -> bool {
        std::path::Path::new("/vendor/lib/libvulkan.so").exists()
            || std::path::Path::new("/system/lib64/libOpenCL.so").exists()
    }

    pub fn suggest_batch_size(&self, model_size_mb: u64) -> usize {
        let available = self.total_ram_mb.saturating_sub(model_size_mb * 2);
        if available > 2048 { 2048 }
        else if available > 1024 { 1024 }
        else if available > 512 { 512 }
        else { 256 }
    }

    pub fn suggest_context_size(&self, model_size_mb: u64) -> usize {
        let available = self.total_ram_mb.saturating_sub(model_size_mb * 3 / 2);
        let kv_mb_per_token = model_size_mb as f64 / 8192.0; // Rough estimate
        let suggested = (available as f64 / kv_mb_per_token) as usize;
        suggested.clamp(512, 32768)
    }
}
