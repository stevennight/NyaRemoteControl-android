//! File transfer (FEATURE_FILE_TRANSFER): uploads of files the user picked on
//! the phone, and downloads of files copied on the host (FileOffer ->
//! FileRequest -> files). Same wire format as the Windows client
//! (`nya_transport::files`): every file goes through the connection's
//! `FileLink`, over the TCP file channel once the host offered one
//! (FEATURE_TCP_FILES), else as FILE streams on QUIC.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nya_proto::pb;
use nya_transport::files::{self, FileLink};
use nya_transport::quinn::RecvStream;
use tokio::io::AsyncRead;

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
        self.set(self.done + n);
    }

    /// Bytes done so far (of the whole batch).
    fn set(&mut self, done: u64) {
        self.done = done;
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

/// Send the picked files as one batch; the host answers with a FileResult.
pub async fn upload(link: FileLink, items: Vec<Upload>, sh: Arc<Shared>) {
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
        // The app already opened it (a content URI has no path): hand that file over.
        let file = Mutex::new(Some(u.file));
        let open: files::Opener = Arc::new(move |_| file.lock().unwrap().take().ok_or_else(|| std::io::Error::other("文件已经发送过")));
        if let Err(e) = link.send_file(h, Path::new(&u.name), Some(&open), |n| prog.add(n)).await {
            prog.finish(Err(format!("发送 {} 失败：{e:#}", u.name)));
            return;
        }
    }
    prog.name = format!("{count} 个文件");
    // Sent; the host's FileResult says where it saved them.
    sh.event(prog.event(false, true, "等待电脑确认…".into()));
}

/// The host pastes files copied on the phone (FEATURE_CLIPBOARD_FILES).
pub async fn send_clipboard(link: FileLink, id: u64, items: Vec<files::Item>, sh: Arc<Shared>) {
    let total = items.iter().filter(|i| !i.is_dir).map(|i| i.size).sum();
    let mut prog = Progress::new(&sh, id, true, total);
    let res = nya_transport::clipfiles::send_items_with(&link, id, &items, pb::FilePurpose::Clipboard, None, |name, n| {
        if prog.name != name {
            prog.name = name.to_owned();
        }
        prog.add(n);
    })
    .await;
    match res {
        Ok(()) => prog.finish(Ok(format!("已粘贴到电脑（{} 项）", items.len()))),
        Err(e) => {
            let msg = format!("复制到电脑失败：{e:#}");
            // The paste on the host waits for these files; a cancel it already knows of.
            if !link.cancels().is_cancelled(id) {
                let r = pb::FileResult { transfer_id: id, ok: false, message: msg.clone(), saved_to: String::new() };
                sh.control(pb::control_msg::Msg::FileResult(r));
            }
            prog.finish(Err(msg));
        }
    }
}

/// An image copied on the phone, as CF_DIB bytes.
pub async fn send_image(link: FileLink, dib: Vec<u8>) {
    let h = pb::FileHeader {
        transfer_id: new_id(),
        name: "clipboard.dib".into(),
        size: dib.len() as u64,
        purpose: pb::FilePurpose::ClipboardImage as i32,
        index: 0,
        count: 1,
        path: String::new(),
    };
    if let Err(e) = link.send_bytes(h, &dib).await {
        tracing::debug!("clipboard image: {e:#}");
    }
}

/// Downloads in progress: offers we requested, and what arrived so far.
#[derive(Default)]
pub struct Downloads {
    /// offer id -> total bytes (from the FileOffer).
    offers: Mutex<HashMap<u64, u64>>,
    /// offer id -> (received files, bytes so far of the whole batch). Files
    /// of a batch may arrive at the same time: one count for all of them.
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

    /// The batch failed, or is requested again: it starts over.
    pub fn forget(&self, id: u64) {
        self.batches.lock().unwrap().remove(&id);
    }

    /// `n` more bytes of batch `id`; returns the batch's bytes so far.
    fn add(&self, id: u64, n: u64) -> u64 {
        let mut b = self.batches.lock().unwrap();
        let e = b.entry(id).or_default();
        e.1 += n;
        e.1
    }
}

/// What files from the host may carry (negotiated features) and where they go.
#[derive(Clone)]
pub struct Receive {
    pub dir: Option<PathBuf>,
    pub save: bool,
    pub images: bool,
    pub print: bool,
    /// Transfers cancelled on either side (the connection's `FileLink`).
    pub cancels: Arc<files::Cancels>,
}

/// A FILE stream from the host (after the type varint).
pub async fn receive(mut r: RecvStream, sh: Arc<Shared>, dl: Arc<Downloads>, rx: Receive) {
    let h = match files::read_header(&mut r).await {
        Ok(h) => h,
        Err(e) => return tracing::warn!("file header: {e:#}"),
    };
    receive_body(h, &mut r, sh, dl, rx).await
}

/// A file from the host (QUIC FILE stream or the TCP file channel): a
/// requested download, an image copied on the host, or a print job.
/// Returning without reading it refuses it.
pub async fn receive_body<R: AsyncRead + Unpin>(h: pb::FileHeader, r: &mut R, sh: Arc<Shared>, dl: Arc<Downloads>, rx: Receive) {
    // Cancelled (here or by the host): reads fail, partial files are removed.
    let r = &mut files::Cancellable::new(r, rx.cancels.flag(h.transfer_id));
    let purpose = pb::FilePurpose::try_from(h.purpose).unwrap_or(pb::FilePurpose::Unspecified);
    let Some(dir) = rx.dir.clone() else { return };
    match purpose {
        pb::FilePurpose::ClipboardImage if rx.images => {
            match files::receive_to_vec(r, &h, files::MAX_IMAGE_BYTES).await {
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
            match files::receive_to_dir(r, &h, &dir.join("print"), |_| {}).await {
                Ok(p) => {
                    tracing::info!("print job from the host: {}", p.display());
                    sh.event(Event::PrintJob { path: p.to_string_lossy().into_owned() });
                }
                Err(e) => tracing::warn!("receiving print job {}: {e:#}", h.name),
            }
            return;
        }
        pb::FilePurpose::Save | pb::FilePurpose::Unspecified if rx.save => {}
        _ => return,
    }
    // One folder per batch: the app moves its files away when it is complete.
    let id = h.transfer_id;
    let dir = dir.join(format!("{id:016x}"));
    let total = dl.offers.lock().unwrap().get(&id).copied().unwrap_or(0);
    let mut prog = Progress::new(&sh, id, false, total);
    prog.name = h.name.clone();
    prog.done = dl.add(id, 0);
    let res = files::receive_to_dir(r, &h, &dir, |n| prog.set(dl.add(id, n))).await;
    match res {
        Ok(p) => {
            // Complete once every file is in (they may finish out of order).
            let done = {
                let mut b = dl.batches.lock().unwrap();
                let e = b.entry(id).or_default();
                e.0.push(p);
                if e.0.len() as u32 >= h.count {
                    b.remove(&id).map(|x| x.0)
                } else {
                    None
                }
            };
            if let Some(paths) = done {
                prog.finish(Ok(format!("已接收 {} 个文件", paths.len())));
                sh.event(Event::FilesReceived {
                    id: id.to_string(),
                    paths: paths.iter().map(|p| p.to_string_lossy().into_owned()).collect(),
                });
            }
        }
        Err(e) => {
            dl.forget(id);
            prog.finish(Err(format!("接收 {} 失败：{e:#}", h.name)));
        }
    }
}
