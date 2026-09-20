import AVFoundation
import SwiftUI

/// Spike② 核心：剪切 + 淡入淡出 + 导出 .m4r（iOS 铃声）。
///
/// 验证点：
/// 1. AVAssetExportSession（AppleM4A preset）任意区间导出 AAC
/// 2. AVMutableAudioMix 音量斜坡实现淡入淡出（无爆音）
/// 3. 导出 .m4a 重命名为 .m4r 后，用户可经「文件」App 导入库乐队设为铃声
///
/// 注：iOS 沙盒不允许任何第三方 App 直接修改系统铃声，
///     本类只负责「生成合规的铃声文件」，设置环节由库乐队引导页（GarageBandGuide）承接。
enum RingtoneExporter {

    /// 苹果铃声规格上限 40 秒。
    static let maxDuration: TimeInterval = 40

    enum ExportError: LocalizedError {
        case noAudioTrack
        case exportFailed(String)
        case tooLong

        var errorDescription: String? {
            switch self {
            case .noAudioTrack: return "文件中没有音轨"
            case .exportFailed(let s): return "导出失败: \(s)"
            case .tooLong: return "铃声最长 40 秒，请缩短选区"
            }
        }
    }

    /// 导出 [start, start+duration) 区间为 .m4r，写入临时目录并返回 URL。
    /// - Parameters:
    ///   - fadeIn/fadeOut: 淡入淡出秒数（0 关闭）
    static func export(
        sourceURL: URL,
        start: TimeInterval,
        duration: TimeInterval,
        fadeIn: TimeInterval = 0.5,
        fadeOut: TimeInterval = 0.5
    ) async throws -> URL {
        let clamped = min(duration, maxDuration)
        guard clamped > 0 else { throw ExportError.tooLong }

        let asset = AVURLAsset(url: sourceURL)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            throw ExportError.noAudioTrack
        }

        let range = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            duration: CMTime(seconds: clamped, preferredTimescale: 600)
        )

        let outM4a = FileManager.default.temporaryDirectory
            .appendingPathComponent("ringmaster_\(Int(start))_\(Int(Date().timeIntervalSince1970)).m4a")
        try? FileManager.default.removeItem(at: outM4a)

        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw ExportError.exportFailed("cannot create export session")
        }
        session.outputURL = outM4a
        session.outputFileType = .m4a
        session.timeRange = range

        // 淡入淡出：音量斜坡（AppleM4A preset 支持 audioMix）
        let mix = AVMutableAudioMix()
        let params = AVMutableAudioMixInputParameters(track: track)
        if fadeIn > 0 {
            params.setVolumeRamp(fromStartVolume: 0, toEndVolume: 1,
                                 timeRange: CMTimeRange(
                                    start: range.start,
                                    duration: CMTime(seconds: min(fadeIn, clamped / 2), preferredTimescale: 600)))
        }
        if fadeOut > 0 {
            let fo = min(fadeOut, clamped / 2)
            params.setVolumeRamp(fromStartVolume: 1, toEndVolume: 0,
                                 timeRange: CMTimeRange(
                                    start: CMTime(seconds: range.start.seconds + clamped - fo, preferredTimescale: 600),
                                    duration: CMTime(seconds: fo, preferredTimescale: 600)))
        }
        mix.inputParameters = [params]
        session.audioMix = mix

        await session.export()
        guard session.status == .completed else {
            throw ExportError.exportFailed(session.error?.localizedDescription ?? "unknown")
        }

        // .m4r 就是改后缀的 AAC m4a——库乐队/系统铃声识别的关键
        let outM4r = outM4a.deletingPathExtension().appendingPathExtension("m4r")
        try? FileManager.default.removeItem(at: outM4r)
        try FileManager.default.moveItem(at: outM4a, to: outM4r)
        return outM4r
    }
}
