//! USB passthrough (FEATURE_USB_REDIRECT), phone side.
//!
//! The Windows client shares devices with usbipd-win; here the core itself is
//! the USB/IP server. The host's usbip-win2 connects to "port 3240" through a
//! TUNNEL stream, lists / imports a device by bus id, then sends URBs
//! (USBIP_CMD_SUBMIT / UNLINK), which go to the device through the file
//! descriptor of the app's `UsbDeviceConnection` (Linux usbdevfs: async URBs
//! submitted, reaped and discarded with ioctls). The app claims every
//! interface (detaching Android's drivers) before sharing.
//!
//! Wire format: USB/IP protocol version 1.1.1, big endian.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{bail, Context, Result};
use nya_proto::pb;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

use crate::session::Shared;

pub const USBIP_PORT: u64 = 3240;
const VERSION: u16 = 0x0111;
const OP_REQ_DEVLIST: u16 = 0x8005;
const OP_REP_DEVLIST: u16 = 0x0005;
const OP_REQ_IMPORT: u16 = 0x8003;
const OP_REP_IMPORT: u16 = 0x0003;
const CMD_SUBMIT: u32 = 1;
const CMD_UNLINK: u32 = 2;
const RET_SUBMIT: u32 = 3;
const RET_UNLINK: u32 = 4;
const ECONNRESET: i32 = 104;
const EINVAL: i32 = 22;
/// Linux URB flags usbip passes through and usbdevfs understands.
const URB_SHORT_NOT_OK: u32 = 0x0001;
const URB_ZERO_PACKET: u32 = 0x0040;
/// Largest transfer accepted from the host.
const MAX_TRANSFER: usize = 16 << 20;

/// Endpoint transfer types (bmAttributes & 3).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EpType {
    Control,
    Iso,
    Bulk,
    Interrupt,
}

/// What the descriptors say about a device.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeviceInfo {
    pub vendor: u16,
    pub product: u16,
    pub bcd_device: u16,
    pub class: u8,
    pub subclass: u8,
    pub protocol: u8,
    pub configuration: u8,
    pub num_configurations: u8,
    /// (class, subclass, protocol) of each interface (alternate setting 0).
    pub interfaces: Vec<(u8, u8, u8)>,
    /// Endpoint address -> type.
    pub endpoints: Vec<(u8, u8)>,
}

impl DeviceInfo {
    /// Parse the raw descriptors (`UsbDeviceConnection.getRawDescriptors()`:
    /// device descriptor followed by the active configuration).
    pub fn parse(raw: &[u8]) -> Result<Self> {
        if raw.len() < 18 || raw[1] != 1 {
            bail!("no device descriptor");
        }
        let le = |o: usize| u16::from_le_bytes([raw[o], raw[o + 1]]);
        let mut d = DeviceInfo {
            class: raw[4],
            subclass: raw[5],
            protocol: raw[6],
            vendor: le(8),
            product: le(10),
            bcd_device: le(12),
            num_configurations: raw[17],
            ..Default::default()
        };
        let mut o = raw[0] as usize;
        let mut alt0 = false;
        while o + 2 <= raw.len() {
            let len = raw[o] as usize;
            if len < 2 || o + len > raw.len() {
                break;
            }
            match raw[o + 1] {
                2 if len >= 9 => d.configuration = raw[o + 5],
                4 if len >= 9 => {
                    alt0 = raw[o + 3] == 0;
                    if alt0 {
                        d.interfaces.push((raw[o + 5], raw[o + 6], raw[o + 7]));
                    }
                }
                5 if len >= 7 => {
                    let addr = raw[o + 2];
                    if !d.endpoints.iter().any(|(a, _)| *a == addr) {
                        d.endpoints.push((addr, raw[o + 3] & 3));
                    }
                    let _ = alt0;
                }
                _ => {}
            }
            o += len;
        }
        Ok(d)
    }

    pub fn ep_type(&self, addr: u8) -> EpType {
        if addr & 0x7f == 0 {
            return EpType::Control;
        }
        match self.endpoints.iter().find(|(a, _)| *a == addr).map(|e| e.1).unwrap_or(2) {
            0 => EpType::Control,
            1 => EpType::Iso,
            3 => EpType::Interrupt,
            _ => EpType::Bulk,
        }
    }
}

