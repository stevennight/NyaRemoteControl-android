//! Connection, handshake, pairing and the long-running session with automatic
//! reconnection. A trimmed port of the Windows client's net.rs: video, audio,
//! cursor, input, clipboard text and stream control; no files, USB, folders.

use std::collections::BTreeSet;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use nya_proto::frame::{datagram_type, stream_type, VideoFrameHeader};
use nya_proto::framing::{encode_varint, expect_msg, read_msg, read_varint, write_msg};
use nya_proto::negotiate::{self, LocalVersion, Negotiated};
use nya_proto::pb::{self, control_msg::Msg, Feature};
use nya_proto::{MAX_MESSAGE_LEN, MAX_VIDEO_FRAME_LEN};
use nya_transport::identity::peer_fingerprint;
use nya_transport::pairing::{self, PairingKey, Transcript};
use nya_transport::quinn::{Connection, Endpoint, RecvStream, SendStream};
use nya_transport::{Fingerprint, Identity};
use tokio::sync::mpsc;
use tokio::time::timeout;

use crate::events::Event;
use crate::options::StartConfig;
use crate::session::{ctl, NetCmd, Shared};

/// Give up reconnecting after this long.
const RECONNECT_FOR: Duration = Duration::from_secs(120);

/// Features this client implements. Not `LocalVersion::current()`: that is
/// everything the Windows client does (files, USB, printing, HDR, ...).
pub fn local_version() -> LocalVersion {
    let features: BTreeSet<u32> = [
        Feature::Audio,
        Feature::LocalCursor,
        Feature::ClipboardText,
        Feature::StaticRefine,
        Feature::Sas,
        Feature::MultiGpuInfo,
        Feature::VirtualDisplay,
        Feature::MultiStream,
        Feature::MultiClient,
        Feature::VideoDatagram,
    ]
    .into_iter()
    .map(|f| f as u32)
    .collect();
    LocalVersion { features, ..LocalVersion::current() }
}

pub type PairPrompt = Arc<dyn Fn() -> Option<String> + Send + Sync>;

pub struct Link {
    pub endpoint: Endpoint,
    pub conn: Connection,
    pub send: SendStream,
    pub recv: RecvStream,
    pub neg: Negotiated,
    pub welcome: pb::Welcome,
    pub server_fp: Fingerprint,
}

/// Connect and complete the handshake (and pairing if the host asks for it).
pub async fn connect(
    addr: SocketAddr,
    id: &Identity,
    pinned: Option<Fingerprint>,
    client_name: &str,
    client_version: &str,
    prompt: Option<PairPrompt>,
) -> Result<Link> {
    let endpoint = nya_transport::endpoint::client_endpoint(addr)?;
    let conn = timeout(Duration::from_secs(8), nya_transport::endpoint::connect(&endpoint, addr, id, pinned))
        .await
        .map_err(|_| anyhow!("连接 {addr} 超时（检查组网是否连通、被控端是否运行、防火墙 UDP 端口）"))??;
    let server_fp = peer_fingerprint(&conn).ok_or_else(|| anyhow!("被控端没有证书"))?;
    let (mut send, mut recv) = conn.open_bi().await?;

    let me = local_version();
    write_msg(&mut send, &negotiate::hello(&me, client_name, client_version)).await?;
    let reply: pb::HelloReply = timeout(Duration::from_secs(10), expect_msg(&mut recv, MAX_MESSAGE_LEN))
        .await
        .context("等待被控端响应超时")??;
    let welcome = match reply.reply {
        Some(pb::hello_reply::Reply::Welcome(w)) => w,
        Some(pb::hello_reply::Reply::Reject(r)) => bail!("被控端拒绝连接：{}", r.message),
        None => bail!("被控端响应无法识别（版本差异过大？）"),
    };
    let neg = negotiate::accept_welcome(&welcome, &me).map_err(|e| anyhow!(e))?;

    if welcome.needs_pairing {
        let challenge: pb::ControlMsg = expect_msg(&mut recv, MAX_MESSAGE_LEN).await?;
        let Some(Msg::AuthChallenge(ch)) = challenge.msg else { bail!("expected AuthChallenge") };
        let Some(prompt) = prompt else {
            bail!("被控端要求重新配对（可能被控端重装或移除了本机），请重新连接并输入配对码");
        };
        let code = tokio::task::spawn_blocking(move || prompt()).await?.ok_or_else(|| anyhow!("已取消配对"))?;
        let key = PairingKey::from_code(&code).ok_or_else(|| anyhow!("配对码格式不正确（24 位，形如 XXXX-XXXX-XXXX-XXXX-XXXX-XXXX）"))?;
        let client_nonce = pairing::nonce();
        let t = Transcript { server_nonce: &ch.server_nonce, client_nonce: &client_nonce, server_fp, client_fp: id.fingerprint() };
        let resp = pb::AuthResponse { client_nonce: client_nonce.to_vec(), mac: t.client_mac(&key) };
        write_msg(&mut send, &ctl(Msg::AuthResponse(resp))).await?;
        let res: pb::ControlMsg = expect_msg(&mut recv, MAX_MESSAGE_LEN).await?;
        let Some(Msg::AuthResult(r)) = res.msg else { bail!("expected AuthResult") };
        if !r.ok {
            bail!("配对失败：{}", r.message);
        }
        if !t.verify_server(&key, &r.server_mac) {
            bail!("被控端无法证明它知道配对码，已中止（可能存在中间人）");
        }
    } else if pinned.is_none() {
        tracing::warn!("host already knows this client but we have no pin; trusting {server_fp}");
    }
    Ok(Link { endpoint, conn, send, recv, neg, welcome, server_fp })
}

