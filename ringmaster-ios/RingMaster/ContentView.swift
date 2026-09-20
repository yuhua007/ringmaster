import AVKit
import SwiftUI
import UniformTypeIdentifiers

/// Phase 0 演示界面：选歌 → 波形选段 → 导出 .m4r → 分享（存储到文件/库乐队）。
struct ContentView: View {
    @State private var inputURL: URL?
    @State private var waveform: WaveformExtractor.Waveform?
    @State private var selStart: TimeInterval = 0
    @State private var selEnd: TimeInterval = 0
    @State private var busy = false
    @State private var exportedURL: URL?
    @State private var showShare = false
    @State private var log = "Ready. 选择音频开始验证 Spike②④"
    @State private var player: AVPlayer?
    @State private var position: TimeInterval = 0
    @State private var playTimer: Timer?
    @State private var showPicker = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("RingMaster Phase 0 Spike")
                        .font(.headline)

                    Button("选择音频文件") { pickAudio() }
                        .buttonStyle(.borderedProminent)

                    if let url = inputURL {
                        Text(url.lastPathComponent).font(.footnote).foregroundStyle(.secondary)
                    }

                    Button {
                        extractWaveform()
                    } label: { Label("提取波形", systemImage: "waveform") }
                        .disabled(inputURL == nil || busy)

                    if waveform != nil {
                        WaveformView(
                            amplitudes: waveform!.amplitudes,
                            duration: waveform!.duration,
                            selectionStart: $selStart,
                            selectionEnd: $selEnd,
                            position: position
                        )
                        .padding(.horizontal, 8)

                        HStack {
                            Stepper("起 \(String(format: "%.1f", selStart))s", value: $selStart, in: 0...max(0, selEnd - 0.5), step: 0.1)
                            Stepper("止 \(String(format: "%.1f", selEnd))s", value: $selEnd, in: min(selStart + 0.5, waveform!.duration)...waveform!.duration, step: 0.1)
                        }

                        Text("选区 \(String(format: "%.1f", selEnd - selStart)) 秒（铃声上限 40s）")
                            .font(.footnote)

                        Button { togglePreview() } label: {
                            Label(player == nil ? "试听选段" : "停止", systemImage: "play.fill")
                        }

                        Button {
                            exportRingtone()
                        } label: { Label("导出 .m4r 并分享", systemImage: "square.and.arrow.up") }
                            .buttonStyle(.borderedProminent)
                            .disabled(busy || waveform == nil)
                    }

                    if busy { ProgressView() }

                    Divider()
                    Text("运行日志").font(.subheadline.weight(.semibold))
                    Text(log).font(.caption2.monospaced()).frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding()
            }
            .navigationTitle("RingMaster")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showShare) {
                if let url = exportedURL { ShareSheet(items: [url]) }
            }
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [UTType.audio]) { result in
                if case .success(let url) = result {
                    let secured = url.startAccessingSecurityScopedResource()
                    inputURL = url
                    waveform = nil; exportedURL = nil
                    appendLog("[选择] \(url.lastPathComponent)")
                    _ = secured
                }
            }
        }
    }

    private func appendLog(_ s: String) { log = s + "\n———\n" + log }

    private func pickAudio() { showPicker = true }

    private func extractWaveform() {
        guard let url = inputURL else { return }
        busy = true
        appendLog("[Spike④] 提取波形中…")
        let t0 = Date()
        Task {
            do {
                let wf = try await WaveformExtractor.extract(url: url)
                await MainActor.run {
                    waveform = wf
                    selStart = wf.duration / 3
                    selEnd = min(wf.duration / 3 + 30, wf.duration)
                    appendLog("[Spike④] \(wf.amplitudes.count) 桶, \(String(format: "%.0f", wf.duration))s, 耗时 \(Int(-t0.timeIntervalSinceNow * 1000))ms")
                    busy = false
                }
            } catch {
                await MainActor.run { appendLog("[Spike④] 失败: \(error.localizedDescription)"); busy = false }
            }
        }
    }

    private func togglePreview() {
        if player != nil {
            player?.pause(); player = nil; playTimer?.invalidate(); position = selStart
            return
        }
        guard let url = inputURL else { return }
        let item = AVPlayerItem(url: url)
        item.forwardPlaybackEndTime = CMTime(seconds: selEnd, preferredTimescale: 600)
        let p = AVPlayer(playerItem: item)
        p.seek(to: CMTime(seconds: selStart, preferredTimescale: 600))
        p.play()
        player = p
        position = selStart
        playTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
            position = p.currentTime().seconds
            if p.currentTime().seconds >= selEnd - 0.05 || p.timeControlStatus == .paused {
                player = nil
            }
        }
    }

    private func exportRingtone() {
        guard let url = inputURL else { return }
        busy = true
        let len = selEnd - selStart
        appendLog("[Spike②] 导出 \(String(format: "%.1f", len))s …")
        Task {
            do {
                let out = try await RingtoneExporter.export(
                    sourceURL: url, start: selStart, duration: len, fadeIn: 0.5, fadeOut: 0.5)
                await MainActor.run {
                    exportedURL = out
                    showShare = true
                    appendLog("[Spike②] 导出成功: \(out.lastPathComponent)（分享面板选「存储到文件」）")
                    busy = false
                }
            } catch {
                await MainActor.run { appendLog("[Spike②] 失败: \(error.localizedDescription)"); busy = false }
            }
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
