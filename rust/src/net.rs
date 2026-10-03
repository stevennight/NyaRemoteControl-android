//! Connection, handshake, pairing and the long-running session with automatic
//! reconnection. A trimmed port of the Windows client's net.rs: video, audio,
//! cursor, input (keys, text, gamepads), clipboard (text, images, files),
//! file transfer (over the TCP file channel when the host offers one,
//! cancellable), printing, microphone, folder mount, USB tunnels, stream
//! control and the connection mode (QUIC over UDP or over TCP). Not offered:
//! 4:4:4 (phone decoders are 4:2:0).

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
use nya_transport::files::FileLink;
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
        Feature::FileTransfer,
        Feature::Gamepad,
        Feature::TextInput,
        Feature::ClipboardImage,
        Feature::ClipboardFiles,
        Feature::Microphone,
        Feature::FolderMount,
        Feature::Print,
        Feature::Hdr,
        Feature::UsbRedirect,
        Feature::TcpFiles,
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
    /// QUIC over TCP (else UDP).
    pub via_tcp: bool,
}

/// How a session travels (setting `transport`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transport {
    /// UDP; TCP when UDP does not connect or loses too much, back to UDP
    /// when it works again (see [`supervise`]).
    Auto,
    Udp,
    /// QUIC over TCP (nya_transport::tcptunnel).
    Tcp,
}

impl Transport {
    pub fn parse(s: &str) -> Self {
        match s {
            "udp" => Self::Udp,
            "tcp" => Self::Tcp,
            _ => Self::Auto,
        }
    }
}

/// Auto: how long the preferred transport may try alone before the other one starts too.
pub const HEAD_START: Duration = Duration::from_millis(2500);
/// Auto: host-reported loss on UDP that counts as bad, and for how long it
/// has to last before the session moves to TCP.
const BAD_LOSS_PCT: f32 = 10.0;
const BAD_FOR: Duration = Duration::from_secs(20);
/// Auto, on TCP: how often UDP is tried again.
const UDP_PROBE_EVERY: Duration = Duration::from_secs(300);
/// Auto: after moving to TCP for loss, stay this long (doubling each time, up to an hour).
const FIRST_TCP_HOLD: Duration = Duration::from_secs(600);

type Opened = (Endpoint, Connection, bool);

async fn open_one(addr: SocketAddr, id: Identity, pinned: Option<Fingerprint>, tcp: bool) -> Result<Opened> {
    let endpoint = if tcp {
        nya_transport::tcptunnel::client_endpoint(addr).await?
    } else {
        nya_transport::endpoint::client_endpoint(addr)?
    };
    let conn = timeout(Duration::from_secs(8), nya_transport::endpoint::connect(&endpoint, addr, &id, pinned))
        .await
        .map_err(|_| anyhow!("{} 连接超时", if tcp { "TCP" } else { "UDP" }))??;
    Ok((endpoint, conn, tcp))
}

/// The QUIC connection: over UDP, over TCP, or (auto) the preferred one with
/// a head start and then both at once — the first that connects is used.
pub async fn open(addr: SocketAddr, id: &Identity, pinned: Option<Fingerprint>, mode: Transport, prefer_tcp: bool) -> Result<Opened> {
    let first_tcp = match mode {
        Transport::Udp => return open_one(addr, id.clone(), pinned, false).await,
        Transport::Tcp => return open_one(addr, id.clone(), pinned, true).await,
        Transport::Auto => prefer_tcp,
    };
    let a = open_one(addr, id.clone(), pinned, first_tcp);
    tokio::pin!(a);
    let mut err_a = None;
    tokio::select! {
        r = &mut a => match r {
            Ok(x) => return Ok(x),
            Err(e) => err_a = Some(e),
        },
        _ = tokio::time::sleep(HEAD_START) => {}
    }
    let b = open_one(addr, id.clone(), pinned, !first_tcp);
    tokio::pin!(b);
    let mut err_b = None;
    loop {
        tokio::select! {
            r = &mut a, if err_a.is_none() => match r {
                Ok(x) => return Ok(x),
                Err(e) => err_a = Some(e),
            },
            r = &mut b, if err_b.is_none() => match r {
                Ok(x) => return Ok(x),
                Err(e) => err_b = Some(e),
            },
        }
        if let (Some(ea), Some(eb)) = (&err_a, &err_b) {
            let (udp, tcp) = if first_tcp { (eb, ea) } else { (ea, eb) };
            bail!("UDP：{udp:#}；TCP：{tcp:#}（检查组网是否连通、被控端是否运行、防火墙和端口转发的 UDP / TCP 端口）");
        }
    }
}