/// What a reconnect replays.
struct Params {
    addr: SocketAddr,
    pinned: Fingerprint,
    identity: Identity,
    name: String,
    version: String,
    caps: pb::ClientCaps,
    start: pb::StartStream,
}

enum End {
    UserQuit,
    Fatal(String),
    Lost(String),
}

/// The whole life of a session: resolve, connect (pairing), run, reconnect.
pub async fn main(cfg: StartConfig, identity: Identity, sh: Arc<Shared>, mut cmds: mpsc::UnboundedReceiver<NetCmd>) {
    sh.event(Event::Connecting);
    let address = cfg.address.clone();
    let addr = match tokio::task::spawn_blocking(move || nya_transport::endpoint::resolve(&address, nya_proto::DEFAULT_PORT)).await {
        Ok(Ok(a)) => a,
        Ok(Err(e)) => return sh.event(Event::Disconnected { message: format!("{e:#}") }),
        Err(e) => return sh.event(Event::Disconnected { message: format!("{e}") }),
    };
    let pinned = cfg.pinned.as_deref().and_then(Fingerprint::from_hex);
    // The code typed when adding the host is used once; after that, ask.
    let typed = Mutex::new(cfg.pair_code.clone().filter(|c| !c.trim().is_empty()));
    let prompt: PairPrompt = {
        let sh = sh.clone();
        Arc::new(move || typed.lock().unwrap().take().or_else(|| sh.ask_pair_code()))
    };
    let first = tokio::select! {
        r = connect(addr, &identity, pinned, &cfg.client_name, &cfg.client_version, Some(prompt)) => r,
        _ = wait_quit(&mut cmds) => return sh.event(Event::Disconnected { message: "已取消".into() }),
    };
    let link = match first {
        Ok(l) => l,
        Err(e) => return sh.event(Event::Disconnected { message: format!("{e:#}") }),
    };
    let p = Params {
        addr,
        pinned: link.server_fp,
        identity,
        name: cfg.client_name.clone(),
        version: cfg.client_version.clone(),
        caps: cfg.caps(),
        start: cfg.stream.to_start(),
    };
    supervise(link, p, cmds, &sh).await;
}

/// Returns once Quit arrives (other commands are dropped meanwhile).
async fn wait_quit(cmds: &mut mpsc::UnboundedReceiver<NetCmd>) {
    loop {
        match cmds.recv().await {
            Some(NetCmd::Quit) | None => return,
            _ => {}
        }
    }
}

async fn supervise(first: Link, mut p: Params, mut cmds: mpsc::UnboundedReceiver<NetCmd>, sh: &Arc<Shared>) {
    let mut link = Some(first);
    let mut lost_since: Option<Instant> = None;
    loop {
        let l = match link.take() {
            Some(l) => l,
            None => match connect(p.addr, &p.identity, Some(p.pinned), &p.name, &p.version, None).await {
                Ok(l) => l,
                Err(e) => {
                    let since = *lost_since.get_or_insert_with(Instant::now);
                    if since.elapsed() > RECONNECT_FOR {
                        sh.event(Event::Disconnected { message: format!("无法重新连接：{e:#}") });
                        return;
                    }
                    sh.event(Event::Reconnecting { message: format!("{e:#}") });
                    let deadline = tokio::time::sleep(Duration::from_secs(2));
                    tokio::pin!(deadline);
                    loop {
                        tokio::select! {
                            _ = &mut deadline => break,
                            c = cmds.recv() => match c {
                                Some(NetCmd::Quit) | None => return sh.event(Event::Disconnected { message: "已断开".into() }),
                                Some(NetCmd::Control(m)) => track(&mut p, &m),
                                _ => {}
                            }
                        }
                    }
                    continue;
                }
            },
        };
        lost_since = None;
        sh.reset_video();
        sh.event(Event::Connected {
            server_name: l.welcome.server_name.clone(),
            server_version: l.welcome.server_version.clone(),
            server_fingerprint: l.server_fp.to_hex(),
            server_fingerprint_short: l.server_fp.short(),
        });
        match run(l, &mut p, &mut cmds, sh).await {
            End::UserQuit => return sh.event(Event::Disconnected { message: "已断开".into() }),
            End::Fatal(msg) => return sh.event(Event::Disconnected { message: msg }),
            End::Lost(msg) => {
                tracing::warn!("connection lost: {msg}");
                sh.event(Event::Reconnecting { message: msg });
            }
        }
    }
}

