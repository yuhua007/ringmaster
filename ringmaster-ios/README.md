# RingMaster iOS（Phase 0 代码就绪，待 Mac 编译验证）

> 本目录在 Windows 上编写完成，需要在 **Mac + Xcode 15+** 上完成最后一步编译与真机验证。

## 快速上手（约 5 分钟）

1. Mac 上打开 Xcode → `File > New > Project > iOS App`
   - Product Name: `RingMaster`，Interface: **SwiftUI**，Language: Swift
   - 保存到本目录旁边（不要覆盖本文件夹内容）
2. 把本目录 `RingMaster/` 下所有 `.swift` 文件拖入 Xcode 项目导航器（勾选 Copy items if needed，Target 勾选 RingMaster）
3. `Cmd+R` 真机运行（模拟器无法验证「库乐队设铃声」全流程）

## Phase 0 真机验证清单（Spike②）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | App 内选一首本地歌曲 | 波形正常渲染、可拖选区、可试听 |
| 2 | 选 ≤40 秒区间 → 点「导出 .m4r」 | 成功弹出分享面板，可见「存储到文件」 |
| 3.1 | 分享面板选「存储到文件」→ 存到「我的 iPhone」 | 文件 App 中可见 `.m4r` |
| 3.2 | 打开「库乐队」→ 浏览器切到「文件」→ 长按该 .m4r → 按住拖到音轨 | 出现 40s 音频条 |
| 3.3 | 点左上角▼ → 「我的歌曲」→ 长按项目 → 「共享」→ 「电话铃声」→ 导出 | 系统弹窗「将铃声设为…」可选 |
| 4 | 打电话给自己 | 听到剪辑的铃声 ✅ Spike② 通过 |
| 5 | 淡入淡出听感 | 开头结尾无爆音 |

## 文件说明

| 文件 | 职责 |
|---|---|
| `RingMaster/RingtoneExporter.swift` | **Spike② 核心**：AVFoundation 剪切 + 淡入淡出 + 导出 .m4r（≤40s） |
| `RingMaster/WaveformExtractor.swift` | AVAssetReader 解码 PCM → 振幅包络 |
| `RingMaster/WaveformView.swift` | SwiftUI Canvas 波形 + 选区手势 |
| `RingMaster/ContentView.swift` | Phase 0 演示界面（选歌→波形→导出→分享） |
| `RingMaster/RingMasterApp.swift` | App 入口 |
| `RingMaster/GarageBandGuide.swift` | 库乐队分步引导页（MVP 用） |

## 已知边界（代码内已处理）

- `.m4r` = AAC m4a 改后缀，`AVAssetExportPresetAppleM4A` 直出后重命名
- 导出时长硬限制 40s（苹果铃声规格）
- 淡入淡出走 `AVMutableAudioMix` 的音量斜坡，无需手写 PCM 处理
