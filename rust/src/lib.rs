//! NyaRemoteControl Android client core.
//!
//! The Kotlin app (`app/`) does the UI, MediaCodec decoding, audio and touch
//! input; this library speaks the protocol through the shared `nya-proto` /
//! `nya-transport` crates. JNI surface: `app.nya.remote.core.NativeCore`.
//!
//! * [`session`] – one connection: channels between the network and Kotlin
//! * [`net`] – handshake, pairing, the session loop, reconnecting
//! * [`gate`] – drop frames after a gap until the next keyframe
//! * [`options`] / [`events`] – JSON in and out

pub mod events;
pub mod files;
pub mod gate;
mod logcat;
pub mod net;
pub mod options;
pub mod session;
pub mod stats;
pub mod usb;

use std::path::PathBuf;
use std::time::Duration;

use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JLongArray, JShortArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use nya_proto::pb::{self, control_msg::Msg, input_msg::Ev};

use crate::options::{StartConfig, StreamOptions};
use crate::session::{Next, Session};

/// The session behind a handle from `start` (0 = none).
fn session<'a>(h: jlong) -> Option<&'a Session> {
    if h == 0 {
        None
    } else {
        // SAFETY: handles come from Box::into_raw in `start` and stay valid
        // until `free`, which Kotlin calls once no other call can be running.
        Some(unsafe { &*(h as *const Session) })
    }
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn timeout(ms: jint) -> Duration {
    Duration::from_millis(ms.max(0) as u64)
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_start<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    data_dir: JString<'l>,
    config: JString<'l>,
) -> jlong {
    logcat::init();
    let dir = string(&mut env, &data_dir).unwrap_or_default();
    let cfg = string(&mut env, &config).unwrap_or_default();
    let result = serde_json::from_str::<StartConfig>(&cfg)
        .map_err(anyhow::Error::from)
        .and_then(|cfg| Session::start(&PathBuf::from(dir), cfg));
    match result {
        Ok(s) => Box::into_raw(Box::new(s)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("{e:#}"));
            0
        }
    }
}

/// Next event as JSON, or null after `timeout_ms`.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_pollEvent<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    timeout_ms: jint,
) -> jstring {
    let Some(s) = session(h) else { return std::ptr::null_mut() };
    match s.poll_event(timeout(timeout_ms)) {
        Next::Item(e) => env.new_string(e.to_json()).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
        Next::Timeout | Next::Closed => std::ptr::null_mut(),
    }
}

/// Copy the next video frame's payload into `buf` (direct).
///
/// Returns the payload length and fills `meta` with
/// `[length, flags, frame_id, capture_ts_us, width, height, codec]`;
/// 0 on timeout; -1 when the session is gone; -2 when `buf` is too small
/// (`meta[0]` = needed size; the frame is kept for the next call).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_nextVideo<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    buf: JByteBuffer<'l>,
    meta: JLongArray<'l>,
    timeout_ms: jint,
) -> jint {
    let Some(s) = session(h) else { return -1 };
    let v = match s.next_video(timeout(timeout_ms)) {
        Next::Item(v) => v,
        Next::Timeout => return 0,
        Next::Closed => return -1,
    };
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else {
        return -1;
    };
    let payload = v.payload();
    let len = payload.len();
    let hd = v.header;
    let info = [len as i64, hd.flags as i64, hd.frame_id as i64, hd.capture_ts_us as i64, hd.width as i64, hd.height as i64, hd.codec as i64];
    if len > cap {
        let _ = env.set_long_array_region(&meta, 0, &info[..1]);
        s.put_back_video(v);
        return -2;
    }
    // SAFETY: `ptr` is the direct buffer's storage of at least `cap` >= len bytes.
    unsafe { std::ptr::copy_nonoverlapping(payload.as_ptr(), ptr, len) };
    let _ = env.set_long_array_region(&meta, 0, &info);
    len as jint
}