/// Keep the replayable state current.
fn track(p: &mut Params, m: &pb::ControlMsg) {
    match &m.msg {
        Some(Msg::StartStream(s)) if s.slot == 0 => p.start = s.clone(),
        Some(Msg::SetMode(m)) => p.start.config.get_or_insert_with(Default::default).mode = m.mode,
        Some(Msg::ClientCaps(c)) => p.caps = c.clone(),
        _ => {}
    }
}

async fn run(link: Link, p: &mut Params, cmds: &mut mpsc::UnboundedReceiver<NetCmd>, sh: &Arc<Shared>) -> End {
    let Link { endpoint: _endpoint, conn, mut send, mut recv, neg, .. } = link;

    let setup = async {
        write_msg(&mut send, &ctl(Msg::ClientCaps(p.caps.clone()))).await?;
        write_msg(&mut send, &ctl(Msg::StartStream(p.start.clone()))).await?;
        let mut input = conn.open_uni().await?;
        input.set_priority(20)?;
        let mut prelude = Vec::new();
        encode_varint(stream_type::INPUT, &mut prelude);
        input.write_all(&prelude).await?;
        anyhow::Ok(input)
    };
    let mut input = match setup.await {
        Ok(i) => i,
        Err(e) => return End::Lost(format!("{e:#}")),
    };

    let uni = tokio::spawn(accept_uni(conn.clone(), sh.clone(), neg.has(Feature::MultiStream)));
    // The host opens bidi streams only for features we don't offer.
    let bidi = tokio::spawn({
        let conn = conn.clone();
        async move {
            while let Ok((_send, mut recv)) = conn.accept_bi().await {
                let _ = recv.stop(0u32.into());
            }
        }
    });
    let dgram = tokio::spawn(read_datagrams(conn.clone(), sh.clone(), neg.has(Feature::Audio), neg.has(Feature::VideoDatagram)));
    let mut ping = tokio::time::interval(Duration::from_secs(1));
    let mut stats = tokio::time::interval(Duration::from_secs(1));
    let mut stats_at = Instant::now();
    let clipboard = neg.has(Feature::ClipboardText);

    let end = loop {
        tokio::select! {
            m = read_msg::<pb::ControlMsg, _>(&mut recv, MAX_MESSAGE_LEN) => {
                let m = match m {
                    Ok(Some(m)) => m,
                    Ok(None) => break End::Lost("被控端关闭了连接".into()),
                    Err(e) => break End::Lost(format!("{e}")),
                };
                match m.msg {
                    Some(Msg::SessionInfo(i)) => sh.event(Event::session_info(&i)),
                    Some(Msg::SessionRole(r)) => sh.event(Event::Role { controlling: r.controlling, controller: r.controller, viewers: r.viewers }),
                    Some(Msg::StreamStarted(s)) if s.slot == 0 => {
                        tracing::info!("stream started: {:?} via {}", s.config, s.encoder_name);
                        sh.event(Event::stream_started(&s));
                    }
                    Some(Msg::StreamError(e)) if e.slot == 0 => sh.event(Event::StreamError { message: e.message }),
                    Some(Msg::ServerStats(s)) => sh.stats.on_server(s),
                    Some(Msg::ClipboardText(c)) if clipboard => sh.event(Event::Clipboard { text: c.text }),
                    Some(Msg::Pong(p)) => sh.stats.on_pong(p.t_us, p.server_t_us),
                    Some(Msg::Bye(b)) => break End::Fatal(format!("被控端断开：{}", b.reason)),
                    Some(other) => tracing::debug!("ignoring {other:?}"),
                    None => tracing::debug!("ignoring unknown control message"),
                }
            }
            c = cmds.recv() => match c {
                Some(NetCmd::Input(m)) => {
                    if let Err(e) = write_msg(&mut input, &m).await {
                        break End::Lost(format!("input: {e}"));
                    }
                }
                Some(NetCmd::Control(m)) => {
                    track(p, &m);
                    if matches!(m.msg, Some(Msg::ClipboardText(_))) && !clipboard {
                        continue;
                    }
                    if let Err(e) = write_msg(&mut send, &m).await {
                        break End::Lost(format!("control: {e}"));
                    }
                }
                Some(NetCmd::Quit) | None => {
                    let _ = write_msg(&mut send, &ctl(Msg::Bye(pb::Bye { reason: "用户断开".into() }))).await;
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    conn.close(0u32.into(), b"bye");
                    break End::UserQuit;
                }
            },
            _ = ping.tick() => {
                let _ = write_msg(&mut send, &ctl(Msg::Ping(pb::Ping { t_us: nya_proto::now_us() }))).await;
            }
            _ = stats.tick() => {
                let (line, client) = sh.stats.take(stats_at.elapsed().as_secs_f32());
                stats_at = Instant::now();
                sh.event(Event::Stats(line));
                let _ = write_msg(&mut send, &ctl(Msg::ClientStats(client))).await;
            }
            e = conn.closed() => break End::Lost(format!("{e}")),
        }
    };
    uni.abort();
    bidi.abort();
    dgram.abort();
    end
}

