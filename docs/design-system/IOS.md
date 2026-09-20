# RingMaster Design System — iOS（SwiftUI 落地）

> 依赖 MASTER.md 令牌。开发时配合 `ios-ui-craft`（出稿）与 `swiftui-design-skill`（五维反 slop 审查），冲突时以 MASTER 为准。

## 主题基调

- **dark-first 唯一首发主题**：全部屏幕基于 `bg-base #121212` 构建；`.preferredColorScheme(.dark)` 锁定
- Tone：refined / focused。参照系（Apple 自家标杆）：Weather 的纵深、Fitness 的数据密度、Journal 的克制

## 色彩（Swift 扩展）

```swift
extension Color {
    static let rmAccent       = Color(hex: 0xC8A24B)  // 方案 A；方案 B: 0x3FD0C9
    static let rmAccentPress  = Color(hex: 0xA8873B)
    static let rmBg           = Color(hex: 0x121212)
    static let rmElevated     = Color(hex: 0x1C1C1E)
    static let rmElevated2    = Color(hex: 0x2A2A2D)
    static let rmTextPrimary  = Color(hex: 0xF5F5F2)
    static let rmTextSecondary = Color(hex: 0x9E9E99)
    static let rmDivider      = Color.white.opacity(0.08)
}
```

## 字体

```swift
// 标题：圆体（精密+亲和）
Text("RingMaster").font(.system(.title, design: .rounded).weight(.semibold))
// 时间码：等宽数字
Text(timecode).monospacedDigit()
```

## 材质边界（Liquid Glass 纪律）

- **iOS 26+**：`.glassEffect()` 只用于底部操作条与导航层；` glassEffectID` 做连续性过渡
- **iOS 26 以下**：`.ultraThinMaterial` 同样只允许控制层
- **波形/内容区永不用玻璃**——实色 `rmBg` 直接绘制，可用极弱径向光晕（`rmAccent.opacity(0.05)` radial gradient）造纵深
- 判断标准：glass 框住内容，绝不遮住内容

## 核心组件规范

### 波形（SwiftUI Canvas）
- `Canvas` 绘制振幅桶，`Path.addRoundedRect`/竖线 + `.round` lineCap
- 选中段 `rmAccent`、未选中 `rmTextSecondary.opacity(0.5)`、选区底 `rmAccent.opacity(0.10)`
- 把手：4pt 宽 `rmAccent` 竖线 + 胶囊抓手，命中宽 44pt；拖动实时时间码气泡

### 动效（spring 统一参数）

```swift
// 与 Android 对齐：dampingRatio 0.7
let rmSpring = Animation.spring(response: 0.4, dampingFraction: 0.7)

// 把手松手回弹 / Sheet / 状态切换统一用 rmSpring
// AI 分析态：波形振幅乘 (1.0 + 0.15 * sin(phase))，phase 由 TimelineView 驱动
// —— 禁止 Spinner；这是唯一允许的持续动画
withAnimation(rmSpring) { ... }
```

### 底部操作条（编辑页灵魂布局）
- `.safeAreaInset(edge: .bottom)` 固定三键：「AI 生成 / 试听 / 导出」
- primary：`rmAccent` 实心 + `rmBg` 文字；其余 `.tint` tonal 风格
- 控制层允许 `ultraThinMaterial`/glass，按钮本身保持高对比

### 库乐队引导页（iOS 体验的灵魂）
- 每步 = 动图（Phase 2 用 Lottie 或逐帧 APNG；Phase 0 先 SVG/截图占位）+ 一行标题 + 两行说明
- 步骤间 `.navigationTransition`；顶部进度点（当前步 accent）
- 最后一步给「打开库乐队」深链按钮（`mus://`）+「完成」主按钮
- 文案口吻：陪伴式（「只需要设置这一次」），不出现「一键设置铃声」字样（审核红线）

### Sheet
- `.presentationDetents([.medium])` + `.presentationCornerRadius(28)` + `rmElevated` 背景
- 用于：导出选项、重命名、铃声类型

## 图标
SF Symbols（`.symbolRenderingMode(.hierarchical)`，secondary 层用 `rmAccent`）；**禁止 emoji**。

## 反 AI-Slop 五维自审（swiftui-design-skill 出稿后必过）
1. Hierarchy：一眼看到「AI 生成」主按钮？
2. Typography：字级 ≤3 层，有 rounded 意图？
3. Color：accent 只在可操作处，无紫无默认蓝？
4. Spacing：8pt 网格，无随意间距？
5. Motion：全部意义驱动，无装饰动画？

## 走查清单（Phase 2 验收）
- [ ] 深色锁定，无跟随系统的浅色闪变
- [ ] glass 只出现在控制层；波形区域 100% 实色
- [ ] 把手回弹与 Android 手感一致（dampingFraction 0.7）
- [ ] SF Symbols 全覆盖，无 emoji
- [ ] 截图与 Android 并排对比无明显割裂
- [ ] 商店素材/文案不出现「一键设置铃声」
