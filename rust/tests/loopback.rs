//! The core against a fake host on loopback: real QUIC, handshake, pairing,
//! stream request, a video frame through the gate, a keyframe request after
//! a gap, text input, and files both ways. Runs on the development machine (no phone needed).

use std::time::{Duration, Instant};

use nya_android::events::Event;
use nya_android::options::{StartConfig, StreamOptions, VirtualScreen};
use nya_android::session::{Next, Session};
use nya_proto::frame::{frame_flags, stream_type, VideoFrameHeader};
use nya_proto::framing::{encode_varint, expect_msg, read_msg, read_varint, write_msg};
use nya_transport::files;
use nya_proto::negotiate::{self, LocalVersion};
use nya_proto::pb::{self, control_msg::Msg, input_msg::Ev};
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
    text: String,
    upload: Option<(String, Vec<u8>)>,
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

    // The client's input stream and uploads.
    let (up_tx, mut up_rx) = tokio::sync::mpsc::unbounded_channel::<Uploaded>();
    let streams = {
        let conn = conn.clone();
        tokio::spawn(async move {
            while let Ok(mut r) = conn.accept_uni().await {
                let up_tx = up_tx.clone();
                tokio::spawn(async move {
                    match read_varint(&mut r).await {
                        Ok(Some(stream_type::INPUT)) => {
                            while let Ok(Some(m)) = read_msg::<pb::InputMsg, _>(&mut r, MAX_MESSAGE_LEN).await {
                                if let Some(Ev::Text(t)) = m.ev {
                                    let _ = up_tx.send(Uploaded::Text(t.text));
                                }
                            }
                        }
                        Ok(Some(stream_type::FILE)) => {
                            let h = files::read_header(&mut r).await.unwrap();
                            let data = files::receive_to_vec(&mut r, &h, 1 << 20).await.unwrap();
                            let _ = up_tx.send(Uploaded::File(h, data));
                        }
                        _ => {}
                    }
                });
            }
        })
    };

    // The phone's shared folder, read through an FS stream like the mounted drive does.
    let read = pb::FsRequest { path: "/phone/note.txt".into(), op: Some(pb::fs_request::Op::Read(pb::FsRead { offset: 0, len: 100 })) };
    let reply = nya_transport::folders::call(&conn, &read).await.unwrap();
    assert_eq!(reply.data, b"shared from the phone");
    let write = pb::FsRequest { path: "/phone/new.txt".into(), op: Some(pb::fs_request::Op::Create(pb::FsCreate { dir: false, exclusive: true })) };
    let reply = nya_transport::folders::call(&conn, &write).await.unwrap();
    assert_eq!(reply.error, pb::FsError::FsAccess as i32, "read-only share refuses changes");

    // Files copied on the host.
    let offer = pb::FileOffer {
        transfer_id: u64::MAX - 5, // above 2^53: must survive JSON as a string
        files: vec![pb::FileEntry { name: "host.txt".into(), size: 5, path: "host.txt".into(), is_dir: false }],
    };
    write_msg(&mut send, &ctl(Msg::FileOffer(offer.clone()))).await.unwrap();

    let mut seen = Seen { start, keyframe_requested: false, text: String::new(), upload: None };
    loop {
        tokio::select! {
            m = read_msg::<pb::ControlMsg, _>(&mut recv, MAX_MESSAGE_LEN) => match m {
                Ok(Some(m)) => match m.msg {
                    Some(Msg::RequestKeyframe(_)) => seen.keyframe_requested = true,
                    Some(Msg::Ping(p)) => {
                        let pong = pb::Pong { t_us: p.t_us, server_t_us: nya_proto::now_us() };
                        write_msg(&mut send, &ctl(Msg::Pong(pong))).await.unwrap();
                    }
                    Some(Msg::FileRequest(r)) => {
                        assert_eq!(r.transfer_id, offer.transfer_id);
                        assert_eq!(r.purpose, pb::FilePurpose::Save as i32);
                        let h = pb::FileHeader {
                            transfer_id: r.transfer_id,
                            name: "host.txt".into(),
                            size: 5,
                            purpose: pb::FilePurpose::Save as i32,
                            index: 0,
                            count: 1,
                            path: String::new(),
                        };
                        files::send_bytes(&conn, h, b"hello").await.unwrap();
                    }
                    Some(Msg::Bye(_)) => break,
                    _ => {}
                },
                _ => break,
            },
            Some(u) = up_rx.recv() => match u {
                Uploaded::Text(t) => seen.text.push_str(&t),
                Uploaded::File(h, data) => {
                    let ok = pb::FileResult { transfer_id: h.transfer_id, ok: true, message: "已保存 1 个文件".into(), saved_to: "C:/Downloads".into() };
                    write_msg(&mut send, &ctl(Msg::FileResult(ok))).await.unwrap();
                    seen.upload = Some((h.name, data));
                }
            },
        }
    }
    streams.abort();
    seen
}

enum Uploaded {
    Text(String),
    File(pb::FileHeader, Vec<u8>),
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
    std::fs::create_dir_all(dir.join("share")).unwrap();
    std::fs::write(dir.join("share").join("note.txt"), b"shared from the phone").unwrap();

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
        download_dir: Some(dir.join("received").to_string_lossy().into_owned()),
        shares: vec![nya_android::options::ShareConfig {
            name: "phone".into(),
            path: dir.join("share").to_string_lossy().into_owned(),
            read_only: true,
        }],
        reverify: false,
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