/// A finished URB.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Completion {
    pub seq: u32,
    /// 0 or a negative errno.
    pub status: i32,
    pub actual: usize,
    /// IN data (`actual` bytes).
    pub data: Vec<u8>,
}

/// One URB to run.
#[derive(Debug, Clone)]
pub struct Submit {
    pub seq: u32,
    pub ep: u8,
    pub kind: EpType,
    pub dir_in: bool,
    pub flags: u32,
    /// Control: the setup packet; empty otherwise.
    pub setup: [u8; 8],
    /// OUT data, or the IN buffer length.
    pub out: Vec<u8>,
    pub in_len: usize,
}

/// Where URBs go: the real device (usbdevfs) or a test double.
pub trait Backend: Send + Sync {
    fn submit(&self, s: Submit) -> std::result::Result<(), i32>;
    /// Cancel a pending URB; it still completes (and is reaped) afterwards.
    fn discard(&self, seq: u32);
    /// Finished URBs, waiting at most `timeout`.
    fn reap(&self, timeout: Duration) -> std::result::Result<Vec<Completion>, i32>;
    fn set_configuration(&self, value: u8) -> std::result::Result<(), i32>;
    fn set_interface(&self, interface: u8, alt: u8) -> std::result::Result<(), i32>;
    fn clear_halt(&self, ep: u8) -> std::result::Result<(), i32>;
    /// usb_device_speed (1 low, 2 full, 3 high, 5 super, 6 super+).
    fn speed(&self) -> u32;
}

/// A device the user shared.
pub struct Device {
    pub busid: String,
    pub devnum: u32,
    pub info: DeviceInfo,
    pub backend: Arc<dyn Backend>,
    /// Set when the user stops sharing: running tunnels end.
    pub closed: AtomicBool,
}

/// Devices shared by this phone, by bus id.
#[derive(Default)]
pub struct Devices(pub Mutex<HashMap<String, Arc<Device>>>);

impl Devices {
    pub fn add(&self, d: Device) {
        self.0.lock().unwrap().insert(d.busid.clone(), Arc::new(d));
    }

    pub fn remove(&self, busid: &str) {
        if let Some(d) = self.0.lock().unwrap().remove(busid) {
            d.closed.store(true, Ordering::SeqCst);
        }
    }

    fn get(&self, busid: &str) -> Option<Arc<Device>> {
        self.0.lock().unwrap().get(busid).cloned()
    }

    fn list(&self) -> Vec<Arc<Device>> {
        self.0.lock().unwrap().values().cloned().collect()
    }
}

/// Host report about a device (attach result, detach).
pub fn status(_sh: &Shared, u: &pb::UsbStatus) {
    tracing::info!("usb {}: attached={} {}", u.busid, u.attached, u.message);
}

// ------------------------------------------------------------------ wire

fn busid_bytes(busid: &str) -> [u8; 32] {
    let mut b = [0u8; 32];
    let s = busid.as_bytes();
    b[..s.len().min(31)].copy_from_slice(&s[..s.len().min(31)]);
    b
}

/// `usbip_usb_device` (312 bytes).
fn device_record(d: &Device, speed: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(312);
    let mut path = [0u8; 256];
    let p = format!("/nya/android/{}", d.busid);
    path[..p.len().min(255)].copy_from_slice(&p.as_bytes()[..p.len().min(255)]);
    v.extend_from_slice(&path);
    v.extend_from_slice(&busid_bytes(&d.busid));
    v.extend_from_slice(&1u32.to_be_bytes()); // busnum
    v.extend_from_slice(&d.devnum.to_be_bytes());
    v.extend_from_slice(&speed.to_be_bytes());
    v.extend_from_slice(&d.info.vendor.to_be_bytes());
    v.extend_from_slice(&d.info.product.to_be_bytes());
    v.extend_from_slice(&d.info.bcd_device.to_be_bytes());
    v.extend_from_slice(&[
        d.info.class,
        d.info.subclass,
        d.info.protocol,
        d.info.configuration,
        d.info.num_configurations,
        d.info.interfaces.len() as u8,
    ]);
    v
}

fn op_header(code: u16, status: u32) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(&VERSION.to_be_bytes());
    v.extend_from_slice(&code.to_be_bytes());
    v.extend_from_slice(&status.to_be_bytes());
    v
}

