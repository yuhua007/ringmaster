<div align="center">

# 🔔 RingMaster

**Cut the part you love. Make it your ringtone.**

A local-first, privacy-friendly ringtone maker for Android.
No ads · No tracking · No account · Works fully offline.

[Download APK](#) · [Screenshots](#) · [License](#-license) · [<kbd>☕ Support the dev</kbd>](https://ko-fi.com/ringmaster)

</div>

---

## ✨ Features

- 🎵 **Smart Cut** — on-device algorithm finds the chorus/highlight automatically (multiple candidates, tap to cycle)
- 🌊 **Waveform editor** — pinch to zoom, drag to pan, tap-to-preview, fine-tune by 0.1 s
- 🔊 **Preview everything** — loop preview, in-library playback, waveform thumbnails
- ⬇️ **Fade in / fade out** — adjustable 0–3 s
- 📲 **One-tap set** — phone / notification / alarm, even per-contact ringtones (Android-only)
- 🎬 **Video import** — cut ringtones straight out of mp4/mkv/webm
- 📁 **Broad format support** — mp3 / m4a / flac / wav / ogg / opus / amr / aiff + more
- 🛰️ **Trailing watermark avoidance** — auto-skips outro watermarks from downloaded videos
- 🙈 **Privacy** — everything happens on your device. No internet permission at all.

## 📥 Download

Grab the latest APK from the [Releases](../../releases) page, or build it yourself (see below).

## 🛠️ Build

```bash
# Requirements: Android Studio, JDK 17, Android SDK 35
git clone https://github.com/yuhua007/ringmaster.git
cd ringmaster/ringmaster-android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/
```

## 🧠 How Smart Cut works

See [docs/ALGORITHM.md](ringmaster-android/../docs/ALGORITHM.md) — RMS-energy based detection
with sliding-window smoothing, silence-boundary correction and repetition verification.
Unit-tested in `app/src/test/`.

## 🤝 Contributing

Issues and PRs are welcome — bug reports, format support, translations, UI polish.

## ☕ Support

If RingMaster saved you some time, a coffee is always appreciated:

[<kbd>☕ Ko-fi</kbd>](https://ko-fi.com/ringmaster)

## 📄 License

Non-commercial license — free for personal use, **commercial use requires permission**.
See [LICENSE](LICENSE) for details.
