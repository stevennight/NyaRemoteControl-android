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
use nya_jitter::JitterBuffer;

pub enum NetCmd {
    Input(pb::InputMsg),
    Control(pb::ControlMsg),
    SendFiles(Vec<crate::files::Upload>),
    /// A MIC datagram (Opus from the phone's microphone).
    Mic(Vec<u8>),
    /// CF_DIB bytes of an image copied on the phone.
    SendImage(Vec<u8>),
    /// Files copied on the phone (staged in the app's cache): offer them for pasting on the host.
    OfferFiles(Vec<std::path::PathBuf>),
    /// New list of shared folders.
    SetShares(nya_transport::folders::Shares),
    /// Stop a transfer (both sides), dropping what was received of it.
    CancelTransfer(u64),
    /// Connection mode changed: reconnect if needed.
    SetTransport(crate::net::Transport),
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
    verify_reply: Mutex<Option<std::sync::mpsc::Sender<bool>>>,
    /// Host audio after decoding (the app decodes Opus with MediaCodec).
    pub jitter: Mutex<JitterBuffer>,
    /// Local clock for the jitter buffer.
    epoch: Instant,
    /// Files copied on the phone, kept until the host pastes them.
    pub clip_out: Mutex<nya_transport::clipfiles::Outgoing>,
    /// Folders the host may read (and write) through FS streams.
    pub shares: Mutex<Arc<nya_transport::folders::Shares>>,
    /// USB devices shared with the host.
    pub usb: crate::usb::Devices,
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

    pub fn now_us(&self) -> u64 {
        self.epoch.elapsed().as_micros() as u64
    }

    /// Ask Kotlin for the pairing code and wait for it (blocking).
    pub fn ask_pair_code(&self) -> Option<String> {
        let (tx, rx) = std::sync::mpsc::channel();
        *self.pair_reply.lock().unwrap() = Some(tx);
        self.event(Event::NeedPairing);
        rx.recv_timeout(Duration::from_secs(600)).ok().flatten()
    }

    /// Show the host's fingerprint and wait for the user's answer (blocking).
    pub fn ask_fingerprint_ok(&self, fingerprint: String) -> bool {
        let (tx, rx) = std::sync::mpsc::channel();
        *self.verify_reply.lock().unwrap() = Some(tx);
        self.event(Event::VerifyFingerprint { fingerprint });
        rx.recv_timeout(Duration::from_secs(600)).unwrap_or(false)
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
    /// Sequence numbers of MIC datagrams.
    pub mic_seq: std::sync::atomic::AtomicU32,
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
            verify_reply: Mutex::new(None),
            jitter: Mutex::new(JitterBuffer::new()),
            epoch: Instant::now(),
            clip_out: Mutex::new(Default::default()),
            shares: Mutex::new(Arc::new(crate::options::shares(&cfg.shares))),
            usb: Default::default(),
        });
        runtime.spawn(crate::net::main(cfg, identity, shared.clone(), cmds_rx));
        Ok(Self {
            shared,
            events,
            video,
            video_pending: Mutex::new(None),
            audio,
            runtime: Mutex::new(Some(runtime)),
            mic_seq: Default::default(),
        })

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

    pub fn confirm_fingerprint(&self, ok: bool) {
        if let Some(tx) = self.shared.verify_reply.lock().unwrap().take() {
            let _ = tx.send(ok);
        }
    }

    /// A decoded audio packet (16-bit interleaved stereo). False when it was late or a duplicate.
    pub fn audio_push(&self, seq: u32, sender_us: u64, pcm: &[i16]) -> bool {
        let now = self.shared.now_us();
        self.shared.jitter.lock().unwrap().push(seq, sender_us, now, |out| {
            out.extend(pcm.iter().map(|&v| v as f32 / 32768.0));
        })
    }

    /// Up to `max_frames` frames (interleaved f32 stereo) for the output device,
    /// which still holds `device_queued` frames.
    pub fn audio_pull(&self, max_frames: usize, device_queued: usize, out: &mut Vec<f32>) -> usize {
        let now = self.shared.now_us();
        self.shared.jitter.lock().unwrap().pull(now, max_frames, device_queued, out)
    }

    pub fn send_files(&self, items: Vec<crate::files::Upload>) {
        let _ = self.shared.cmds.send(NetCmd::SendFiles(items));
    }

    pub fn cmd(&self, c: NetCmd) {
        let _ = self.shared.cmds.send(c);
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