fn devlist(devs: &[Arc<Device>]) -> Vec<u8> {
    let mut v = op_header(OP_REP_DEVLIST, 0);
    v.extend_from_slice(&(devs.len() as u32).to_be_bytes());
    for d in devs {
        v.extend(device_record(d, d.backend.speed()));
        for (c, s, p) in &d.info.interfaces {
            v.extend_from_slice(&[*c, *s, *p, 0]);
        }
    }
    v
}

fn be32(b: &[u8], o: usize) -> u32 {
    u32::from_be_bytes(b[o..o + 4].try_into().unwrap())
}

fn ret_header(command: u32, seq: u32, devid: u32, dir: u32, ep: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(48);
    for x in [command, seq, devid, dir, ep] {
        v.extend_from_slice(&x.to_be_bytes());
    }
    v
}

fn ret_submit(c: &Completion, devid: u32, dir_in: bool) -> Vec<u8> {
    let mut v = ret_header(RET_SUBMIT, c.seq, devid, dir_in as u32, 0);
    v.extend_from_slice(&c.status.to_be_bytes());
    v.extend_from_slice(&(c.actual as i32).to_be_bytes());
    v.extend_from_slice(&0i32.to_be_bytes()); // start_frame
    v.extend_from_slice(&0i32.to_be_bytes()); // number_of_packets
    v.extend_from_slice(&0i32.to_be_bytes()); // error_count
    v.extend_from_slice(&[0u8; 8]);
    if dir_in {
        v.extend_from_slice(&c.data[..c.actual.min(c.data.len())]);
    }
    v
}

fn ret_unlink(seq: u32, devid: u32, status: i32) -> Vec<u8> {
    let mut v = ret_header(RET_UNLINK, seq, devid, 0, 0);
    v.extend_from_slice(&status.to_be_bytes());
    v.extend_from_slice(&[0u8; 24]);
    v
}

// ------------------------------------------------------------------ server

/// A TUNNEL stream from the host to `port`: serve USB/IP on it.
pub async fn tunnel(
    send: nya_transport::quinn::SendStream,
    recv: nya_transport::quinn::RecvStream,
    port: u64,
    sh: &Shared,
) -> Result<()> {
    if port != USBIP_PORT {
        bail!("tunnel to unexpected port {port}");
    }
    serve(recv, send, &sh.usb).await
}

/// Serve one USB/IP connection (list devices, or import one and run its URBs).
pub async fn serve<R, W>(mut r: R, w: W, devices: &Devices) -> Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let writer = tokio::spawn(async move {
        let mut w = w;
        while let Some(b) = rx.recv().await {
            if w.write_all(&b).await.is_err() {
                break;
            }
        }
        let _ = w.shutdown().await;
    });
    let res = async {
        loop {
            let mut h = [0u8; 8];
            if r.read_exact(&mut h).await.is_err() {
                return Ok(());
            }
            let code = u16::from_be_bytes([h[2], h[3]]);
            match code {
                OP_REQ_DEVLIST => {
                    let _ = tx.send(devlist(&devices.list()));
                }
                OP_REQ_IMPORT => {
                    let mut busid = [0u8; 32];
                    r.read_exact(&mut busid).await?;
                    let id = String::from_utf8_lossy(&busid).trim_end_matches('\0').to_string();
                    let Some(dev) = devices.get(&id) else {
                        tracing::warn!("usb: host asked for unknown device {id}");
                        let _ = tx.send(op_header(OP_REP_IMPORT, 1));
                        continue;
                    };
                    let mut v = op_header(OP_REP_IMPORT, 0);
                    v.extend(device_record(&dev, dev.backend.speed()));
                    let _ = tx.send(v);
                    tracing::info!("usb: host imported {id}");
                    return urbs(&mut r, dev, tx.clone()).await;
                }
                other => bail!("unknown USB/IP operation {other:#06x}"),
            }
        }
    }
    .await;
    drop(tx);
    let _ = writer.await;
    res
}

/// Which URBs are in flight and which the host unlinked (shared with the reaper).
#[derive(Default)]
struct InFlight {
    /// seq -> IN direction
    pending: HashMap<u32, bool>,
    unlinked: HashSet<u32>,
}

