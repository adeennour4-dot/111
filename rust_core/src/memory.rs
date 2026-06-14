use serde::{Serialize, Deserialize};
use std::collections::VecDeque;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MemoryAdvice {
    pub under_pressure: bool,
    pub pressure_percent: f64,
    pub available_mb: u64,
    pub recommend_kv_quant: String,
    pub recommend_offload: bool,
    pub should_reduce_context: bool,
}

pub struct MemoryMonitor {
    total_ram_mb: u64,
    pressure_samples: VecDeque<f64>,
    sample_count: usize,
}

impl Default for MemoryMonitor {
    fn default() -> Self {
        Self::new(4096)
    }
}

impl MemoryMonitor {
    pub fn new(total_ram_mb: u64) -> Self {
        Self {
            total_ram_mb,
            pressure_samples: VecDeque::with_capacity(10),
            sample_count: 0,
        }
    }

    pub fn record_usage(&mut self, used_mb: u64) {
        let pressure = used_mb as f64 / self.total_ram_mb as f64;
        self.pressure_samples.push_back(pressure);
        if self.pressure_samples.len() > 10 {
            self.pressure_samples.pop_front();
        }
        self.sample_count += 1;
    }

    pub fn current_pressure(&self) -> f64 {
        self.pressure_samples.back().copied().unwrap_or(0.0)
    }

    pub fn avg_pressure(&self) -> f64 {
        if self.pressure_samples.is_empty() { return 0.0; }
        self.pressure_samples.iter().sum::<f64>() / self.pressure_samples.len() as f64
    }

    pub fn is_under_pressure(&self) -> bool {
        self.avg_pressure() > 0.85 // > 85% average memory usage = pressure
    }

    pub fn available_mb(&self) -> u64 {
        let used = (self.total_ram_mb as f64 * self.avg_pressure()) as u64;
        self.total_ram_mb.saturating_sub(used).saturating_sub(256) // 256MB reserve
    }

    pub fn recommend_kv_cache_quant(&self) -> &'static str {
        if self.is_under_pressure() { "q4_0" } else { "q8_0" }
    }

    pub fn recommend_model_to_offload(&self) -> bool {
        self.is_under_pressure()
    }

    pub fn get_advice(&self) -> MemoryAdvice {
        MemoryAdvice {
            under_pressure: self.is_under_pressure(),
            pressure_percent: self.avg_pressure() * 100.0,
            available_mb: self.available_mb(),
            recommend_kv_quant: self.recommend_kv_cache_quant().to_string(),
            recommend_offload: self.recommend_model_to_offload(),
            should_reduce_context: self.is_under_pressure(),
        }
    }
}
