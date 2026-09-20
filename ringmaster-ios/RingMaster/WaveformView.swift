import SwiftUI

/// 波形图 + 选区交互（与 Android 端 WaveformView 行为对齐）。
/// 灰色=未选中，主题色=选区内，白线=播放头，两侧竖线=可拖手柄。
struct WaveformView: View {
    let amplitudes: [Float]
    let duration: TimeInterval
    @Binding var selectionStart: TimeInterval
    @Binding var selectionEnd: TimeInterval
    var position: TimeInterval = 0

    @State private var dragMode: Mode = .start
    @State private var lastXSeconds: TimeInterval = 0

    enum Mode { case start, end, move }

    var body: some View {
        Canvas { ctx, size in
            let n = max(1, amplitudes.count)
            let barSpace = size.width / CGFloat(n)
            let mid = size.height / 2
            let startF = CGFloat(selectionStart / duration) * size.width
            let endF = CGFloat(selectionEnd / duration) * size.width

            // 选区底色
            ctx.fill(
                Path(CGRect(x: startF, y: 0, width: endF - startF, height: size.height)),
                with: .color(.accentColor.opacity(0.10))
            )

            // 波形柱
            for i in 0..<n {
                let x = CGFloat(i) / CGFloat(n) * Double(duration)
                let inSel = x >= selectionStart && x <= selectionEnd
                let amp = CGFloat(amplitudes[i]) * 0.9 * mid + 0.02 * mid
                var p = Path()
                p.move(to: CGPoint(x: CGFloat(i) * barSpace + barSpace / 2, y: mid - amp))
                p.addLine(to: CGPoint(x: CGFloat(i) * barSpace + barSpace / 2, y: mid + amp))
                ctx.stroke(p, with: .color(inSel ? .accentColor : Color(.systemGray4)),
                           style: StrokeStyle(lineWidth: max(1, barSpace * 0.6), lineCap: .round))
            }

            // 手柄
            for x in [startF, endF] {
                var p = Path()
                p.move(to: CGPoint(x: x, y: 0))
                p.addLine(to: CGPoint(x: x, y: size.height))
                ctx.stroke(p, with: .color(.accentColor), style: StrokeStyle(lineWidth: 4, lineCap: .round))
            }

            // 播放头
            if position > 0 {
                let x = CGFloat(position / duration) * size.width
                var p = Path()
                p.move(to: CGPoint(x: x, y: 0))
                p.addLine(to: CGPoint(x: x, y: size.height))
                ctx.stroke(p, with: .color(.white), style: StrokeStyle(lineWidth: 2))
            }
        }
        .frame(height: 120)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { g in
                    let sec = Double(g.location.x / UIScreen.main.bounds.width) * duration
                    if g.startLocation == g.location {
                        let nearStart = abs(sec - selectionStart) < 0.4
                        let nearEnd = abs(sec - selectionEnd) < 0.4
                        dragMode = nearStart ? .start : (nearEnd ? .end : (sec > selectionStart && sec < selectionEnd ? .move : .start))
                        lastXSeconds = sec
                    }
                    let delta = sec - lastXSeconds
                    lastXSeconds = sec
                    let minLen: TimeInterval = 0.5
                    switch dragMode {
                    case .start:
                        selectionStart = min(max(0, sec), selectionEnd - minLen)
                    case .end:
                        selectionEnd = max(min(duration, sec), selectionStart + minLen)
                    case .move:
                        let len = selectionEnd - selectionStart
                        let s = min(max(0, selectionStart + delta), duration - len)
                        selectionStart = s; selectionEnd = s + len
                    }
                }
        )
    }
}