/// Next audio datagram (`u8 type | u32 seq | u64 ts | Opus`) into `buf`;
/// 0 on timeout, -1 when the session is gone.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_nextAudio<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    buf: JByteBuffer<'l>,
    timeout_ms: jint,
) -> jint {
    let Some(s) = session(h) else { return -1 };
    let packet = match s.next_audio(timeout(timeout_ms)) {
        Next::Item(p) => p,
        Next::Timeout => return 0,
        Next::Closed => return -1,
    };
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else {
        return -1;
    };
    let len = packet.len().min(cap);
    // SAFETY: copying at most `cap` bytes into the direct buffer.
    unsafe { std::ptr::copy_nonoverlapping(packet.as_ptr(), ptr, len) };
    len as jint
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_providePairCode<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    code: JString<'l>,
) {
    let code = string(&mut env, &code);
    if let Some(s) = session(h) {
        s.provide_pair_code(code);
    }
}

/// x, y: 0..65535 across the streamed display.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_mouseAbs(_env: JNIEnv, _cls: JClass, h: jlong, x: jint, y: jint) {
    if let Some(s) = session(h) {
        s.input(Ev::MouseAbs(pb::MouseAbs { x: x.clamp(0, 65535) as u32, y: y.clamp(0, 65535) as u32, slot: 0 }));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_mouseRel(_env: JNIEnv, _cls: JClass, h: jlong, dx: jint, dy: jint) {
    if let Some(s) = session(h) {
        s.input(Ev::MouseRel(pb::MouseRel { dx, dy }));
    }
}

/// button: 1 left, 2 right, 3 middle (pb::MouseButton).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_mouseButton(_env: JNIEnv, _cls: JClass, h: jlong, button: jint, down: jboolean) {
    if let Some(s) = session(h) {
        s.input(Ev::MouseButton(pb::MouseButtonEv { button, down: down == JNI_TRUE }));
    }
}

/// Units of 1/120 notch; dy > 0 scrolls up, dx > 0 right (Windows convention).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_wheel(_env: JNIEnv, _cls: JClass, h: jlong, dx: jint, dy: jint) {
    if let Some(s) = session(h) {
        s.input(Ev::Wheel(pb::Wheel { dx, dy }));
    }
}

