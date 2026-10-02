//! Counters behind the statistics overlay and `ClientStats`.

use std::sync::Mutex;

use nya_proto::pb;
use nya_transport::videodgram::DgramStats;

use crate::events::StatsLine;

#[derive(Default)]
struct Inner {
    frames: u32,
    bytes: u64,
    dropped: u32,
    latency_sum_ms: f64,
    latency_n: u32,
    rtt_ms: Option<f64>,
    /// Host clock minus ours, in µs (from Ping/Pong).
    clock_offset_us: Option<f64>,
    decode_ms: f32,
    server: Option<pb::ServerStats>,
    dgram: DgramStats,
}

#[derive(Default)]
pub struct Stats(Mutex<Inner>);

impl Stats {
    pub fn on_frame(&self, len: usize, capture_ts_us: u64) {
        let mut s = self.0.lock().unwrap();
        s.frames += 1;
        s.bytes += len as u64;
        if let Some(off) = s.clock_offset_us {
            let ms = (nya_proto::now_us() as f64 + off - capture_ts_us as f64) / 1000.0;
            if (0.0..10_000.0).contains(&ms) {
                s.latency_sum_ms += ms;
                s.latency_n += 1;
            }
        }
    }

    pub fn on_dropped(&self) {
        self.0.lock().unwrap().dropped += 1;
    }

    pub fn on_pong(&self, t_us: u64, server_t_us: u64) {
        let now = nya_proto::now_us();
        let rtt = now.saturating_sub(t_us) as f64;
        let mut s = self.0.lock().unwrap();
        // Smooth both a little; one slow pong shouldn't jump the numbers.
        s.rtt_ms = Some(match s.rtt_ms {
            Some(r) => r * 0.7 + rtt / 1000.0 * 0.3,
            None => rtt / 1000.0,
        });
        let off = server_t_us as f64 - (t_us as f64 + rtt / 2.0);
        s.clock_offset_us = Some(match s.clock_offset_us {
            Some(o) => o * 0.8 + off * 0.2,
            None => off,
        });
    }

    pub fn on_server(&self, st: pb::ServerStats) {
        if st.slot == 0 {
            self.0.lock().unwrap().server = Some(st);
        }
    }

    pub fn add_dgram(&self, d: &DgramStats) {
        let mut s = self.0.lock().unwrap();
        s.dgram.shards_received += d.shards_received;
        s.dgram.shards_lost += d.shards_lost;
        s.dgram.frames_recovered += d.frames_recovered;
        s.dgram.frames_lost += d.frames_lost;
    }

    pub fn set_decode(&self, decode_ms: f32, dropped: u32) {
        let mut s = self.0.lock().unwrap();
        s.decode_ms = decode_ms;
        s.dropped += dropped;
    }

    /// The last second's numbers (resets the counters).
    pub fn take(&self, secs: f32, audio: nya_jitter::JitterStats) -> (StatsLine, pb::ClientStats) {
        let mut s = self.0.lock().unwrap();
        let secs = secs.max(0.001);
        let server = s.server.clone().unwrap_or_default();
        let line = StatsLine {
            fps: (s.frames as f32 / secs).round() as u32,
            mbps: s.bytes as f32 * 8.0 / secs / 1_000_000.0,
            rtt_ms: s.rtt_ms.unwrap_or(0.0) as f32,
            latency_ms: if s.latency_n > 0 { (s.latency_sum_ms / s.latency_n as f64) as f32 } else { 0.0 },
            decode_ms: s.decode_ms,
            server_fps: server.fps,
            encode_ms: server.encode_ms_p50,
            target_kbps: server.target_kbps,
            fec_percent: server.fec_percent,
            frames_lost: s.dgram.frames_lost,
            frames_dropped: s.dropped,
            audio_ms: audio.level_ms,
            audio_target_ms: audio.target_ms,
            audio_underruns: audio.underruns,
        };
        let client = pb::ClientStats {
            decode_ms_p50: s.decode_ms,
            render_ms_p50: 0.0,
            frames_dropped: s.dropped,
            fps: line.fps,
            video_shards_received: s.dgram.shards_received,
            video_shards_lost: s.dgram.shards_lost,
            video_frames_recovered: s.dgram.frames_recovered,
            video_frames_lost: s.dgram.frames_lost,
        };
        s.frames = 0;
        s.bytes = 0;
        s.dropped = 0;
        s.latency_sum_ms = 0.0;
        s.latency_n = 0;
        s.dgram = DgramStats::default();
        (line, client)
    }
}
