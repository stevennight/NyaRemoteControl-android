//! `tracing` output to logcat (tag "nya-core"); stderr elsewhere (tests).

use std::io::Write;
use std::sync::Once;

static INIT: Once = Once::new();

pub fn init() {
    INIT.call_once(|| {
        let filter = tracing_subscriber::filter::LevelFilter::INFO;
        let _ = tracing_subscriber::fmt()
            .with_max_level(filter)
            .with_ansi(false)
            .without_time()
            .with_target(false)
            .with_writer(|| LineWriter(Vec::new()))
            .try_init();
        std::panic::set_hook(Box::new(|info| {
            tracing::error!("panic: {info}");
        }));
    });
}

/// Collects one formatted event and logs it when dropped.
struct LineWriter(Vec<u8>);

impl Write for LineWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Drop for LineWriter {
    fn drop(&mut self) {
        while self.0.last().is_some_and(|b| *b == b'\n') {
            self.0.pop();
        }
        if !self.0.is_empty() {
            write_line(&self.0);
        }
    }
}

#[cfg(target_os = "android")]
fn write_line(line: &[u8]) {
    use android_log_sys::{LogPriority, __android_log_write};
    let level = if line.starts_with(b"ERROR") {
        LogPriority::ERROR
    } else if line.starts_with(b"WARN") || line.starts_with(b" WARN") {
        LogPriority::WARN
    } else {
        LogPriority::INFO
    };
    let mut text: Vec<u8> = line.iter().copied().filter(|b| *b != 0).collect();
    text.push(0);
    unsafe {
        __android_log_write(level as i32, c"nya-core".as_ptr(), text.as_ptr() as *const _);
    }
}

#[cfg(not(target_os = "android"))]
fn write_line(line: &[u8]) {
    let _ = std::io::stderr().write_all(line);
    let _ = std::io::stderr().write_all(b"\n");
}
