import AVFoundation
import Foundation

/// 波形数据：解码 PCM 并降采样为振幅包络（与 Android 端 WaveformExtractor 逻辑对齐）。
final class WaveformExtractor {

    struct Waveform {
        let amplitudes: [Float]   // 0...1
        let duration: TimeInterval
    }

    enum ExtractError: LocalizedError {
        case noAudioTrack
        var errorDescription: String? { "文件中没有音轨" }
    }

    /// 提取整曲波形，桶数默认 2000。
    static func extract(url: URL, buckets: Int = 2000) async throws -> Waveform {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            throw ExtractError.noAudioTrack
        }
        let duration = try await asset.load(.duration)
        let totalSeconds = duration.seconds

        guard let reader = try? AVAssetReader(asset: asset) else {
            throw ExtractError.noAudioTrack
        }
        // 16bit PCM，解出后按桶聚合峰值
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
            ]
        )
        output.alwaysCopiesSampleData = false
        reader.add(output)
        reader.startReading()

        guard let desc = track.formatDescriptions.first else { throw ExtractError.noAudioTrack }
        var channels = 2
        if let asbd = CMSampleBufferGetFormatDescription(desc)?.audioStreamBasicDescription {
            channels = Int(asbd.mChannelsPerFrame)
        }
        channels = max(1, channels)

        var peaks = [Float](repeating: 0, count: buckets)
        var bucketIdx = 0
        var currentPeak: Float = 0
        // 每桶覆盖的帧数：总帧数/桶数；总帧数 ≔ 时长*采样率（用 44100 估计即可，误差不影响观感）
        let estSamplesPerBucket = max(1, Int(totalSeconds * 44100) / buckets)

        while let sampleBuffer = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            var data: UnsafeMutablePointer<UInt8>? = nil
            CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: nil, dataPointerOut: &data)
            guard let base = data else { continue }

            let sampleCount = length / 2
            base.withMemoryRebound(to: Int16.self, capacity: sampleCount) { samples in
                var i = 0
                while i + channels <= sampleCount && bucketIdx < buckets {
                    var framePeak: Int16 = 0
                    for c in 0..<channels {
                        let v = samples[i + c]
                        let a = v < 0 ? Int16(-Int(v)) : v   // abs（防 Int16.min 溢出用 Int 中转亦可）
                        if a > framePeak { framePeak = a }
                    }
                    let norm = Float(framePeak) / 32768.0
                    if norm > currentPeak { currentPeak = norm }
                    i += channels
                    if i / channels % estSamplesPerBucket == estSamplesPerBucket - 1 {
                        peaks[bucketIdx] = currentPeak
                        bucketIdx += 1
                        currentPeak = 0
                    }
                }
            }
        }
        if bucketIdx < buckets { peaks[bucketIdx] = currentPeak }
        return Waveform(amplitudes: peaks, duration: totalSeconds)
    }
}
