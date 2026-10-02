//! File transfer (FEATURE_FILE_TRANSFER): uploads of files the user picked on
//! the phone, and downloads of files copied on the host (FileOffer ->
//! FileRequest -> FILE streams). Same wire format as the Windows client
//! (`nya_transport::files`).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{bail, Result};
use nya_proto::frame::stream_type;
use nya_proto::framing::encode_varint;
use nya_proto::pb;
use nya_transport::files;
use nya_transport::quinn::{Connection, RecvStream};
use tokio::io::AsyncReadExt;

use crate::events::{Event, OfferedFile};
use crate::session::Shared;

/// A file the app opened for sending (from a content URI).
pub struct Upload {
    pub file: std::fs::File,
    pub name: String,
    pub size: u64,
}

pub fn new_id() -> u64 {
    static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    nya_proto::now_us() ^ (N.fetch_add(1, std::sync::atomic::Ordering::Relaxed) << 48)
}

/// Rate-limited progress events.
struct Progress<'a> {
    sh: &'a Shared,
    id: u64,
    upload: bool,
    name: String,
    done: u64,
    total: u64,
    last: Instant,
}

impl<'a> Progress<'a> {
    fn new(sh: &'a Shared, id: u64, upload: bool, total: u64) -> Self {
        Self { sh, id, upload, name: String::new(), done: 0, total, last: Instant::now() - Duration::from_secs(1) }
    }

    fn event(&self, finished: bool, ok: bool, message: String) -> Event {
        Event::Transfer {
            id: self.id.to_string(),
            upload: self.upload,
            name: self.name.clone(),
            done: self.done,
            total: self.total,
            finished,
            ok,
            message,
        }
    }

    fn add(&mut self, n: u64) {
        self.done += n;
        if self.last.elapsed() >= Duration::from_millis(200) {
            self.last = Instant::now();
            self.sh.event(self.event(false, true, String::new()));
        }
    }

    fn finish(&self, r: Result<String, String>) {
        let (ok, message) = match r {
            Ok(m) => (true, m),
            Err(m) => (false, m),
        };
        self.sh.event(self.event(true, ok, message));
    }
}

const CHUNK: usize = 256 * 1024;

/// Send the picked files as one batch; the host answers with a FileResult.
pub async fn upload(conn: Connection, items: Vec<Upload>, sh: Arc<Shared>) {
    let id = new_id();
    let total = items.iter().map(|u| u.size).sum();
    let mut prog = Progress::new(&sh, id, true, total);
    let count = items.len() as u32;
    for (i, u) in items.into_iter().enumerate() {
        prog.name = u.name.clone();
        let h = pb::FileHeader {
            transfer_id: id,
            name: u.name.clone(),
            size: u.size,
            purpose: pb::FilePurpose::Save as i32,
            index: i as u32,
            count,
            path: String::new(),
        };
        if let Err(e) = send_one(&conn, h, u.file, &mut prog).await {
            prog.finish(Err(format!("发送 {} 失败：{e:#}", u.name)));
            return;
        }
    }
    prog.name = format!("{count} 个文件");
    // Sent; the host's FileResult says where it saved them.
    sh.event(prog.event(false, true, "等待电脑确认…".into()));
}

async fn send_one(conn: &Connection, header: pb::FileHeader, file: std::fs::File, prog: &mut Progress<'_>) -> Result<()> {
    let mut file = tokio::fs::File::from_std(file);
    let mut s = conn.open_uni().await?;
    s.set_priority(-1)?; // below video, input and cursor
    let mut prelude = Vec::new();
    encode_varint(stream_type::FILE, &mut prelude);
    prelude.extend(nya_proto::framing::encode_msg(&header));
    s.write_all(&prelude).await?;
    let mut buf = vec![0u8; CHUNK];
    let mut left = header.size;
    while left > 0 {
        let n = file.read(&mut buf[..(left.min(CHUNK as u64) as usize)]).await?;
        if n == 0 {
            bail!("文件在发送过程中变小了");
        }
        s.write_all(&buf[..n]).await?;
        left -= n as u64;
        prog.add(n as u64);
    }
    s.finish()?;
    Ok(())
}

/// The host pastes files copied on the phone (FEATURE_CLIPBOARD_FILES).
pub async fn send_clipboard(conn: Connection, id: u64, items: Vec<files::Item>, sh: Arc<Shared>) {
    let total = items.iter().filter(|i| !i.is_dir).map(|i| i.size).sum();
    let mut prog = Progress::new(&sh, id, true, total);
    let res = nya_transport::clipfiles::send_items(&conn, id, &items, pb::FilePurpose::Clipboard, |name, n| {
        if prog.name != name {
            prog.name = name.to_owned();
        }
        prog.add(n);
    })
    .await;
    match res {
        Ok(()) => prog.finish(Ok(format!("已粘贴到电脑（{} 项）", items.len()))),
        Err(e) => prog.finish(Err(format!("复制到电脑失败：{e:#}"))),
    }
}

