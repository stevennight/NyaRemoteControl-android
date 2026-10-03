//! Events for the Kotlin side, polled as JSON (`NativeCore.pollEvent`).

use base64::Engine;
use nya_proto::pb;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Event {
    Connecting,
    /// The host wants the pairing code; answer with `providePairCode`.
    NeedPairing,
    Connected {
        server_name: String,
        server_version: String,
        /// Hex fingerprint to pin for later connections.
        server_fingerprint: String,
        /// Short form for display.
        server_fingerprint_short: String,
        /// The host types text as Unicode (protocol 1.5): the phone IME can be used as is.
        text_input: bool,
        file_transfer: bool,
        gamepad: bool,
        clipboard_image: bool,
        clipboard_files: bool,
        microphone: bool,
        folder_mount: bool,
        print: bool,
        usb: bool,
        hdr: bool,
        virtual_display: bool,
        /// This connection is QUIC over TCP (else UDP).
        tcp: bool,
    },
    Reconnecting {
        message: String,
    },
    /// Final: the session is over.
    Disconnected {
        message: String,
    },
    /// Final: the host's certificate is not the saved one (reinstalled, or
    /// someone pretends to be it). The app may connect again with `reverify`.
    PinChanged {
        message: String,
    },
    /// Connected without the saved pin and the host already knows this phone:
    /// show the fingerprint, answer with `confirmFingerprint`.
    VerifyFingerprint {
        fingerprint: String,
    },
    SessionInfo {
        host_name: String,
        virtual_display_available: bool,
        displays: Vec<Display>,
        /// Playback device on the host that receives the microphone; empty = none installed.
        mic_device: String,
        usb_available: bool,
    },
    StreamStarted {
        width: u32,
        height: u32,
        source_width: u32,
        source_height: u32,
        fps: u32,
        codec: String,
        encoder: String,
        display_id: u32,
        /// HDR10 (HEVC Main10, BT.2020, PQ).
        hdr: bool,
        /// The host desktop is HDR and is sent tone-mapped to SDR.
        hdr_tonemapped: bool,
        /// Captured on one GPU, encoded on another: "[capture]→[encode]".
        cross_gpu: String,
    },
    StreamError {
        message: String,
    },
    Role {
        controlling: bool,
        controller: String,
        viewers: Vec<String>,
    },
    CursorShape {
        id: u32,
        width: u32,
        height: u32,
        hot_x: i32,
        hot_y: i32,
        /// Base64 of straight-alpha RGBA rows.
        rgba: String,
    },
    CursorState {
        shape_id: u32,
        visible: bool,
        x: i32,
        y: i32,
    },
    Clipboard {
        text: String,
    },
    /// The host copied files; `requestFiles(id)` downloads them.
    FileOffer {
        /// u64 as a string (JSON numbers lose precision above 2^53).
        id: String,
        files: Vec<OfferedFile>,
        total_bytes: u64,
    },
    /// Progress of an upload or download; `finished` once it ends.
    Transfer {
        id: String,
        upload: bool,
        name: String,
        done: u64,
        total: u64,
        finished: bool,
        ok: bool,
        message: String,
    },
    /// The host cancelled transfer `id` (either direction): what is still
    /// running of it ends; later reports of it change nothing.
    TransferCancelled {
        id: String,
        message: String,
    },
    /// A whole download batch is in `download_dir`.
    FilesReceived {
        id: String,
        paths: Vec<String>,
    },
    /// An image copied on the host: CF_DIB bytes in this file.
    ClipboardImage {
        path: String,
    },
    /// The host printed: a PDF to print or open here.
    PrintJob {
        path: String,
    },
    FolderMount {
        mounted: bool,
        mount_point: String,
        message: String,
    },
    UsbStatus {
        busid: String,
        attached: bool,
        message: String,
    },
    /// Force feedback for pad `index` (0..255 per motor).
    Rumble {
        index: u32,
        large: u32,
        small: u32,
    },
    Stats(StatsLine),
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfferedFile {
    pub name: String,
    pub path: String,
    pub size: u64,
    pub is_dir: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Display {
    pub id: u32,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub primary: bool,
    pub is_virtual: bool,
    /// HDR desktop (sent tone-mapped unless HDR10 passthrough is on).
    pub hdr: bool,
    pub refresh_hz: u32,
    /// 1-based index among the session's virtual screens (0 = physical).
    pub virtual_index: u32,
}

/// Once a second, for the statistics overlay.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsLine {
    pub fps: u32,
    pub mbps: f32,
    pub rtt_ms: f32,
    /// Capture on the host until the frame was received here (clock offset from pings).
    pub latency_ms: f32,
    pub decode_ms: f32,
    pub server_fps: u32,
    pub encode_ms: f32,
    pub target_kbps: u32,
    /// What the host measured it sends.
    pub server_kbps: u32,
    /// Bitrate policy and the reason of its last change.
    pub bitrate_note: String,
    /// 99th percentile over 10 s (0 = the host doesn't report it).
    pub encode_p99_ms: f32,
    pub fec_percent: u32,
    /// Video datagrams: shards lost (percent) and frames rebuilt from parity.
    pub loss_percent: f32,
    pub frames_recovered: u32,
    pub frames_lost: u32,
    pub frames_dropped: u32,
    /// Audio jitter buffer: current depth and target (ms), underruns so far.
    pub audio_ms: f32,
    pub audio_target_ms: f32,
    pub audio_underruns: u32,
    /// The host's view of the connection (protocol 1.8): packet loss and round trip.
    pub path_loss_pct: f32,
    pub path_rtt_ms: f32,
}

pub fn codec_name(c: i32) -> &'static str {
    match pb::Codec::try_from(c).unwrap_or(pb::Codec::Unspecified) {
        pb::Codec::H264 => "H.264",
        pb::Codec::Hevc => "HEVC",
        pb::Codec::Av1 => "AV1",
        pb::Codec::Unspecified => "?",
    }
}

