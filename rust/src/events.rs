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
    },
    Reconnecting {
        message: String,
    },
    /// Final: the session is over.
    Disconnected {
        message: String,
    },
    SessionInfo {
        host_name: String,
        virtual_display_available: bool,
        displays: Vec<Display>,
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
    Stats(StatsLine),
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
    pub fec_percent: u32,
    pub frames_lost: u32,
    pub frames_dropped: u32,
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
    }
}
