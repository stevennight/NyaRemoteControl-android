//! Video admission before the decoder: frames after a gap reference pictures
//! the decoder never got, so everything up to the next keyframe is dropped and
//! a keyframe is requested (rate-limited), as in the Windows client.

use std::time::{Duration, Instant};

use nya_proto::frame::VideoFrameHeader;

/// Don't ask for keyframes more often than this while waiting for one.
const KEYFRAME_RETRY: Duration = Duration::from_millis(500);

#[derive(Debug, PartialEq, Eq)]
pub enum Admit {
    Pass,
    /// Drop the frame; `request_keyframe` says whether to ask the host now.
    Drop { request_keyframe: bool },
}

#[derive(Default)]
pub struct Gate {
    /// (stream id, next frame id) expected.
    expect: Option<(u64, u64)>,
    need_key: bool,
    last_request: Option<Instant>,
}

impl Gate {
    pub fn new() -> Self {
        // Nothing decoded yet: start at a keyframe.
        Self { need_key: true, ..Default::default() }
    }

    pub fn admit(&mut self, stream_id: u64, h: &VideoFrameHeader, now: Instant) -> Admit {
        if let Some((s, next)) = self.expect {
            if (s != stream_id || h.frame_id != next) && !h.is_keyframe() {
                self.need_key = true;
            }
        }
        self.expect = Some((stream_id, h.frame_id + 1));
        if h.is_keyframe() {
            self.need_key = false;
            return Admit::Pass;
        }
        if self.need_key {
            return Admit::Drop { request_keyframe: self.request_due(now) };
        }
        Admit::Pass
    }

    /// The decoder lost its state (reset, surface recreated, queue overflow):
    /// wait for a keyframe. Returns whether to ask the host now.
    pub fn lost(&mut self, now: Instant) -> bool {
        self.need_key = true;
        self.request_due(now)
    }

    /// Reconnected: new streams, new numbering.
    pub fn reset(&mut self) {
        *self = Self::new();
    }

    fn request_due(&mut self, now: Instant) -> bool {
        if self.last_request.is_some_and(|t| now.duration_since(t) < KEYFRAME_RETRY) {
            return false;
        }
        self.last_request = Some(now);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nya_proto::frame::frame_flags::KEYFRAME;

    fn h(frame_id: u64, key: bool) -> VideoFrameHeader {
        VideoFrameHeader { frame_id, flags: if key { KEYFRAME } else { 0 }, ..Default::default() }
    }

    #[test]
    fn waits_for_first_keyframe_then_passes_in_order() {
        let t = Instant::now();
        let mut g = Gate::new();
        assert_eq!(g.admit(1, &h(5, false), t), Admit::Drop { request_keyframe: true });
        assert_eq!(g.admit(1, &h(6, false), t), Admit::Drop { request_keyframe: false });
        assert_eq!(g.admit(1, &h(7, true), t), Admit::Pass);
        assert_eq!(g.admit(1, &h(8, false), t), Admit::Pass);
    }

    #[test]
    fn gap_drops_until_keyframe() {
        let t = Instant::now();
        let mut g = Gate::new();
        assert_eq!(g.admit(1, &h(1, true), t), Admit::Pass);
        assert_eq!(g.admit(1, &h(3, false), t), Admit::Drop { request_keyframe: true });
        assert_eq!(g.admit(1, &h(4, false), t + Duration::from_millis(100)), Admit::Drop { request_keyframe: false });
        assert_eq!(g.admit(1, &h(5, false), t + Duration::from_millis(600)), Admit::Drop { request_keyframe: true });
        assert_eq!(g.admit(1, &h(6, true), t), Admit::Pass);
    }

    #[test]
    fn new_stream_starting_with_keyframe_is_fine() {
        let t = Instant::now();
        let mut g = Gate::new();
        g.admit(1, &h(1, true), t);
        assert_eq!(g.admit(2, &h(1, true), t), Admit::Pass);
        assert_eq!(g.admit(2, &h(2, false), t), Admit::Pass);
    }

    #[test]
    fn decoder_loss_waits_for_keyframe() {
        let t = Instant::now();
        let mut g = Gate::new();
        g.admit(1, &h(1, true), t);
        assert!(g.lost(t));
        assert_eq!(g.admit(1, &h(2, false), t), Admit::Drop { request_keyframe: false });
        assert_eq!(g.admit(1, &h(3, true), t), Admit::Pass);
    }
}
