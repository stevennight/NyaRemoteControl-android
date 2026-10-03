//! Session settings handed over from Kotlin as JSON, turned into protocol
//! messages.

use nya_proto::pb;
use serde::Deserialize;

/// `NativeCore.start` configuration.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartConfig {
    /// `ip`, `ip:port`, `host`, `[v6]:port`.
    pub address: String,
    /// Saved fingerprint of the host (hex); absent before the first pairing.
    #[serde(default)]
    pub pinned: Option<String>,
    /// Pairing code typed when the host was added; asked for again if needed.
    #[serde(default)]
    pub pair_code: Option<String>,
    pub client_name: String,
    pub client_version: String,
    #[serde(default)]
    pub decoders: Vec<DecoderCap>,
    #[serde(default)]
    pub max_fps: u32,
    pub stream: StreamOptions,
    /// Where files from the host are received (the app then moves them to Downloads).
    #[serde(default)]
    pub download_dir: Option<String>,
    /// Phone folders shown on the host as a drive (FEATURE_FOLDER_MOUNT).
    #[serde(default)]
    pub shares: Vec<ShareConfig>,
    /// Connect without the saved pin (the host's certificate changed). If the
    /// host already knows this phone (no pairing code proves who it is), the
    /// fingerprint is shown for confirmation first (`verifyFingerprint`).
    #[serde(default)]
    pub reverify: bool,
    /// Connection mode: "auto" (UDP; TCP when UDP does not connect or loses
    /// too much) | "udp" | "tcp" (QUIC over TCP).
    #[serde(default)]
    pub transport: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShareConfig {
    pub name: String,
    pub path: String,
    #[serde(default)]
    pub read_only: bool,
}

pub fn shares(list: &[ShareConfig]) -> nya_transport::folders::Shares {
    nya_transport::folders::Shares(
        list.iter()
            .map(|s| nya_transport::folders::Share {
                name: s.name.clone(),
                root: std::path::PathBuf::from(&s.path),
                read_only: s.read_only,
            })
            .collect(),
    )
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DecoderCap {
    /// "h264" / "hevc" / "av1".
    pub codec: String,
    #[serde(default)]
    pub hardware: bool,
    #[serde(default)]
    pub max_width: u32,
    #[serde(default)]
    pub max_height: u32,
    /// 10-bit 4:2:0 (HEVC Main10) for HDR10.
    #[serde(default)]
    pub ten_bit: bool,
}

/// What the client asks the host to stream. Sent again to change it.
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StreamOptions {
    /// "auto" / "h264" / "hevc" / "av1".
    #[serde(default)]
    pub codec: String,
    /// 0 = the host decides.
    #[serde(default)]
    pub bitrate_kbps: u32,
    #[serde(default)]
    pub game: bool,
    /// "auto" / "stream" / "datagram".
    #[serde(default)]
    pub video_transport: String,
    /// The size of the virtual screens on the host; `None` = the host's
    /// displays as they are.
    #[serde(default)]
    pub virtual_screen: Option<VirtualScreen>,
    /// How many virtual screens (1..=4) of that size; 0 counts as 1.
    #[serde(default)]
    pub virtual_count: u32,
    /// Switch the host's physical displays off while connected (privacy).
    #[serde(default)]
    pub physical_off: bool,
    /// Block the host's own keyboard and mouse while connected.
    #[serde(default)]
    pub block_input: bool,
    /// Host encoder: "auto" / "nvenc" / "qsv" / "amf" / "software".
    #[serde(default)]
    pub encoder: String,
    /// Host display to show; 0 = primary (the virtual screen while there is one).
    #[serde(default)]
    pub display_id: u32,
    /// "auto" / "quality" / "balanced" / "smooth" / "fixed".
    #[serde(default)]
    pub bitrate_policy: String,
    /// The phone shows HDR: ask for HDR10 (FEATURE_HDR).
    #[serde(default)]
    pub hdr: bool,
}

#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VirtualScreen {
    pub width: u32,
    pub height: u32,
    #[serde(default)]
    pub refresh_hz: u32,
    /// Windows display scaling; 0 = leave as is.
    #[serde(default)]
    pub scale_percent: u32,
}

pub fn parse_codec(s: &str) -> pb::Codec {
    match s.to_ascii_lowercase().as_str() {
        "h264" | "avc" => pb::Codec::H264,
        "hevc" | "h265" => pb::Codec::Hevc,
        "av1" => pb::Codec::Av1,
        _ => pb::Codec::Unspecified,
    }
}

/// Virtual display sizes: width a multiple of 8, height even, at least 640x480
/// (same rule as the Windows client).
fn vd_dims(w: u32, h: u32) -> (u32, u32) {
    ((w & !7).max(640), (h & !1).max(480))
}

