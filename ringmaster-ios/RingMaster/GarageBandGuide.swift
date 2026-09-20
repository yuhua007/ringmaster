import SwiftUI

/// 库乐队设铃声分步引导（MVP 正式界面，Phase 0 先落数据与视图骨架）。
/// 策略：iOS 无法由第三方 App 直接改系统铃声，本页把 30 秒官方流程做成图文步骤，
///      配合导出成功页自动弹出，降低流失（对标并超越 Zedge 的引导体验）。
struct GarageBandGuideView: View {

    let ringtoneFileName: String

    struct Step {
        let icon: String     // SF Symbol
        let title: String
        let detail: String
    }

    var steps: [Step] {
        [
            Step(icon: "square.and.arrow.down", title: "1. 存到「文件」",
                 detail: "导出面板里选「存储到文件」→「我的 iPhone」，本页之后你在文件 App 能找到 \(ringtoneFileName)"),
            Step(icon: "music.quarternote.3", title: "2. 打开库乐队新建项目",
                 detail: "App Store 免费下载「库乐队」→ + 新建 → 选「音频录制」→ 点音轨图标进入音轨视图"),
            Step(icon: "folder", title: "3. 拖入铃声文件",
                 detail: "点左上角「循环浏览」按钮 → 切到「文件」标签 → 找到 \(ringtoneFileName) → 长按拖到音轨上"),
            Step(icon: "arrowshape.turn.up.left", title: "4. 导出为电话铃声",
                 detail: "点左上角 ▼ 「我的歌曲」→ 长按项目 → 「共享」→「电话铃声」→「继续」→「导出」"),
            Step(icon: "phone.fill", title: "5. 设为铃声",
                 detail: "导出完成后系统弹窗「将铃声用作…」→ 选「标准电话铃声」；或到 设置→声音与触感→电话铃声 里选择"),
        ]
    }

    var body: some View {
        List {
            Section {
                Label("只需设置一次，以后换铃声都很快", systemImage: "lightbulb")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: step.icon)
                        .font(.title3)
                        .frame(width: 36)
                        .foregroundStyle(.accent)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(step.title).font(.subheadline.weight(.semibold))
                        Text(step.detail).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }
            Section {
                Link(destination: URL(string: "mus://")!) {
                    Label("打开库乐队", systemImage: "arrow.up.forward.app")
                }
            }
        }
        .navigationTitle("设为铃声教程")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack { GarageBandGuideView(ringtoneFileName: "我的铃声.m4r") }
}