async fn urbs<R: AsyncRead + Unpin>(r: &mut R, dev: Arc<Device>, tx: mpsc::UnboundedSender<Vec<u8>>) -> Result<()> {
    let devid = (1u32 << 16) | dev.devnum;
    let flight = Arc::new(Mutex::new(InFlight::default()));
    let stop = Arc::new(AtomicBool::new(false));
    // Completions are reaped on a thread: the ioctls block.
    let reaper = {
        let (dev, flight, stop, tx) = (dev.clone(), flight.clone(), stop.clone(), tx.clone());
        std::thread::Builder::new().name("nya-usb-reap".into()).spawn(move || {
            while !stop.load(Ordering::Relaxed) || !flight.lock().unwrap().pending.is_empty() {
                let done = match dev.backend.reap(Duration::from_millis(100)) {
                    Ok(d) => d,
                    Err(e) => {
                        tracing::warn!("usb reap: errno {e}");
                        break;
                    }
                };
                for c in done {
                    let mut f = flight.lock().unwrap();
                    let Some(dir_in) = f.pending.remove(&c.seq) else { continue };
                    if f.unlinked.remove(&c.seq) {
                        continue; // answered by RET_UNLINK
                    }
                    let _ = tx.send(ret_submit(&c, devid, dir_in));
                }
                if stop.load(Ordering::Relaxed) {
                    // Cancel what is left so the loop can end.
                    let left: Vec<u32> = flight.lock().unwrap().pending.keys().copied().collect();
                    left.into_iter().for_each(|s| dev.backend.discard(s));
                }
            }
        })?
    };
    let res = async {
        loop {
            if dev.closed.load(Ordering::Relaxed) {
                return Ok(());
            }
            let mut h = [0u8; 48];
            if r.read_exact(&mut h).await.is_err() {
                return Ok(());
            }
            let (command, seq, dir, ep) = (be32(&h, 0), be32(&h, 4), be32(&h, 12), be32(&h, 16));
            match command {
                CMD_SUBMIT => {
                    let flags = be32(&h, 20);
                    let len = be32(&h, 24) as i32;
                    let packets = be32(&h, 32) as i32;
                    let mut setup = [0u8; 8];
                    setup.copy_from_slice(&h[40..48]);
                    let len = len.max(0) as usize;
                    if len > MAX_TRANSFER {
                        bail!("transfer of {len} bytes");
                    }
                    let dir_in = dir == 1;
                    let mut out = Vec::new();
                    if !dir_in && len > 0 {
                        out = vec![0u8; len];
                        r.read_exact(&mut out).await?;
                    }
                    if packets > 0 && packets != -1 {
                        // Isochronous descriptors follow; not supported.
                        let mut skip = vec![0u8; packets as usize * 16];
                        r.read_exact(&mut skip).await?;
                        let c = Completion { seq, status: -EINVAL, actual: 0, data: Vec::new() };
                        let _ = tx.send(ret_submit(&c, devid, dir_in));
                        continue;
                    }
                    let addr = (ep as u8 & 0x0f) | if dir_in { 0x80 } else { 0 };
                    let kind = dev.info.ep_type(addr);
                    if kind == EpType::Control {
                        if let Some(c) = special_control(&dev, seq, &setup) {
                            let _ = tx.send(ret_submit(&c, devid, dir_in));
                            continue;
                        }
                    }
                    let s = Submit {
                        seq,
                        ep: if kind == EpType::Control { 0 } else { addr },
                        kind,
                        dir_in,
                        flags: flags & (URB_SHORT_NOT_OK | URB_ZERO_PACKET),
                        setup,
                        out,
                        in_len: if dir_in { len } else { 0 },
                    };
                    flight.lock().unwrap().pending.insert(seq, dir_in);
                    if let Err(e) = dev.backend.submit(s) {
                        flight.lock().unwrap().pending.remove(&seq);
                        let c = Completion { seq, status: -e.abs(), actual: 0, data: Vec::new() };
                        let _ = tx.send(ret_submit(&c, devid, dir_in));
                    }
                }
                CMD_UNLINK => {
                    let victim = be32(&h, 20);
                    let mut f = flight.lock().unwrap();
                    let status = if f.pending.contains_key(&victim) {
                        f.unlinked.insert(victim);
                        drop(f);
                        dev.backend.discard(victim);
                        -ECONNRESET
                    } else {
                        0 // already answered
                    };
                    let _ = tx.send(ret_unlink(seq, devid, status));
                }
                other => bail!("unknown USB/IP command {other}"),
            }
        }
    }
    .await;
    stop.store(true, Ordering::Relaxed);
    let _ = tokio::task::spawn_blocking(move || reaper.join()).await;
    tracing::info!("usb: {} released by the host", dev.busid);
    res.context("usb urbs")
}