    // Text, as the phone keyboard would send it.
    session.input(Ev::Text(pb::TextInput { text: "你好 nya".into() }));

    // The host's offer arrives; download it.
    let Event::FileOffer { id, files, total_bytes } = wait_event(&session, |e| matches!(e, Event::FileOffer { .. })) else { unreachable!() };
    assert_eq!(id, (u64::MAX - 5).to_string());
    assert_eq!((files.len(), total_bytes), (1, 5));
    session.control(Msg::FileRequest(pb::FileRequest { transfer_id: id.parse().unwrap(), purpose: pb::FilePurpose::Save as i32 }));
    let Event::FilesReceived { paths, .. } = wait_event(&session, |e| matches!(e, Event::FilesReceived { .. })) else { unreachable!() };
    assert_eq!(std::fs::read(&paths[0]).unwrap(), b"hello");

    // Upload a file the "app" opened.
    let src = dir.join("phone.txt");
    std::fs::write(&src, b"from the phone").unwrap();
    let file = std::fs::File::open(&src).unwrap();
    session.send_files(vec![nya_android::files::Upload { file, name: "phone.txt".into(), size: 14 }]);
    let done = wait_event(&session, |e| matches!(e, Event::Transfer { finished: true, upload: true, .. }));
    let Event::Transfer { ok, message, .. } = done else { unreachable!() };
    assert!(ok, "{message}");

    session.stop();
    wait_event(&session, |e| matches!(e, Event::Disconnected { .. }));
    let seen = rt.block_on(host).unwrap();
    assert!(seen.keyframe_requested, "a gap asks the host for a keyframe");
    assert_eq!(seen.text, "你好 nya");
    assert_eq!(seen.upload, Some(("phone.txt".to_string(), b"from the phone".to_vec())));
    let vs = &seen.start.display_setup.unwrap().virtual_screens[0];
    assert_eq!((vs.width, vs.height, vs.scale_percent), (2400, 1080, 150));
    drop(session);
    let _ = std::fs::remove_dir_all(dir);
}

/// A host that already knows every client (no pairing): accepts connections
/// and reads control messages until the client goes away.
async fn known_client_host(endpoint: nya_transport::quinn::Endpoint) {
    while let Some(incoming) = endpoint.accept().await {
        tokio::spawn(async move {
            let Ok(conn) = incoming.await else { return };
            let Ok((mut send, mut recv)) = conn.accept_bi().await else { return };
            let Ok(hello) = expect_msg::<pb::Hello, _>(&mut recv, MAX_MESSAGE_LEN).await else { return };
            let neg = negotiate::negotiate(&hello, &LocalVersion::current()).unwrap();
            let welcome = pb::Welcome {
                proto_major: neg.major,
                proto_minor: neg.minor,
                server_name: "known".into(),
                server_version: "0.0.0".into(),
                features: neg.features.iter().copied().collect(),
                needs_pairing: false,
            };
            let _ = write_msg(&mut send, &pb::HelloReply { reply: Some(pb::hello_reply::Reply::Welcome(welcome)) }).await;
            while let Ok(Some(_)) = read_msg::<pb::ControlMsg, _>(&mut recv, MAX_MESSAGE_LEN).await {}
        });
    }
}

#[test]
fn changed_certificate_is_reported_then_verified_by_fingerprint() {
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
    let host_id = Identity::generate().unwrap();
    let endpoint = rt.block_on(async { nya_transport::endpoint::server_endpoint("127.0.0.1:0".parse().unwrap(), &host_id) }).unwrap();
    let addr = endpoint.local_addr().unwrap();
    rt.spawn(known_client_host(endpoint));
    let dir = std::env::temp_dir().join(format!("nya-android-pin-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let cfg = |pinned: Option<String>, reverify: bool| StartConfig {
        address: addr.to_string(),
        pinned,
        pair_code: None,
        client_name: "test phone".into(),
        client_version: "0.0.0".into(),
        decoders: vec![],
        max_fps: 60,
        stream: StreamOptions::default(),
        download_dir: None,
        shares: vec![],
        reverify,
    };

    // Pinned to another certificate: not a plain failure, the app can re-check.
    let other = Identity::generate().unwrap().fingerprint().to_hex();
    let s = Session::start(&dir, cfg(Some(other), false)).unwrap();
    wait_event(&s, |e| matches!(e, Event::PinChanged { .. }));
    drop(s);

    // Checked again without the pin: the fingerprint is shown first.
    for ok in [false, true] {
        let s = Session::start(&dir, cfg(None, true)).unwrap();
        let Event::VerifyFingerprint { fingerprint } = wait_event(&s, |e| matches!(e, Event::VerifyFingerprint { .. })) else { unreachable!() };
        assert_eq!(fingerprint, host_id.fingerprint().short());
        s.confirm_fingerprint(ok);
        if ok {
            let Event::Connected { server_fingerprint, .. } = wait_event(&s, |e| matches!(e, Event::Connected { .. })) else { unreachable!() };
            assert_eq!(server_fingerprint, host_id.fingerprint().to_hex());
            s.stop();
        } else {
            let Event::Disconnected { message } = wait_event(&s, |e| matches!(e, Event::Disconnected { .. })) else { unreachable!() };
            assert!(message.contains("未确认"), "{message}");
        }
        drop(s);
    }
    let _ = std::fs::remove_dir_all(dir);
}
