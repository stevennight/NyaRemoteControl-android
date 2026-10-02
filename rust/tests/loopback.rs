//! The core against a fake host on loopback: real QUIC, handshake, pairing,
//! stream request, a video frame through the gate, and a keyframe request
//! after a gap. Runs on the development machine (no phone needed).

use std::time::{Duration, Instant};

use nya_android::events::Event;
use nya_android::options::{StartConfig, StreamOptions, VirtualScreen};
use nya_android::session::{Next, Session};
use nya_proto::frame::{frame_flags, stream_type, VideoFrameHeader};
use nya_proto::framing::{encode_varint, expect_msg, read_msg, write_msg};
use nya_proto::negotiate::{self, LocalVersion};
use nya_proto::pb::{self, control_msg::Msg};
use nya_proto::MAX_MESSAGE_LEN;
use nya_transport::identity::peer_fingerprint;
use nya_transport::pairing::{self, PairingKey, Transcript};
use nya_transport::Identity;

fn ctl(m: Msg) -> pb::ControlMsg {
    pb::ControlMsg { msg: Some(m) }
}

fn frame(id: u64, key: bool, payload: &[u8]) -> Vec<u8> {
    let h = VideoFrameHeader {
        flags: if key { frame_flags::KEYFRAME } else { 0 },
        frame_id: id,
        capture_ts_us: nya_proto::now_us(),
        width: 64,
        height: 32,
        codec: pb::Codec::Hevc as u8,
        chroma: pb::Chroma::Yuv420 as u8,
    };
    let mut body = Vec::new();
    h.write(&mut body);
    body.extend_from_slice(payload);
    let mut out = (body.len() as u32).to_le_bytes().to_vec();
    out.extend(body);
    out
}

/// What the fake host saw.
struct Seen {
    start: pb::StartStream,
    keyframe_requested: bool,
}

async fn fake_host(endpoint: nya_transport::quinn::Endpoint, id: Identity, key: PairingKey) -> Seen {
    let conn = endpoint.accept().await.unwrap().await.unwrap();
    let client_fp = peer_fingerprint(&conn).unwrap();
    let (mut send, mut recv) = conn.accept_bi().await.unwrap();

    let hello: pb::Hello = expect_msg(&mut recv, MAX_MESSAGE_LEN).await.unwrap();
    assert_eq!(hello.client_name, "test phone");
    let neg = negotiate::negotiate(&hello, &LocalVersion::current()).unwrap();
    let welcome = pb::Welcome {
        proto_major: neg.major,
        proto_minor: neg.minor,
        server_name: "fake host".into(),
        server_version: "0.0.0".into(),
        features: neg.features.iter().copied().collect(),
        needs_pairing: true,
    };
    write_msg(&mut send, &pb::HelloReply { reply: Some(pb::hello_reply::Reply::Welcome(welcome)) }).await.unwrap();

    let server_nonce = pairing::nonce();
    write_msg(&mut send, &ctl(Msg::AuthChallenge(pb::AuthChallenge { server_nonce: server_nonce.to_vec() }))).await.unwrap();
    let resp: pb::ControlMsg = expect_msg(&mut recv, MAX_MESSAGE_LEN).await.unwrap();
    let Some(Msg::AuthResponse(r)) = resp.msg else { panic!("expected AuthResponse") };
    let t = Transcript { server_nonce: &server_nonce, client_nonce: &r.client_nonce, server_fp: id.fingerprint(), client_fp };
    assert!(t.verify_client(&key, &r.mac), "client proved the pairing code");
    let ok = pb::AuthResult { ok: true, server_mac: t.server_mac(&key), message: String::new() };
    write_msg(&mut send, &ctl(Msg::AuthResult(ok))).await.unwrap();

    let caps: pb::ControlMsg = expect_msg(&mut recv, MAX_MESSAGE_LEN).await.unwrap();
    assert!(matches!(caps.msg, Some(Msg::ClientCaps(_))));
    let start: pb::ControlMsg = expect_msg(&mut recv, MAX_MESSAGE_LEN).await.unwrap();
    let Some(Msg::StartStream(start)) = start.msg else { panic!("expected StartStream") };

    // Video: a keyframe, then a frame after a gap (2 is missing).
    let mut v = conn.open_uni().await.unwrap();
    let mut prelude = Vec::new();
    encode_varint(stream_type::VIDEO, &mut prelude);
    encode_varint(7, &mut prelude);
    if neg.has(pb::Feature::MultiStream) {
        encode_varint(0, &mut prelude);
    }
    v.write_all(&prelude).await.unwrap();
    v.write_all(&frame(1, true, b"\0\0\0\x01key")).await.unwrap();
    v.write_all(&frame(3, false, b"\0\0\0\x01late")).await.unwrap();

    let mut keyframe_requested = false;
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        match tokio::time::timeout(Duration::from_secs(5), read_msg::<pb::ControlMsg, _>(&mut recv, MAX_MESSAGE_LEN)).await {
            Ok(Ok(Some(m))) => match m.msg {
                Some(Msg::RequestKeyframe(_)) => {
                    keyframe_requested = true;
                    break;
                }
                Some(Msg::Ping(p)) => {
                    let pong = pb::Pong { t_us: p.t_us, server_t_us: nya_proto::now_us() };
                    write_msg(&mut send, &ctl(Msg::Pong(pong))).await.unwrap();
                }
                _ => {}
            },
            _ => break,
        }
    }
    // Wait for the client's goodbye.
    while let Ok(Some(m)) = read_msg::<pb::ControlMsg, _>(&mut recv, MAX_MESSAGE_LEN).await {
        if matches!(m.msg, Some(Msg::Bye(_))) {
            break;
        }
    }
    Seen { start, keyframe_requested }
}

