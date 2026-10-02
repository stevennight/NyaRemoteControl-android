//! One connection to a host, owned by the Kotlin session screen through a JNI
//! handle. The network runs on its own tokio runtime; Kotlin threads pull
//! events, video frames and audio packets with blocking calls and push input.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use crossbeam_channel::{bounded, unbounded, Receiver, RecvTimeoutError, Sender};
use nya_proto::frame::VideoFrameHeader;
use nya_proto::pb::{self, control_msg::Msg};
use nya_transport::Identity;
use tokio::sync::mpsc;

use crate::events::Event;
use crate::gate::{Admit, Gate};
use crate::options::StartConfig;
use crate::stats::Stats;

pub enum NetCmd {
    Input(pb::InputMsg),
    Control(pb::ControlMsg),
    Quit,
}

pub fn ctl(m: Msg) -> pb::ControlMsg {
    pb::ControlMsg { msg: Some(m) }
}

/// A frame ready for MediaCodec: the Annex-B / OBU payload is `buf[offset..]`.
pub struct VideoOut {
    pub header: VideoFrameHeader,
    pub buf: Vec<u8>,
    pub offset: usize,
}

impl VideoOut {
    pub fn payload(&self) -> &[u8] {
        &self.buf[self.offset..]
    }
}

/// Decoded frames waiting for Kotlin. Small: a slow decoder should drop and
/// resync on a keyframe rather than build up latency.
const VIDEO_QUEUE: usize = 12;
const AUDIO_QUEUE: usize = 64;

/// State shared by the network tasks and the JNI calls.
pub struct Shared {
    events: Sender<Event>,
    video: Sender<VideoOut>,
    audio: Sender<Vec<u8>>,
    gate: Mutex<Gate>,
    pub stats: Stats,
    pub cmds: mpsc::UnboundedSender<NetCmd>,
    pair_reply: Mutex<Option<std::sync::mpsc::Sender<Option<String>>>>,
}

impl Shared {
    pub fn event(&self, e: Event) {
        let _ = self.events.send(e);
    }

    pub fn control(&self, m: Msg) {
        let _ = self.cmds.send(NetCmd::Control(ctl(m)));
    }

    fn request_keyframe(&self) {
        self.control(Msg::RequestKeyframe(pb::RequestKeyframe { slot: 0 }));
    }

    /// A received frame (video frame header + payload) towards the decoder.
    pub fn deliver(&self, stream_id: u64, buf: Vec<u8>) {
        let header = match VideoFrameHeader::parse(&buf) {
            Ok((h, payload)) => (h, buf.len() - payload.len()),
            Err(e) => {
                tracing::warn!("bad video frame: {e}");
                return;
            }
        };
        let (header, offset) = header;
        self.stats.on_frame(buf.len(), header.capture_ts_us);
        let now = Instant::now();
        let admit = self.gate.lock().unwrap().admit(stream_id, &header, now);
        match admit {
            Admit::Pass => {
                if self.video.try_send(VideoOut { header, buf, offset }).is_err() {
                    tracing::warn!("decoder queue full; dropping frame and waiting for a keyframe");
                    self.stats.on_dropped();
                    if self.gate.lock().unwrap().lost(now) {
                        self.request_keyframe();
                    }
                }
            }
            Admit::Drop { request_keyframe } => {
                self.stats.on_dropped();
                if request_keyframe {
                    self.request_keyframe();
                }
            }
        }
    }

    pub fn audio(&self, packet: Vec<u8>) {
        let _ = self.audio.try_send(packet);
    }

    /// Reconnected: wait for the new streams' keyframes.
    pub fn reset_video(&self) {
        self.gate.lock().unwrap().reset();
    }

    /// Ask Kotlin for the pairing code and wait for it (blocking).
    pub fn ask_pair_code(&self) -> Option<String> {
        let (tx, rx) = std::sync::mpsc::channel();
        *self.pair_reply.lock().unwrap() = Some(tx);
        self.event(Event::NeedPairing);
        rx.recv_timeout(Duration::from_secs(600)).ok().flatten()
    }
}

pub enum Next<T> {
    Item(T),
    Timeout,
    Closed,
}

pub struct Session {
    pub shared: Arc<Shared>,
    events: Receiver<Event>,
    video: Receiver<VideoOut>,
    /// A frame that didn't fit the caller's buffer; handed out again.
    video_pending: Mutex<Option<VideoOut>>,
    audio: Receiver<Vec<u8>>,
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
}

fn next<T>(rx: &Receiver<T>, timeout: Duration) -> Next<T> {
    match rx.recv_timeout(timeout) {
        Ok(v) => Next::Item(v),
        Err(RecvTimeoutError::Timeout) => Next::Timeout,
        Err(RecvTimeoutError::Disconnected) => Next::Closed,
    }
}

impl Session {
    pub fn start(data_dir: &Path, cfg: StartConfig) -> Result<Self> {
        let identity = Identity::load_or_create(&data_dir.join("identity")).context("client identity")?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("nya-net")
            .enable_all()
            .build()
            .context("tokio runtime")?;
        let (events_tx, events) = unbounded();
        let (video_tx, video) = bounded(VIDEO_QUEUE);
        let (audio_tx, audio) = bounded(AUDIO_QUEUE);
        let (cmds_tx, cmds_rx) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared {
            events: events_tx,
            video: video_tx,
            audio: audio_tx,
            gate: Mutex::new(Gate::new()),
            stats: Stats::default(),
            cmds: cmds_tx,
            pair_reply: Mutex::new(None),
        });
        runtime.spawn(crate::net::main(cfg, identity, shared.clone(), cmds_rx));
        Ok(Self { shared, events, video, video_pending: Mutex::new(None), audio, runtime: Mutex::new(Some(runtime)) })
    }

    pub fn poll_event(&self, timeout: Duration) -> Next<Event> {
        next(&self.events, timeout)
    }

    pub fn next_video(&self, timeout: Duration) -> Next<VideoOut> {
        if let Some(v) = self.video_pending.lock().unwrap().take() {
            return Next::Item(v);
        }
        next(&self.video, timeout)
    }

    pub fn put_back_video(&self, v: VideoOut) {
        *self.video_pending.lock().unwrap() = Some(v);
    }

    pub fn next_audio(&self, timeout: Duration) -> Next<Vec<u8>> {
        next(&self.audio, timeout)
    }

    pub fn provide_pair_code(&self, code: Option<String>) {
        if let Some(tx) = self.shared.pair_reply.lock().unwrap().take() {
            let _ = tx.send(code);
        }
    }

    pub fn input(&self, ev: pb::input_msg::Ev) {
        let _ = self.shared.cmds.send(NetCmd::Input(pb::InputMsg { ev: Some(ev) }));
    }

    pub fn control(&self, m: Msg) {
        self.shared.control(m);
    }

    /// The decoder was reset (new surface, error): wait for a keyframe.
    pub fn decoder_lost(&self) {
        if self.shared.gate.lock().unwrap().lost(Instant::now()) {
            self.shared.request_keyframe();
        }
    }

    /// Say goodbye to the host; the session then ends (Disconnected event).
    pub fn stop(&self) {
        let _ = self.shared.cmds.send(NetCmd::Quit);
        // Unblock a pending pairing prompt.
        self.provide_pair_code(None);
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.stop();
        if let Some(rt) = self.runtime.lock().unwrap().take() {
            rt.shutdown_timeout(Duration::from_millis(500));
        }
    }
}