/// Connect and complete the handshake (and pairing if the host asks for it).
#[allow(clippy::too_many_arguments)]
pub async fn connect(
    addr: SocketAddr,
    id: &Identity,
    pinned: Option<Fingerprint>,
    client_name: &str,
    client_version: &str,
    prompt: Option<PairPrompt>,
    mode: Transport,
    prefer_tcp: bool,
) -> Result<Link> {
    let (endpoint, conn, via_tcp) = open(addr, id, pinned, mode, prefer_tcp).await.with_context(|| format!("连接 {addr} 失败"))?;
    tracing::info!("connected to {addr} over {}", if via_tcp { "TCP" } else { "UDP" });
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
    Ok(Link { endpoint, conn, send, recv, neg, welcome, server_fp, via_tcp })
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
    download_dir: Option<std::path::PathBuf>,
    /// Connection mode (setting `transport`).
    transport: Transport,
    /// Auto: try TCP first until then (moved to TCP for loss).
    prefer_tcp_until: Option<Instant>,
    /// Auto: how long the next move to TCP for loss lasts.
    tcp_hold: Duration,
}

enum End {
    UserQuit,
    Fatal(String),
    Lost(String),
    /// Reconnect over TCP (`true`) or UDP, for this reason.
    Switch(bool, String),
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
    let transport = Transport::parse(&cfg.transport);
    let first = tokio::select! {
        r = connect(addr, &identity, pinned, &cfg.client_name, &cfg.client_version, Some(prompt), transport, false) => r,
        _ = wait_quit(&mut cmds) => return sh.event(Event::Disconnected { message: "已取消".into() }),
    };
    let link = match first {
        Ok(l) => l,
        Err(e) => {
            let message = format!("{e:#}");
            // The pin did not match: the app offers to check the host again.
            if pinned.is_some() && message.contains(nya_transport::tls::PIN_MISMATCH) {
                return sh.event(Event::PinChanged { message });
            }
            return sh.event(Event::Disconnected { message });
        }
    };
    // Connected without the saved pin and no pairing code proved who the host
    // is: the user compares the fingerprint with the host's own display.
    if cfg.reverify && !link.welcome.needs_pairing {
        let (sh2, fp) = (sh.clone(), link.server_fp.to_string());
        let ok = tokio::select! {
            r = tokio::task::spawn_blocking(move || sh2.ask_fingerprint_ok(fp)) => r.unwrap_or(false),
            _ = wait_quit(&mut cmds) => false,
        };
        if !ok {
            return sh.event(Event::Disconnected { message: "证书指纹未确认，已取消连接".into() });
        }
    }
    let p = Params {
        addr,
        pinned: link.server_fp,
        identity,
        name: cfg.client_name.clone(),
        version: cfg.client_version.clone(),
        caps: cfg.caps(),
        start: cfg.stream.to_start(),
        download_dir: cfg.download_dir.as_ref().map(std::path::PathBuf::from),
        transport,
        prefer_tcp_until: None,
        tcp_hold: FIRST_TCP_HOLD,
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
    // The next connection's transport when switching (else the setting).
    let mut next: Option<Transport> = None;
    loop {
        let l = match link.take() {
            Some(l) => l,
            None => match connect(
                p.addr,
                &p.identity,
                Some(p.pinned),
                &p.name,
                &p.version,
                None,
                next.take().unwrap_or(p.transport),
                p.prefer_tcp_until.is_some_and(|t| Instant::now() < t),
            )
            .await
            {
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
                                Some(NetCmd::SetTransport(t)) => p.transport = t,
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
            text_input: l.neg.has(Feature::TextInput),
            file_transfer: l.neg.has(Feature::FileTransfer),
            gamepad: l.neg.has(Feature::Gamepad),
            clipboard_image: l.neg.has(Feature::ClipboardImage),
            clipboard_files: l.neg.has(Feature::FileTransfer) && l.neg.has(Feature::ClipboardFiles),
            microphone: l.neg.has(Feature::Microphone),
            folder_mount: l.neg.has(Feature::FolderMount),
            print: l.neg.has(Feature::Print),
            usb: l.neg.has(Feature::UsbRedirect),
            hdr: l.neg.has(Feature::Hdr),
            virtual_display: l.neg.has(Feature::VirtualDisplay),
            tcp: l.via_tcp,
        });
        match run(l, &mut p, &mut cmds, sh).await {
            End::UserQuit => return sh.event(Event::Disconnected { message: "已断开".into() }),
            End::Fatal(msg) => return sh.event(Event::Disconnected { message: msg }),
            End::Lost(msg) => {
                tracing::warn!("connection lost: {msg}");
                sh.event(Event::Reconnecting { message: msg });
            }
            End::Switch(tcp, why) => {
                tracing::info!("switching to {}: {why}", if tcp { "TCP" } else { "UDP" });
                if tcp && p.transport == Transport::Auto {
                    p.prefer_tcp_until = Some(Instant::now() + p.tcp_hold);
                    p.tcp_hold = (p.tcp_hold * 2).min(Duration::from_secs(3600));
                } else if !tcp {
                    p.prefer_tcp_until = None;
                }
                next = Some(if tcp { Transport::Tcp } else { Transport::Udp });
                sh.event(Event::Reconnecting { message: format!("{why}，正在改用 {}", if tcp { "TCP" } else { "UDP" }) });
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

/// Stop transfer `id` here: sending stops at the next chunk, receiving fails
/// (partial files removed), what arrived of a download is forgotten.
fn cancel_transfer(link: &FileLink, downloads: &crate::files::Downloads, id: u64) {
    link.cancels().cancel(id);
    downloads.forget(id);
}

async fn run(link: Link, p: &mut Params, cmds: &mut mpsc::UnboundedReceiver<NetCmd>, sh: &Arc<Shared>) -> End {
    let Link { endpoint: _endpoint, conn, mut send, mut recv, neg, via_tcp, .. } = link;
    // Auto: since when the host has reported bad loss on UDP.
    let mut bad_since: Option<Instant> = None;
    // Auto, on TCP: try UDP again now and then.
    let mut udp_probe = tokio::time::interval_at(tokio::time::Instant::now() + UDP_PROBE_EVERY, UDP_PROBE_EVERY);
    let mut probe: Option<tokio::task::JoinHandle<bool>> = None;

    let setup = async {
        write_msg(&mut send, &ctl(Msg::ClientCaps(p.caps.clone()))).await?;
        write_msg(&mut send, &ctl(Msg::StartStream(p.start.clone()))).await?;
        let shares = sh.shares.lock().unwrap().clone();
        if neg.has(Feature::FolderMount) && !shares.0.is_empty() {
            write_msg(&mut send, &ctl(Msg::SharedFolders(shares.to_pb()))).await?;
        }
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

    let files_on = neg.has(Feature::FileTransfer);
    let images_on = neg.has(Feature::ClipboardImage);
    let clip_files_on = files_on && neg.has(Feature::ClipboardFiles);
    let mic_on = neg.has(Feature::Microphone);
    let mount_on = neg.has(Feature::FolderMount);
    let downloads = Arc::new(crate::files::Downloads::default());
    // Where files go: the TCP file channel once the host offered it and it
    // is up (FEATURE_TCP_FILES), FILE streams on this connection until then.
    let files_link = FileLink::new(conn.clone());
    let receive = crate::files::Receive {
        dir: p.download_dir.clone(),
        save: files_on,
        images: images_on,
        print: neg.has(Feature::Print),
        cancels: files_link.cancels().clone(),
    };
    let mut file_channel_task: Option<tokio::task::JoinHandle<()>> = None;
    let uni = tokio::spawn(accept_uni(conn.clone(), sh.clone(), neg.has(Feature::MultiStream), downloads.clone(), receive.clone()));
    // Bidi streams the host opens: folder requests (FS) and USB tunnels.
    let bidi = tokio::spawn({
        let (conn, sh) = (conn.clone(), sh.clone());
        let usb_on = neg.has(Feature::UsbRedirect);
        async move {
            while let Ok((send, mut recv)) = conn.accept_bi().await {
                let sh = sh.clone();
                tokio::spawn(async move {
                    match read_varint(&mut recv).await {
                        Ok(Some(stream_type::FS)) if mount_on => {
                            let shares = sh.shares.lock().unwrap().clone();
                            if let Err(e) = nya_transport::folders::serve_stream(send, recv, shares).await {
                                tracing::debug!("folder request: {e:#}");
                            }
                        }
                        Ok(Some(stream_type::TUNNEL)) if usb_on => {
                            let Ok(Some(port)) = read_varint(&mut recv).await else { return };
                            if let Err(e) = crate::usb::tunnel(send, recv, port, &sh).await {
                                tracing::debug!("usb tunnel: {e:#}");
                            }
                        }
                        _ => {
                            let _ = recv.stop(0u32.into());
                        }
                    }
                });
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
                    Some(Msg::ServerStats(s)) => {
                        if p.transport == Transport::Auto && !via_tcp && s.slot == 0 {
                            if s.path_loss_pct >= BAD_LOSS_PCT {
                                let since = *bad_since.get_or_insert_with(Instant::now);
                                if since.elapsed() >= BAD_FOR {
                                    break End::Switch(true, format!("UDP 丢包严重（{:.0}%）", s.path_loss_pct));
                                }
                            } else {
                                bad_since = None;
                            }
                        }
                        sh.stats.on_server(s);
                    }
                    Some(Msg::ClipboardText(c)) if clipboard => sh.event(Event::Clipboard { text: c.text }),
                    Some(Msg::Pong(p)) => sh.stats.on_pong(p.t_us, p.server_t_us),
                    Some(Msg::FileOffer(o)) if files_on => {
                        tracing::info!("host offers {} item(s)", o.files.len());
                        sh.event(downloads.offered(&o));
                    }
                    Some(Msg::FileResult(r)) => {
                        if !r.ok {
                            downloads.forget(r.transfer_id);
                        }
                        let message = if r.ok && !r.saved_to.is_empty() { format!("{}（{}）", r.message, r.saved_to) } else { r.message };
                        sh.event(Event::Transfer {
                            id: r.transfer_id.to_string(),
                            upload: true,
                            name: String::new(),
                            done: 0,
                            total: 0,
                            finished: true,
                            ok: r.ok,
                            message,
                        });
                    }
                    Some(Msg::FileCancel(c)) => {
                        tracing::info!("the host cancelled transfer {:016x}", c.transfer_id);
                        cancel_transfer(&files_link, &downloads, c.transfer_id);
                        sh.event(Event::TransferCancelled { id: c.transfer_id.to_string(), message: "被控端取消了传输".into() });
                    }
                    Some(Msg::FileChannel(fc)) if neg.has(Feature::TcpFiles) => {
                        // Same address and port as QUIC (a port forward needs both).
                        let addr = conn.remote_address();
                        let (identity, pinned, link) = (p.identity.clone(), p.pinned, files_link.clone());
                        let (sh, downloads, receive) = (sh.clone(), downloads.clone(), receive.clone());
                        if let Some(t) = file_channel_task.take() {
                            t.abort();
                        }
                        file_channel_task = Some(tokio::spawn(async move {
                            let on_file: nya_transport::filechan::OnFile = Arc::new(move |h, mut r| {
                                let (sh, downloads, receive) = (sh.clone(), downloads.clone(), receive.clone());
                                tokio::spawn(async move { crate::files::receive_body(h, &mut r, sh, downloads, receive).await });
                            });
                            match nya_transport::filechan::connect(addr, &identity, pinned, &fc.token, on_file).await {
                                Ok(ch) => {
                                    tracing::info!("files go over the TCP file channel ({addr})");
                                    link.set_tcp(Some(ch));
                                }
                                Err(e) => tracing::warn!("file channel (TCP {addr}): {e:#}; files go over QUIC"),
                            }
                        }));
                    }
                    Some(Msg::GamepadRumble(r)) => sh.event(Event::Rumble { index: r.index, large: r.large_motor, small: r.small_motor }),
                    Some(Msg::FileRequest(req)) if clip_files_on => {
                        // The host pastes files copied on the phone.
                        let items = sh.clip_out.lock().unwrap().items(req.transfer_id);
                        match items {
                            Some(items) => {
                                tracing::info!("host pastes our files (offer {:016x})", req.transfer_id);
                                tokio::spawn(crate::files::send_clipboard(files_link.clone(), req.transfer_id, items, sh.clone()));
                            }
                            None => {
                                let r = pb::FileResult { transfer_id: req.transfer_id, ok: false, message: "这批文件已过期，请在手机上重新复制".into(), saved_to: String::new() };
                                let _ = write_msg(&mut send, &ctl(Msg::FileResult(r))).await;
                            }
                        }
                    }
                    Some(Msg::FolderMountStatus(s)) => sh.event(Event::FolderMount { mounted: s.mounted, mount_point: s.mount_point, message: s.message }),
                    Some(Msg::UsbStatus(u)) => {
                        crate::usb::status(&sh, &u);
                        sh.event(Event::UsbStatus { busid: u.busid, attached: u.attached, message: u.message });
                    }
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
                    // Requested (again): the batch's progress starts over.
                    if let Some(Msg::FileRequest(r)) = &m.msg {
                        downloads.forget(r.transfer_id);
                    }
                    if let Err(e) = write_msg(&mut send, &m).await {
                        break End::Lost(format!("control: {e}"));
                    }
                }
                Some(NetCmd::SendFiles(items)) => {
                    if files_on {
                        tokio::spawn(crate::files::upload(files_link.clone(), items, sh.clone()));
                    } else {
                        sh.event(Event::Transfer {
                            id: String::new(), upload: true, name: String::new(), done: 0, total: 0,
                            finished: true, ok: false, message: "电脑上的被控端版本不支持文件传输，请升级被控端".into(),
                        });
                    }
                }
                Some(NetCmd::Mic(d)) => {
                    if mic_on {
                        let _ = conn.send_datagram(d.into());
                    }
                }
                Some(NetCmd::SendImage(dib)) => {
                    if images_on {
                        tokio::spawn(crate::files::send_image(files_link.clone(), dib));
                    }
                }
                Some(NetCmd::OfferFiles(paths)) => {
                    let offer = if clip_files_on { sh.clip_out.lock().unwrap().offer(&paths, true) } else { None };
                    match offer {
                        Some(o) => {
                            tracing::info!("offering {} copied item(s) to the host", o.files.len());
                            if let Err(e) = write_msg(&mut send, &ctl(Msg::FileOffer(o))).await {
                                break End::Lost(format!("control: {e}"));
                            }
                        }
                        None => sh.event(Event::Transfer {
                            id: String::new(), upload: true, name: String::new(), done: 0, total: 0, finished: true, ok: false,
                            message: if clip_files_on { "没有可复制的文件".into() } else { "电脑上的被控端版本不支持复制文件".into() },
                        }),
                    }
                }
                Some(NetCmd::CancelTransfer(id)) => {
                    tracing::info!("transfer {id:016x} cancelled here");
                    cancel_transfer(&files_link, &downloads, id);
                    if let Err(e) = write_msg(&mut send, &ctl(Msg::FileCancel(pb::FileCancel { transfer_id: id }))).await {
                        break End::Lost(format!("control: {e}"));
                    }
                }
                Some(NetCmd::SetTransport(t)) => {
                    p.transport = t;
                    match t {
                        Transport::Tcp if !via_tcp => break End::Switch(true, "已选择 TCP".into()),
                        Transport::Udp if via_tcp => break End::Switch(false, "已选择 UDP".into()),
                        _ => {}
                    }
                }
                Some(NetCmd::SetShares(s)) => {
                    let s = Arc::new(s);
                    *sh.shares.lock().unwrap() = s.clone();
                    if mount_on {
                        if let Err(e) = write_msg(&mut send, &ctl(Msg::SharedFolders(s.to_pb()))).await {
                            break End::Lost(format!("control: {e}"));
                        }
                    }
                }
                Some(NetCmd::Quit) | None => {
                    let _ = write_msg(&mut send, &ctl(Msg::Bye(pb::Bye { reason: "用户断开".into() }))).await;
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    conn.close(0u32.into(), b"bye");
                    break End::UserQuit;
                }
            },
            _ = udp_probe.tick(), if p.transport == Transport::Auto && via_tcp && probe.is_none()
                && p.prefer_tcp_until.is_none_or(|t| Instant::now() >= t) => {
                let (addr, id, pinned) = (p.addr, p.identity.clone(), p.pinned);
                probe = Some(tokio::spawn(async move {
                    match open_one(addr, id, Some(pinned), false).await {
                        Ok((ep, conn, _)) => {
                            conn.close(0u32.into(), b"probe");
                            ep.wait_idle().await;
                            true
                        }
                        Err(e) => {
                            tracing::info!("UDP still not usable: {e:#}");
                            false
                        }
                    }
                }));
            }
            Some(udp_ok) = async { match probe.as_mut() { Some(h) => h.await.ok(), None => std::future::pending().await } } => {
                probe = None;
                if udp_ok {
                    break End::Switch(false, "UDP 已恢复".into());
                }
            }
            _ = ping.tick() => {
                let _ = write_msg(&mut send, &ctl(Msg::Ping(pb::Ping { t_us: nya_proto::now_us() }))).await;
            }
            _ = stats.tick() => {
                let audio = sh.jitter.lock().unwrap().stats();
                let (line, client) = sh.stats.take(stats_at.elapsed().as_secs_f32(), audio);
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
    if let Some(t) = probe {
        t.abort();
    }
    if let Some(t) = file_channel_task {
        t.abort();
    }
    if let Some(ch) = files_link.tcp() {
        ch.close().await;
    }
    end
}

async fn accept_uni(
    conn: Connection,
    sh: Arc<Shared>,
    multi: bool,
    downloads: Arc<crate::files::Downloads>,
    receive: crate::files::Receive,
) {
    while let Ok(mut r) = conn.accept_uni().await {
        let (sh, downloads, receive) = (sh.clone(), downloads.clone(), receive.clone());
        tokio::spawn(async move {
            match read_varint(&mut r).await {
                Ok(Some(stream_type::FILE)) => crate::files::receive(r, sh, downloads, receive).await,
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
        assert!(v.has(Feature::TextInput) && v.has(Feature::FileTransfer) && v.has(Feature::Gamepad));
        assert!(v.has(Feature::FolderMount) && v.has(Feature::Print) && v.has(Feature::Microphone) && v.has(Feature::UsbRedirect));
        assert!(v.has(Feature::TcpFiles));
        for f in [Feature::Yuv444] {
            assert!(!v.has(f), "{f:?} must not be offered");
        }
        assert_eq!(v.major, nya_proto::PROTO_MAJOR);
    }

    #[test]
    fn transport_setting() {
        assert_eq!(Transport::parse("tcp"), Transport::Tcp);
        assert_eq!(Transport::parse("udp"), Transport::Udp);
        assert_eq!(Transport::parse("auto"), Transport::Auto);
        assert_eq!(Transport::parse(""), Transport::Auto, "absent = auto");
    }

    /// A host reachable over TCP only (UDP blocked): auto connects over TCP
    /// after UDP's head start; UDP only does not connect.
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn auto_falls_back_to_tcp() {
        use tokio::io::AsyncReadExt;
        let (host_id, client_id) = (Identity::generate().unwrap(), Identity::generate().unwrap());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let socket = nya_transport::tcptunnel::TunnelSocket::new(addr);
        let server = nya_transport::tcptunnel::server_endpoint(socket.clone(), &host_id).unwrap();
        tokio::spawn(async move {
            while let Ok((mut tcp, peer)) = listener.accept().await {
                let mut pre = [0u8; 8];
                if tcp.read_exact(&mut pre).await.is_ok() {
                    socket.add(tcp, peer);
                }
            }
        });
        tokio::spawn(async move {
            while let Some(i) = server.accept().await {
                tokio::spawn(async move {
                    if let Ok(c) = i.await {
                        c.closed().await;
                    }
                });
            }
        });
        let pin = Some(host_id.fingerprint());
        let start = Instant::now();
        let (_ep, conn, tcp) = open(addr, &client_id, pin, Transport::Auto, false).await.unwrap();
        assert!(tcp, "over TCP");
        assert!(start.elapsed() >= HEAD_START, "UDP had its head start");
        conn.close(0u32.into(), b"");
        // Preferring TCP (moved there for loss): connects at once.
        let start = Instant::now();
        let (_ep, _conn, tcp) = open(addr, &client_id, pin, Transport::Auto, true).await.unwrap();
        assert!(tcp && start.elapsed() < HEAD_START);
        assert!(open(addr, &client_id, pin, Transport::Udp, false).await.is_err(), "no UDP there");
    }
}
