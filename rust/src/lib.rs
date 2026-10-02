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
pub mod gate;
mod logcat;
pub mod net;
pub mod options;
pub mod session;
pub mod stats;

use std::path::PathBuf;
use std::time::Duration;

use jni::objects::{JByteBuffer, JClass, JLongArray, JString};
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