async fn accept_uni(conn: Connection, sh: Arc<Shared>, multi: bool) {
    while let Ok(mut r) = conn.accept_uni().await {
        let sh = sh.clone();
        tokio::spawn(async move {
            match read_varint(&mut r).await {
                Ok(Some(stream_type::VIDEO)) => {
                    let Ok(Some(stream_id)) = read_varint(&mut r).await else { return };
                    // With FEATURE_MULTI_STREAM the prelude names the window (slot).
                    let slot = if multi {
                        let Ok(Some(slot)) = read_varint(&mut r).await else { return };
                        slot
                    } else {
                        0
                    };
                    if slot != 0 {
                        let _ = r.stop(0u32.into());
                        return;
                    }
                    tracing::info!("video stream {stream_id} opened by host");
                    loop {
                        let mut len = [0u8; 4];
                        if r.read_exact(&mut len).await.is_err() {
                            return;
                        }
                        let len = u32::from_le_bytes(len) as usize;
                        if !(VideoFrameHeader::LEN_V1..=MAX_VIDEO_FRAME_LEN).contains(&len) {
                            tracing::warn!("bad video frame length {len}");
                            return;
                        }
                        let mut buf = vec![0u8; len];
                        if r.read_exact(&mut buf).await.is_err() {
                            return;
                        }
                        sh.deliver(stream_id, buf);
                    }
                }
                Ok(Some(stream_type::CURSOR)) => {
                    while let Ok(Some(m)) = read_msg::<pb::CursorMsg, _>(&mut r, MAX_MESSAGE_LEN).await {
                        if let Some(e) = Event::cursor(m) {
                            sh.event(e);
                        }
                    }
                }
                Ok(Some(other)) => {
                    tracing::debug!("ignoring stream type {other}");
                    let _ = r.stop(0u32.into());
                }
                _ => {}
            }
        });
    }
}

/// Audio, and video sent as datagrams with FEC (FEATURE_VIDEO_DATAGRAM).
async fn read_datagrams(conn: Connection, sh: Arc<Shared>, audio_on: bool, video_on: bool) {
    let mut frames = nya_transport::videodgram::Reassembler::new(MAX_VIDEO_FRAME_LEN);
    let mut published = Instant::now();
    while let Ok(d) = conn.read_datagram().await {
        match d.first() {
            Some(&datagram_type::AUDIO) if audio_on => {
                // Kotlin gets the whole datagram (type, seq, timestamp, Opus).
                sh.audio(d.to_vec());
            }
            Some(&datagram_type::VIDEO) if video_on => {
                if let Some(f) = frames.push(&d) {
                    if f.slot == 0 {
                        sh.deliver(f.stream_id, f.data);
                    }
                }
                if published.elapsed() >= Duration::from_millis(250) {
                    published = Instant::now();
                    sh.stats.add_dgram(&frames.take_stats());
                }
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offers_only_what_android_implements() {
        let v = local_version();
        assert!(v.has(Feature::VirtualDisplay) && v.has(Feature::VideoDatagram) && v.has(Feature::Audio));
        for f in [Feature::FileTransfer, Feature::UsbRedirect, Feature::FolderMount, Feature::Print, Feature::Hdr, Feature::Yuv444] {
            assert!(!v.has(f), "{f:?} must not be offered");
        }
        assert_eq!(v.major, nya_proto::PROTO_MAJOR);
    }
}