impl Event {
    pub fn session_info(i: &pb::SessionInfo) -> Self {
        Event::SessionInfo {
            host_name: i.host_name.clone(),
            virtual_display_available: i.virtual_display_available,
            mic_device: i.mic_device.clone(),
            usb_available: i.usb_available,
            displays: i
                .displays
                .iter()
                .map(|d| Display {
                    id: d.id,
                    name: d.name.clone(),
                    width: d.width,
                    height: d.height,
                    primary: d.primary,
                    is_virtual: d.is_virtual,
                    hdr: d.hdr,
                    refresh_hz: d.refresh_hz,
                    virtual_index: d.virtual_index,
                })
                .collect(),
        }
    }

    pub fn stream_started(s: &pb::StreamStarted) -> Self {
        let c = s.config.clone().unwrap_or_default();
        Event::StreamStarted {
            width: c.width,
            height: c.height,
            source_width: s.source_width,
            source_height: s.source_height,
            fps: c.fps,
            codec: codec_name(c.codec).into(),
            encoder: s.encoder_name.clone(),
            display_id: s.display_id,
            hdr: c.hdr,
            hdr_tonemapped: s.hdr_tonemapped,
            cross_gpu: if s.cross_gpu { format!("[{}]→[{}]", s.capture_gpu_index, s.encode_gpu_index) } else { String::new() },
        }
    }

    pub fn cursor(m: pb::CursorMsg) -> Option<Self> {
        Some(match m.msg? {
            pb::cursor_msg::Msg::Shape(s) => Event::CursorShape {
                id: s.id,
                width: s.width,
                height: s.height,
                hot_x: s.hot_x,
                hot_y: s.hot_y,
                rgba: base64::engine::general_purpose::STANDARD.encode(&s.rgba),
            },
            pb::cursor_msg::Msg::State(s) if s.slot == 0 => {
                Event::CursorState { shape_id: s.shape_id, visible: s.visible, x: s.x, y: s.y }
            }
            pb::cursor_msg::Msg::State(_) => return None,
        })
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{\"type\":\"invalid\"}".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_shape_matches_kotlin_parser() {
        let e = Event::Reconnecting { message: "x".into() };
        assert_eq!(e.to_json(), r#"{"type":"reconnecting","message":"x"}"#);
        let e = Event::CursorState { shape_id: 3, visible: true, x: -1, y: 2 };
        assert_eq!(e.to_json(), r#"{"type":"cursorState","shapeId":3,"visible":true,"x":-1,"y":2}"#);
        let e = Event::Stats(StatsLine { fps: 60, ..Default::default() });
        assert!(e.to_json().starts_with(r#"{"type":"stats","fps":60,"#));
        assert_eq!(Event::NeedPairing.to_json(), r#"{"type":"needPairing"}"#);
        let e = Event::VerifyFingerprint { fingerprint: "AB:CD".into() };
        assert_eq!(e.to_json(), r#"{"type":"verifyFingerprint","fingerprint":"AB:CD"}"#);
        assert_eq!(Event::PinChanged { message: "m".into() }.to_json(), r#"{"type":"pinChanged","message":"m"}"#);
        let e = Event::TransferCancelled { id: "7".into(), message: "x".into() };
        assert_eq!(e.to_json(), r#"{"type":"transferCancelled","id":"7","message":"x"}"#);
        let e = Event::Stats(StatsLine { path_loss_pct: 12.5, path_rtt_ms: 40.0, ..Default::default() });
        assert!(e.to_json().contains(r#""pathLossPct":12.5,"pathRttMs":40.0"#), "{}", e.to_json());
    }
}