/// PC/AT set-1 scancode; `extended` = E0 prefix.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_key(
    _env: JNIEnv,
    _cls: JClass,
    h: jlong,
    scancode: jint,
    extended: jboolean,
    down: jboolean,
) {
    if let Some(s) = session(h) {
        s.input(Ev::Key(pb::Key { scancode: scancode as u32, extended: extended == JNI_TRUE, down: down == JNI_TRUE }));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_releaseAll(_env: JNIEnv, _cls: JClass, h: jlong) {
    if let Some(s) = session(h) {
        s.input(Ev::ReleaseAll(pb::ReleaseAll {}));
    }
}

/// New stream settings (JSON StreamOptions), e.g. the phone was rotated or
/// the resolution setting changed.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_updateStream<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    json: JString<'l>,
) {
    let json = string(&mut env, &json).unwrap_or_default();
    match serde_json::from_str::<StreamOptions>(&json) {
        Ok(o) => {
            if let Some(s) = session(h) {
                s.control(Msg::StartStream(o.to_start()));
            }
        }
        Err(e) => tracing::warn!("updateStream: {e}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_setMode(_env: JNIEnv, _cls: JClass, h: jlong, game: jboolean) {
    if let Some(s) = session(h) {
        let mode = if game == JNI_TRUE { pb::StreamMode::Game } else { pb::StreamMode::Office };
        s.control(Msg::SetMode(pb::SetMode { mode: mode as i32 }));
    }
}

/// Ctrl+Alt+Del (secure attention sequence).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_sendSas(_env: JNIEnv, _cls: JClass, h: jlong) {
    if let Some(s) = session(h) {
        s.control(Msg::SendSas(pb::SendSas {}));
    }
}

/// The decoder lost its state: wait for (and ask for) a keyframe.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_requestKeyframe(_env: JNIEnv, _cls: JClass, h: jlong) {
    if let Some(s) = session(h) {
        s.decoder_lost();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_takeControl(_env: JNIEnv, _cls: JClass, h: jlong, kick: jboolean) {
    if let Some(s) = session(h) {
        s.control(Msg::TakeControl(pb::TakeControl { kick: kick == JNI_TRUE }));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_clipboardText<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    text: JString<'l>,
) {
    let text = string(&mut env, &text).unwrap_or_default();
    if let Some(s) = session(h) {
        s.control(Msg::ClipboardText(pb::ClipboardText { text }));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_setDecodeStats(
    _env: JNIEnv,
    _cls: JClass,
    h: jlong,
    decode_ms: jfloat,
    dropped: jint,
) {
    if let Some(s) = session(h) {
        s.shared.stats.set_decode(decode_ms, dropped.max(0) as u32);
    }
}

/// Text typed on the phone (FEATURE_TEXT_INPUT): the host types it as Unicode.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_text<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, text: JString<'l>) {
    let text = string(&mut env, &text).unwrap_or_default();
    if let Some(s) = session(h) {
        if !text.is_empty() {
            s.input(Ev::Text(pb::TextInput { text }));
        }
    }
}

/// XInput state of pad `index` (0..3); see pb::Gamepad.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_gamepad(
    _env: JNIEnv,
    _cls: JClass,
    h: jlong,
    index: jint,
    connected: jboolean,
    buttons: jint,
    left_trigger: jint,
    right_trigger: jint,
    lx: jint,
    ly: jint,
    rx: jint,
    ry: jint,
) {
    if let Some(s) = session(h) {
        s.input(Ev::Gamepad(pb::Gamepad {
            index: index.clamp(0, 3) as u32,
            connected: connected == JNI_TRUE,
            buttons: buttons as u32 & 0xffff,
            left_trigger: left_trigger.clamp(0, 255) as u32,
            right_trigger: right_trigger.clamp(0, 255) as u32,
            lx: lx.clamp(-32768, 32767),
            ly: ly.clamp(-32768, 32767),
            rx: rx.clamp(-32768, 32767),
            ry: ry.clamp(-32768, 32767),
        }));
    }
}

/// A decoded audio packet: `samples` 16-bit values of interleaved stereo.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_audioPush<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    seq: jint,
    sender_us: jlong,
    pcm: JShortArray<'l>,
    samples: jint,
) -> jboolean {
    let Some(s) = session(h) else { return 0 };
    let mut buf = vec![0i16; samples.max(0) as usize & !1];
    if env.get_short_array_region(&pcm, 0, &mut buf).is_err() {
        return 0;
    }
    s.audio_push(seq as u32, sender_us as u64, &buf) as jboolean
}

/// Up to `max_frames` frames of interleaved f32 stereo into `out`; returns the frames written.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_audioPull<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    max_frames: jint,
    device_queued: jint,
    out: JFloatArray<'l>,
) -> jint {
    let Some(s) = session(h) else { return 0 };
    let cap = env.get_array_length(&out).unwrap_or(0).max(0) as usize / 2;
    let mut buf = Vec::with_capacity(cap * 2);
    let n = s.audio_pull((max_frames.max(0) as usize).min(cap), device_queued.max(0) as usize, &mut buf);
    if n > 0 && env.set_float_array_region(&out, 0, &buf[..n * 2]).is_err() {
        return 0;
    }
    n as jint
}

/// Download the files of a FileOffer (id as given in the event).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_requestFiles<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, id: JString<'l>) {
    let Some(id) = string(&mut env, &id).and_then(|i| i.parse::<u64>().ok()) else { return };
    if let Some(s) = session(h) {
        s.control(Msg::FileRequest(pb::FileRequest { transfer_id: id, purpose: pb::FilePurpose::Save as i32 }));
    }
}

/// Send files to the host. `json`: `[{"fd": 42, "name": "a.jpg", "size": 123}]`;
/// the core takes ownership of the file descriptors (detached by the app).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_sendFiles<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, json: JString<'l>) {
    #[derive(serde::Deserialize)]
    struct Picked {
        fd: i32,
        name: String,
        size: u64,
    }
    let json = string(&mut env, &json).unwrap_or_default();
    let picked: Vec<Picked> = match serde_json::from_str(&json) {
        Ok(p) => p,
        Err(e) => return tracing::warn!("sendFiles: {e}"),
    };
    let items: Vec<files::Upload> = picked
        .into_iter()
        .filter_map(|p| {
            let file = file_from_fd(p.fd)?;
            Some(files::Upload { file, name: nya_transport::files::sanitize_name(&p.name), size: p.size })
        })
        .collect();
    match session(h) {
        Some(s) if !items.is_empty() => s.send_files(items),
        _ => {}
    }
}