/// Control requests usbdevfs wants as ioctls (configuration, interface,
/// clearing a halt) answered here; None for everything else.
fn special_control(dev: &Device, seq: u32, setup: &[u8; 8]) -> Option<Completion> {
    let (req_type, req) = (setup[0], setup[1]);
    let value = u16::from_le_bytes([setup[2], setup[3]]);
    let index = u16::from_le_bytes([setup[4], setup[5]]);
    let r = match (req_type, req) {
        // SET_CONFIGURATION: usually the one already active.
        (0x00, 0x09) => {
            if value as u8 == dev.info.configuration {
                Ok(())
            } else {
                dev.backend.set_configuration(value as u8)
            }
        }
        // SET_INTERFACE
        (0x01, 0x0b) => dev.backend.set_interface(index as u8, value as u8),
        // CLEAR_FEATURE(ENDPOINT_HALT)
        (0x02, 0x01) if value == 0 => dev.backend.clear_halt(index as u8),
        _ => return None,
    };
    let status = match r {
        Ok(()) => 0,
        Err(e) => -e.abs(),
    };
    Some(Completion { seq, status, actual: 0, data: Vec::new() })
}

// ------------------------------------------------------------------ usbdevfs

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod devfs {
    //! The real device: async URBs on the usbdevfs file descriptor.

    use super::*;
    use std::os::raw::{c_int, c_uint, c_void};

    #[repr(C)]
    struct UsbdevfsUrb {
        kind: u8,
        endpoint: u8,
        status: c_int,
        flags: c_uint,
        buffer: *mut c_void,
        buffer_length: c_int,
        actual_length: c_int,
        start_frame: c_int,
        number_of_packets: c_int,
        error_count: c_int,
        signr: c_uint,
        usercontext: *mut c_void,
    }

    #[repr(C)]
    struct Urb {
        raw: UsbdevfsUrb,
        buf: Vec<u8>,
        ctrl: bool,
    }

    const fn ioc(dir: u64, nr: u64, size: u64) -> u64 {
        (dir << 30) | (size << 16) | ((b'U' as u64) << 8) | nr
    }
    const READ: u64 = 2;
    const WRITE: u64 = 1;
    const SUBMITURB: u64 = ioc(READ, 10, std::mem::size_of::<UsbdevfsUrb>() as u64);
    const DISCARDURB: u64 = ioc(0, 11, 0);
    const REAPURBNDELAY: u64 = ioc(WRITE, 13, std::mem::size_of::<*mut c_void>() as u64);
    const SETINTERFACE: u64 = ioc(READ, 4, 8);
    const SETCONFIGURATION: u64 = ioc(READ, 5, 4);
    const CLEAR_HALT: u64 = ioc(READ, 21, 4);
    const GET_SPEED: u64 = ioc(0, 31, 0);
    const TYPE_INTERRUPT: u8 = 1;
    const TYPE_CONTROL: u8 = 2;
    const TYPE_BULK: u8 = 3;

    fn errno() -> i32 {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(5)
    }

    /// URBs in flight (boxed: the kernel holds their address).
    struct Pending(HashMap<u32, Box<Urb>>);
    // SAFETY: the raw pointers point into the boxes themselves.
    unsafe impl Send for Pending {}

    pub struct DevFs {
        fd: c_int,
        pending: Mutex<Pending>,
    }

    impl DevFs {
        /// `fd` stays owned by the app's UsbDeviceConnection.
        pub fn new(fd: i32) -> Self {
            Self { fd, pending: Mutex::new(Pending(HashMap::new())) }
        }

        fn ioctl(&self, req: u64, arg: *mut c_void) -> std::result::Result<c_int, i32> {
            // SAFETY: requests and argument layouts follow linux/usbdevice_fs.h.
            let r = unsafe { libc::ioctl(self.fd, req as _, arg) };
            if r < 0 {
                Err(errno())
            } else {
                Ok(r)
            }
        }
    }

    impl Backend for DevFs {
        fn submit(&self, s: Submit) -> std::result::Result<(), i32> {
            let (kind, buf) = match s.kind {
                EpType::Control => {
                    let wlen = u16::from_le_bytes([s.setup[6], s.setup[7]]) as usize;
                    let mut b = Vec::with_capacity(8 + wlen);
                    b.extend_from_slice(&s.setup);
                    if s.dir_in {
                        b.resize(8 + wlen.max(s.in_len), 0);
                    } else {
                        b.extend_from_slice(&s.out);
                    }
                    (TYPE_CONTROL, b)
                }
                EpType::Interrupt => (TYPE_INTERRUPT, if s.dir_in { vec![0; s.in_len] } else { s.out }),
                EpType::Bulk => (TYPE_BULK, if s.dir_in { vec![0; s.in_len] } else { s.out }),
                EpType::Iso => return Err(EINVAL),
            };
            let mut urb = Box::new(Urb {
                raw: UsbdevfsUrb {
                    kind,
                    endpoint: s.ep,
                    status: 0,
                    flags: s.flags,
                    buffer: std::ptr::null_mut(),
                    buffer_length: buf.len() as c_int,
                    actual_length: 0,
                    start_frame: 0,
                    number_of_packets: 0,
                    error_count: 0,
                    signr: 0,
                    usercontext: s.seq as usize as *mut c_void,
                },
                buf,
                ctrl: kind == TYPE_CONTROL,
            });
            urb.raw.buffer = urb.buf.as_mut_ptr() as *mut c_void;
            let mut p = self.pending.lock().unwrap();
            self.ioctl(SUBMITURB, &mut urb.raw as *mut UsbdevfsUrb as *mut c_void)?;
            p.0.insert(s.seq, urb);
            Ok(())
        }

        fn discard(&self, seq: u32) {
            let mut p = self.pending.lock().unwrap();
            if let Some(u) = p.0.get_mut(&seq) {
                let _ = self.ioctl(DISCARDURB, &mut u.raw as *mut UsbdevfsUrb as *mut c_void);
            }
        }

        fn reap(&self, timeout: Duration) -> std::result::Result<Vec<Completion>, i32> {
            let mut pfd = libc::pollfd { fd: self.fd, events: libc::POLLOUT, revents: 0 };
            // SAFETY: one valid pollfd.
            let n = unsafe { libc::poll(&mut pfd, 1, timeout.as_millis() as c_int) };
            if n < 0 {
                let e = errno();
                return if e == libc::EINTR { Ok(Vec::new()) } else { Err(e) };
            }
            if pfd.revents & (libc::POLLERR | libc::POLLHUP) != 0 && self.pending.lock().unwrap().0.is_empty() {
                return Err(libc::ENODEV);
            }
            let mut out = Vec::new();
            loop {
                let mut ptr: *mut UsbdevfsUrb = std::ptr::null_mut();
                match self.ioctl(REAPURBNDELAY, &mut ptr as *mut *mut UsbdevfsUrb as *mut c_void) {
                    Ok(_) if !ptr.is_null() => {
                        // SAFETY: the kernel returns the address we submitted.
                        let seq = unsafe { (*ptr).usercontext as usize as u32 };
                        let Some(mut u) = self.pending.lock().unwrap().0.remove(&seq) else { continue };
                        let actual = u.raw.actual_length.max(0) as usize;
                        let data = if u.ctrl {
                            u.buf.drain(..8.min(u.buf.len()));
                            u.buf.truncate(actual);
                            std::mem::take(&mut u.buf)
                        } else {
                            u.buf.truncate(actual);
                            std::mem::take(&mut u.buf)
                        };
                        out.push(Completion { seq, status: u.raw.status, actual, data });
                    }
                    Ok(_) => break,
                    Err(e) if e == libc::EAGAIN => break,
                    Err(e) if e == libc::ENODEV => {
                        if out.is_empty() {
                            return Err(e);
                        }
                        break;
                    }
                    Err(_) => break,
                }
            }
            Ok(out)
        }

        fn set_configuration(&self, value: u8) -> std::result::Result<(), i32> {
            let mut v: c_uint = value as c_uint;
            self.ioctl(SETCONFIGURATION, &mut v as *mut c_uint as *mut c_void).map(|_| ())
        }

        fn set_interface(&self, interface: u8, alt: u8) -> std::result::Result<(), i32> {
            let mut v: [c_uint; 2] = [interface as c_uint, alt as c_uint];
            self.ioctl(SETINTERFACE, v.as_mut_ptr() as *mut c_void).map(|_| ())
        }

        fn clear_halt(&self, ep: u8) -> std::result::Result<(), i32> {
            let mut v: c_uint = ep as c_uint;
            self.ioctl(CLEAR_HALT, &mut v as *mut c_uint as *mut c_void).map(|_| ())
        }

        fn speed(&self) -> u32 {
            self.ioctl(GET_SPEED, std::ptr::null_mut()).map(|s| s as u32).unwrap_or(3)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Completes OUT transfers at once, echoes control/bulk IN with a pattern;
    /// interrupt IN stays pending until discarded.
    #[derive(Default)]
    struct Fake {
        done: Mutex<Vec<Completion>>,
        pending: Mutex<HashSet<u32>>,
        configs: Mutex<Vec<u8>>,
    }

    impl Backend for Fake {
        fn submit(&self, s: Submit) -> std::result::Result<(), i32> {
            if s.kind == EpType::Interrupt && s.dir_in {
                self.pending.lock().unwrap().insert(s.seq);
                return Ok(());
            }
            let (actual, data) = if s.dir_in { (s.in_len.min(4), vec![0xab; s.in_len.min(4)]) } else { (s.out.len(), Vec::new()) };
            self.done.lock().unwrap().push(Completion { seq: s.seq, status: 0, actual, data });
            Ok(())
        }
        fn discard(&self, seq: u32) {
            if self.pending.lock().unwrap().remove(&seq) {
                self.done.lock().unwrap().push(Completion { seq, status: -ECONNRESET, actual: 0, data: Vec::new() });
            }
        }
        fn reap(&self, timeout: Duration) -> std::result::Result<Vec<Completion>, i32> {
            let d = std::mem::take(&mut *self.done.lock().unwrap());
            if d.is_empty() {
                std::thread::sleep(timeout.min(Duration::from_millis(5)));
            }
            Ok(d)
        }
        fn set_configuration(&self, value: u8) -> std::result::Result<(), i32> {
            self.configs.lock().unwrap().push(value);
            Ok(())
        }
        fn set_interface(&self, _: u8, _: u8) -> std::result::Result<(), i32> {
            Ok(())
        }
        fn clear_halt(&self, _: u8) -> std::result::Result<(), i32> {
            Ok(())
        }
        fn speed(&self) -> u32 {
            3
        }
    }

    /// Device descriptor + a configuration with one interface, bulk IN/OUT and interrupt IN.
    fn raw_descriptors() -> Vec<u8> {
        let mut v = vec![18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 1, 2, 3, 1];
        v.extend([9, 2, 39, 0, 1, 1, 0, 0x80, 50]);
        v.extend([9, 4, 0, 0, 3, 8, 6, 80, 0]);
        v.extend([7, 5, 0x81, 2, 0x00, 0x02, 0]);
        v.extend([7, 5, 0x02, 2, 0x00, 0x02, 0]);
        v.extend([7, 5, 0x83, 3, 8, 0, 4]);
        v
    }

    fn submit(seq: u32, dir_in: bool, ep: u32, len: u32, setup: [u8; 8], data: &[u8]) -> Vec<u8> {
        let mut v = ret_header(CMD_SUBMIT, seq, 0x10002, dir_in as u32, ep);
        for x in [0u32, len, 0, 0, 0] {
            v.extend_from_slice(&x.to_be_bytes());
        }
        v.extend_from_slice(&setup);
        v.extend_from_slice(data);
        v
    }

    #[test]
    fn parses_descriptors() {
        let d = DeviceInfo::parse(&raw_descriptors()).unwrap();
        assert_eq!((d.vendor, d.product, d.bcd_device), (0x1234, 0x5678, 0x0100));
        assert_eq!(d.configuration, 1);
        assert_eq!(d.interfaces, vec![(8, 6, 80)]);
        assert_eq!(d.ep_type(0x81), EpType::Bulk);
        assert_eq!(d.ep_type(0x83), EpType::Interrupt);
        assert_eq!(d.ep_type(0x00), EpType::Control);
    }

    #[tokio::test]
    async fn devlist_import_submit_unlink() {
        let devices = Arc::new(Devices::default());
        let fake = Arc::new(Fake::default());
        devices.add(Device {
            busid: "1-2".into(),
            devnum: 2,
            info: DeviceInfo::parse(&raw_descriptors()).unwrap(),
            backend: fake.clone(),
            closed: AtomicBool::new(false),
        });
        let (client, server) = tokio::io::duplex(1 << 16);
        let (sr, sw) = tokio::io::split(server);
        let devs = devices.clone();
        let task = tokio::spawn(async move { serve(sr, sw, &devs).await });
        let (mut cr, mut cw) = tokio::io::split(client);

        // Device list.
        cw.write_all(&op_header(OP_REQ_DEVLIST, 0)).await.unwrap();
        let mut h = [0u8; 12];
        cr.read_exact(&mut h).await.unwrap();
        assert_eq!(u16::from_be_bytes([h[2], h[3]]), OP_REP_DEVLIST);
        assert_eq!(be32(&h, 8), 1);
        let mut rec = vec![0u8; 312 + 4];
        cr.read_exact(&mut rec).await.unwrap();
        assert_eq!(&rec[256..259], b"1-2");
        assert_eq!(u16::from_be_bytes([rec[300], rec[301]]), 0x1234);
        assert_eq!(rec[311], 1); // interfaces
        assert_eq!(&rec[312..315], &[8, 6, 80]);

        // Import.
        let mut req = op_header(OP_REQ_IMPORT, 0);
        req.extend_from_slice(&busid_bytes("1-2"));
        cw.write_all(&req).await.unwrap();
        let mut rep = vec![0u8; 8 + 312];
        cr.read_exact(&mut rep).await.unwrap();
        assert_eq!(be32(&rep, 4), 0, "import ok");

        // SET_CONFIGURATION(1) is answered without the device (already active).
        cw.write_all(&submit(1, false, 0, 0, [0x00, 0x09, 1, 0, 0, 0, 0, 0], &[])).await.unwrap();
        let mut r = [0u8; 48];
        cr.read_exact(&mut r).await.unwrap();
        assert_eq!((be32(&r, 0), be32(&r, 4), be32(&r, 20)), (RET_SUBMIT, 1, 0));
        assert!(fake.configs.lock().unwrap().is_empty());

        // Bulk OUT 3 bytes, then bulk IN 4 bytes.
        cw.write_all(&submit(2, false, 2, 3, [0; 8], b"abc")).await.unwrap();
        cr.read_exact(&mut r).await.unwrap();
        assert_eq!((be32(&r, 4), be32(&r, 24)), (2, 3));
        cw.write_all(&submit(3, true, 1, 64, [0; 8], &[])).await.unwrap();
        cr.read_exact(&mut r).await.unwrap();
        assert_eq!((be32(&r, 4), be32(&r, 24)), (3, 4));
        let mut data = [0u8; 4];
        cr.read_exact(&mut data).await.unwrap();
        assert_eq!(data, [0xab; 4]);

        // Interrupt IN waits; the host unlinks it: only RET_UNLINK comes back.
        cw.write_all(&submit(4, true, 3, 8, [0; 8], &[])).await.unwrap();
        let mut unlink = ret_header(CMD_UNLINK, 5, 0x10002, 0, 0);
        unlink.extend_from_slice(&4u32.to_be_bytes());
        unlink.extend_from_slice(&[0u8; 24]);
        cw.write_all(&unlink).await.unwrap();
        cr.read_exact(&mut r).await.unwrap();
        assert_eq!((be32(&r, 0), be32(&r, 4), be32(&r, 20) as i32), (RET_UNLINK, 5, -ECONNRESET));
        // Nothing else (no RET_SUBMIT for seq 4): the next answer is for seq 6.
        cw.write_all(&submit(6, false, 2, 1, [0; 8], b"z")).await.unwrap();
        cr.read_exact(&mut r).await.unwrap();
        assert_eq!((be32(&r, 0), be32(&r, 4)), (RET_SUBMIT, 6));

        drop(cw);
        drop(cr);
        task.await.unwrap().unwrap();
    }
}