/// An image copied on the phone, as CF_DIB bytes.
pub async fn send_image(conn: Connection, dib: Vec<u8>) {
    let h = pb::FileHeader {
        transfer_id: new_id(),
        name: "clipboard.dib".into(),
        size: dib.len() as u64,
        purpose: pb::FilePurpose::ClipboardImage as i32,
        index: 0,
        count: 1,
        path: String::new(),
    };
    if let Err(e) = files::send_bytes(&conn, h, &dib).await {
        tracing::debug!("clipboard image: {e:#}");
    }
}

/// Downloads in progress: offers we requested, and what arrived so far.
#[derive(Default)]
pub struct Downloads {
    /// offer id -> total bytes (from the FileOffer).
    offers: Mutex<HashMap<u64, u64>>,
    /// offer id -> (received files, bytes so far).
    batches: Mutex<HashMap<u64, (Vec<PathBuf>, u64)>>,
}

impl Downloads {
    pub fn offered(&self, o: &pb::FileOffer) -> Event {
        let files: Vec<OfferedFile> = o
            .files
            .iter()
            .map(|f| OfferedFile { name: f.name.clone(), path: f.path.clone(), size: f.size, is_dir: f.is_dir })
            .collect();
        let total_bytes = o.files.iter().map(|f| f.size).sum();
        let mut offers = self.offers.lock().unwrap();
        if offers.len() > 32 {
            offers.clear();
        }
        offers.insert(o.transfer_id, total_bytes);
        Event::FileOffer { id: o.transfer_id.to_string(), files, total_bytes }
    }

    pub fn forget(&self, id: u64) {
        self.batches.lock().unwrap().remove(&id);
    }
}

/// What FILE streams from the host may carry (negotiated features) and where they go.
#[derive(Clone)]
pub struct Receive {
    pub dir: Option<PathBuf>,
    pub save: bool,
    pub images: bool,
    pub print: bool,
}

/// A FILE stream from the host (after the type varint): a requested download,
/// an image copied on the host, or a print job.
pub async fn receive(mut r: RecvStream, sh: Arc<Shared>, dl: Arc<Downloads>, rx: Receive) {
    let h = match files::read_header(&mut r).await {
        Ok(h) => h,
        Err(e) => return tracing::warn!("file header: {e:#}"),
    };
    let purpose = pb::FilePurpose::try_from(h.purpose).unwrap_or(pb::FilePurpose::Unspecified);
    let Some(dir) = rx.dir.clone() else {
        let _ = r.stop(0u32.into());
        return;
    };
    match purpose {
        pb::FilePurpose::ClipboardImage if rx.images => {
            match files::receive_to_vec(&mut r, &h, files::MAX_IMAGE_BYTES).await {
                Ok(dib) => {
                    let path = dir.join("clipboard.dib");
                    let write = async {
                        tokio::fs::create_dir_all(&dir).await?;
                        tokio::fs::write(&path, &dib).await
                    };
                    match write.await {
                        Ok(()) => sh.event(Event::ClipboardImage { path: path.to_string_lossy().into_owned() }),
                        Err(e) => tracing::warn!("clipboard image: {e}"),
                    }
                }
                Err(e) => tracing::debug!("clipboard image: {e:#}"),
            }
            return;
        }
        pb::FilePurpose::Print if rx.print => {
            match files::receive_to_dir(&mut r, &h, &dir.join("print"), |_| {}).await {
                Ok(p) => {
                    tracing::info!("print job from the host: {}", p.display());
                    sh.event(Event::PrintJob { path: p.to_string_lossy().into_owned() });
                }
                Err(e) => tracing::warn!("receiving print job {}: {e:#}", h.name),
            }
            return;
        }
        pb::FilePurpose::Save | pb::FilePurpose::Unspecified if rx.save => {}
        _ => {
            let _ = r.stop(0u32.into());
            return;
        }
    }
    // One folder per batch: the app moves its files away when it is complete.
    let dir = dir.join(format!("{:016x}", h.transfer_id));
    let total = dl.offers.lock().unwrap().get(&h.transfer_id).copied().unwrap_or(0);
    let already = dl.batches.lock().unwrap().get(&h.transfer_id).map(|b| b.1).unwrap_or(0);
    let mut prog = Progress::new(&sh, h.transfer_id, false, total);
    prog.name = h.name.clone();
    prog.done = already;
    let res = files::receive_to_dir(&mut r, &h, &dir, |n| prog.add(n)).await;
    match res {
        Ok(p) => {
            let done = {
                let mut b = dl.batches.lock().unwrap();
                let e = b.entry(h.transfer_id).or_default();
                e.0.push(p);
                e.1 += h.size;
                if h.index + 1 >= h.count {
                    b.remove(&h.transfer_id).map(|x| x.0)
                } else {
                    None
                }
            };
            if let Some(paths) = done {
                prog.finish(Ok(format!("已接收 {} 个文件", paths.len())));
                sh.event(Event::FilesReceived {
                    id: h.transfer_id.to_string(),
                    paths: paths.iter().map(|p| p.to_string_lossy().into_owned()).collect(),
                });
            }
        }
        Err(e) => {
            dl.forget(h.transfer_id);
            prog.finish(Err(format!("接收 {} 失败：{e:#}", h.name)));
        }
    }
}