fn wait_event(s: &Session, want: impl Fn(&Event) -> bool) -> Event {
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        if let Next::Item(e) = s.poll_event(Duration::from_millis(200)) {
            if let Event::Disconnected { message } = &e {
                if !want(&e) {
                    panic!("disconnected: {message}");
                }
            }
            if want(&e) {
                return e;
            }
        }
    }
    panic!("event not received");
}

#[test]
fn pairs_streams_and_resyncs_on_gaps() {
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
    let host_id = Identity::generate().unwrap();
    let key = PairingKey::generate();
    let endpoint = rt.block_on(async { nya_transport::endpoint::server_endpoint("127.0.0.1:0".parse().unwrap(), &host_id) }).unwrap();
    let addr = endpoint.local_addr().unwrap();
    let host = rt.spawn(fake_host(endpoint, host_id.clone(), key.clone()));

    let dir = std::env::temp_dir().join(format!("nya-android-test-{}", std::process::id()));
    let cfg = StartConfig {
        address: addr.to_string(),
        pinned: None,
        pair_code: Some(key.to_code()),
        client_name: "test phone".into(),
        client_version: "0.0.0".into(),
        decoders: vec![],
        max_fps: 60,
        stream: StreamOptions {
            codec: "hevc".into(),
            virtual_screen: Some(VirtualScreen { width: 2400, height: 1080, refresh_hz: 60, scale_percent: 150 }),
            ..Default::default()
        },
    };
    let session = Session::start(&dir, cfg).unwrap();

    let Event::Connected { server_fingerprint, .. } = wait_event(&session, |e| matches!(e, Event::Connected { .. })) else { unreachable!() };
    assert_eq!(server_fingerprint, host_id.fingerprint().to_hex(), "pin the host we paired with");

    let v = loop {
        match session.next_video(Duration::from_secs(5)) {
            Next::Item(v) => break v,
            Next::Timeout => panic!("no video"),
            Next::Closed => panic!("closed"),
        }
    };
    assert!(v.header.is_keyframe());
    assert_eq!(v.payload(), b"\0\0\0\x01key");
    // The frame after the gap never reaches the decoder.
    assert!(matches!(session.next_video(Duration::from_millis(500)), Next::Timeout));

    session.stop();
    wait_event(&session, |e| matches!(e, Event::Disconnected { .. }));
    let seen = rt.block_on(host).unwrap();
    assert!(seen.keyframe_requested, "a gap asks the host for a keyframe");
    let vs = &seen.start.display_setup.unwrap().virtual_screens[0];
    assert_eq!((vs.width, vs.height, vs.scale_percent), (2400, 1080, 150));
    drop(session);
    let _ = std::fs::remove_dir_all(dir);
}