#[cfg(unix)]
fn file_from_fd(fd: i32) -> Option<std::fs::File> {
    use std::os::fd::FromRawFd;
    // SAFETY: the app detached this descriptor for us; we own and close it.
    (fd >= 0).then(|| unsafe { std::fs::File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn file_from_fd(_fd: i32) -> Option<std::fs::File> {
    None
}

/// One Opus packet from the phone's microphone (48 kHz stereo).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_mic<'l>(env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, opus: JByteArray<'l>) {
    let Some(s) = session(h) else { return };
    let Ok(data) = env.convert_byte_array(&opus) else { return };
    let seq = s.mic_seq.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let d = nya_proto::frame::AudioPacket { seq, capture_ts_us: nya_proto::now_us(), data }.encode_as(nya_proto::frame::datagram_type::MIC);
    s.cmd(session::NetCmd::Mic(d));
}

/// An image copied on the phone, as CF_DIB bytes.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_clipboardImage<'l>(env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, dib: JByteArray<'l>) {
    let Some(s) = session(h) else { return };
    if let Ok(d) = env.convert_byte_array(&dib) {
        s.cmd(session::NetCmd::SendImage(d));
    }
}

/// Files copied on the phone (copies in the app's cache, JSON array of paths), offered for pasting on the host.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_clipboardFiles<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, json: JString<'l>) {
    let json = string(&mut env, &json).unwrap_or_default();
    let paths: Vec<String> = serde_json::from_str(&json).unwrap_or_default();
    if let Some(s) = session(h) {
        s.cmd(session::NetCmd::OfferFiles(paths.into_iter().map(PathBuf::from).collect()));
    }
}

/// New list of shared folders: `[{"name","path","readOnly"}]`.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_setShares<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, json: JString<'l>) {
    let json = string(&mut env, &json).unwrap_or_default();
    match serde_json::from_str::<Vec<options::ShareConfig>>(&json) {
        Ok(list) => {
            if let Some(s) = session(h) {
                s.cmd(session::NetCmd::SetShares(options::shares(&list)));
            }
        }
        Err(e) => tracing::warn!("setShares: {e}"),
    }
}

/// Share a USB device (opened and all interfaces claimed by the app) with the
/// host: `fd` of its UsbDeviceConnection (stays owned by the app), its raw descriptors.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_usbShare<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    h: jlong,
    busid: JString<'l>,
    devnum: jint,
    fd: jint,
    descriptors: JByteArray<'l>,
    description: JString<'l>,
) -> jboolean {
    let busid = string(&mut env, &busid).unwrap_or_default();
    let description = string(&mut env, &description).unwrap_or_default();
    let raw = env.convert_byte_array(&descriptors).unwrap_or_default();
    let Some(s) = session(h) else { return 0 };
    let info = match usb::DeviceInfo::parse(&raw) {
        Ok(i) => i,
        Err(e) => {
            tracing::warn!("usb {busid}: {e:#}");
            return 0;
        }
    };
    let Some(backend) = usb_backend(fd) else { return 0 };
    s.shared.usb.add(usb::Device {
        busid: busid.clone(),
        devnum: devnum.max(1) as u32,
        info,
        backend,
        closed: std::sync::atomic::AtomicBool::new(false),
    });
    s.control(Msg::UsbAttach(pb::UsbAttach { busid, description }));
    JNI_TRUE
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn usb_backend(fd: i32) -> Option<std::sync::Arc<dyn usb::Backend>> {
    Some(std::sync::Arc::new(usb::devfs::DevFs::new(fd)))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn usb_backend(_fd: i32) -> Option<std::sync::Arc<dyn usb::Backend>> {
    None
}

/// Stop sharing a USB device (the app closes its connection afterwards).
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_usbUnshare<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, h: jlong, busid: JString<'l>) {
    let busid = string(&mut env, &busid).unwrap_or_default();
    if let Some(s) = session(h) {
        s.control(Msg::UsbDetach(pb::UsbDetach { busid: busid.clone() }));
        s.shared.usb.remove(&busid);
    }
}

/// Disconnect; a Disconnected event follows.

#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_stop(_env: JNIEnv, _cls: JClass, h: jlong) {
    if let Some(s) = session(h) {
        s.stop();
    }
}

/// Release the handle. No other call on it may run or follow.
#[no_mangle]
pub extern "system" fn Java_app_nya_remote_core_NativeCore_free(_env: JNIEnv, _cls: JClass, h: jlong) {
    if h != 0 {
        // SAFETY: see `session`; this is the matching Box::from_raw.
        drop(unsafe { Box::from_raw(h as *mut Session) });
    }
}