impl StreamOptions {
    pub fn to_start(&self) -> pb::StartStream {
        let screens: Vec<pb::VirtualScreen> = self
            .virtual_screen
            .as_ref()
            .map(|v| {
                let (width, height) = vd_dims(v.width, v.height);
                let screen = pb::VirtualScreen {
                    width,
                    height,
                    refresh_hz: if v.refresh_hz == 0 { 60 } else { v.refresh_hz },
                    scale_percent: v.scale_percent,
                };
                vec![screen; self.virtual_count.clamp(1, 4) as usize]
            })
            .unwrap_or_default();
        // Same rule as the Windows client: a setup only with virtual screens or blocked input.
        let setup = (!screens.is_empty() || self.block_input).then(|| pb::DisplaySetup {
            physical_off: self.physical_off && !screens.is_empty(),
            virtual_screens: screens,
            block_local_input: self.block_input,
        });
        pb::StartStream {
            // 0 = primary, which is the virtual screen while there is one.
            display_id: self.display_id,
            display_setup: setup,
            slot: 0,
            config: Some(pb::StreamConfig {
                codec: parse_codec(&self.codec) as i32,
                chroma: pb::Chroma::Yuv420 as i32,
                width: 0,
                height: 0,
                fps: 0,
                bitrate_kbps: self.bitrate_kbps,
                mode: if self.game { pb::StreamMode::Game } else { pb::StreamMode::Office } as i32,
                bitrate_policy: match self.bitrate_policy.as_str() {
                    "quality" => pb::BitratePolicy::Quality,
                    "balanced" => pb::BitratePolicy::Balanced,
                    "smooth" => pb::BitratePolicy::Smooth,
                    "fixed" => pb::BitratePolicy::Fixed,
                    _ => pb::BitratePolicy::Unspecified,
                } as i32,
                video_transport: match self.video_transport.as_str() {
                    "stream" => pb::VideoTransport::Stream,
                    "datagram" => pb::VideoTransport::Datagram,
                    _ => pb::VideoTransport::Auto,
                } as i32,
                hdr: self.hdr,
            }),
            encoder_preference: match self.encoder.as_str() {
                "" | "auto" => String::new(),
                e => e.to_owned(),
            },
        }
    }
}

impl StartConfig {
    pub fn caps(&self) -> pb::ClientCaps {
        let decoders = self
            .decoders
            .iter()
            .filter_map(|d| {
                let codec = parse_codec(&d.codec);
                (codec != pb::Codec::Unspecified).then(|| pb::CodecCap {
                    codec: codec as i32,
                    chroma: pb::Chroma::Yuv420 as i32,
                    max_width: d.max_width,
                    max_height: d.max_height,
                    hardware: d.hardware,
                    ten_bit: d.ten_bit,
                })
            })
            .collect();
        pb::ClientCaps { decoders, max_width: 0, max_height: 0, max_fps: self.max_fps }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_kotlin_config() {
        let json = r#"{
            "address": "10.0.0.2", "clientName": "Pixel", "clientVersion": "0.1.0",
            "decoders": [{"codec": "hevc", "hardware": true, "maxWidth": 4096, "maxHeight": 2160}, {"codec": "vp9"}],
            "maxFps": 60,
            "stream": {"codec": "auto", "virtualScreen": {"width": 2401, "height": 1081, "refreshHz": 60, "scalePercent": 150}}
        }"#;
        let c: StartConfig = serde_json::from_str(json).unwrap();
        assert_eq!(c.pinned, None);
        let caps = c.caps();
        assert_eq!(caps.decoders.len(), 1, "unknown codecs are left out");
        assert_eq!(caps.decoders[0].codec, pb::Codec::Hevc as i32);
        let s = c.stream.to_start();
        let vs = &s.display_setup.unwrap().virtual_screens[0];
        assert_eq!((vs.width, vs.height, vs.scale_percent), (2400, 1080, 150));
        assert_eq!(s.config.unwrap().mode, pb::StreamMode::Office as i32);
    }

    #[test]
    fn no_virtual_screen_means_displays_as_they_are() {
        let s = StreamOptions { game: true, ..Default::default() }.to_start();
        assert!(s.display_setup.is_none());
        assert_eq!(s.config.unwrap().mode, pb::StreamMode::Game as i32);
    }

    #[test]
    fn several_virtual_screens_privacy_and_encoder() {
        let json = r#"{"virtualScreen": {"width": 1920, "height": 1080}, "virtualCount": 3,
            "physicalOff": true, "blockInput": true, "encoder": "qsv"}"#;
        let s = serde_json::from_str::<StreamOptions>(json).unwrap().to_start();
        let setup = s.display_setup.unwrap();
        assert_eq!(setup.virtual_screens.len(), 3);
        assert!(setup.physical_off && setup.block_local_input);
        assert_eq!(s.encoder_preference, "qsv");
        // Blocking input alone still needs a setup; the physical screens stay on.
        let s = StreamOptions { block_input: true, physical_off: true, encoder: "auto".into(), ..Default::default() }.to_start();
        let setup = s.display_setup.unwrap();
        assert!(setup.virtual_screens.is_empty() && !setup.physical_off && setup.block_local_input);
        assert_eq!(s.encoder_preference, "");
    }
}
