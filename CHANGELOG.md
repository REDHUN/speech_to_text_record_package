## 0.1.0

- Initial release of `speechtotextrecord`.
- Android-only plugin for single-capture WAV audio recording with simultaneous platform speech-to-text recognition.
- Streaming real-time events for transcript (partial & committed), audio levels, issues, state changes, and final results.
- Bounded queue and nonblocking audio pipe preventing speech engine slowdowns from blocking disk audio capture.
- Safe lifecycle handling (pauses/stops on backgrounding to avoid audio leaks).
