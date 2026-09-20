# RingMaster Design System — ANDROID（Jetpack Compose 落地）

> 依赖 MASTER.md 令牌。开发时装有 `compose-component-design` / `compose-animations` 等 Skill，冲突时以 MASTER 为准。

## 主题配置（Material 3，关闭动态色）

```kotlin
// 统一深色主题；dynamicColor 关闭——品牌色不随用户壁纸漂移
private val LightColors = darkColorScheme( // 只此一套，浅色后置 Phase 6
    primary = Accent,                 // #C8A24B（方案 A）或 #3FD0C9（方案 B）
    onPrimary = BgBase,               // #121212：主按钮文字反色
    secondary = AccentDim,
    background = BgBase,              // #121212
    surface = BgBase,
    surfaceVariant = BgElevated,      // #1C1C1E
    onBackground = ContentPrimary,    // #F5F5F2
    onSurface = ContentPrimary,
    onSurfaceVariant = ContentSecondary, // #9E9E99
    outline = Divider,                // #FFFFFF 8%
    error = ErrRed                    // #D0604F
)

MaterialTheme(
    colorScheme = LightColors,
    typography = RmTypography,
    // 不传 dynamicDarkColorScheme —— 固定品牌色
) { ... }
```

## 色彩令牌定义

```kotlin
object RmColor {
    val Accent = Color(0xFFC8A24B)        // 方案 A；方案 B: 0xFF3FD0C9
    val AccentPress = Color(0xFFA8873B)
    val AccentDim = Accent.copy(alpha = 0.4f)
    val BgBase = Color(0xFF121212)
    val BgElevated = Color(0xFF1C1C1E)
    val BgElevated2 = Color(0xFF2A2A2D)
    val ContentPrimary = Color(0xFFF5F5F2)
    val ContentSecondary = Color(0xFF9E9E99)
    val ContentTertiary = Color(0xFF5E5E5B)
    val Divider = Color.White.copy(alpha = 0.08f)
}
```

## Typography 映射

```kotlin
val RmTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge   = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    labelSmall   = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 18.sp),
)
// 时间码：FontFamily.Monospace + tabular 数字
```

## 布局纪律

- `Modifier.padding(16.dp)` 屏幕边距；卡片 `RoundedCornerShape(16.dp)` + `RmColor.BgElevated` 背景，**不用 elevation 阴影**
- 最小触控 48dp：图标按钮用 `Modifier.minimumInteractiveComponentSize()` 兜底
- 8pt 网格：所有 spacing 取 4/8/12/16/24

## 核心组件规范

### 波形（Canvas 自绘）
- `Canvas` + 桶数据（振幅 FloatArray），绘制用 `drawLine(cap = StrokeCap.Round)`
- 滚动/缩放优化用 `graphicsLayer { translationX = ... }` 平移，不在 draw 里做分配
- 选中段 accent、未选中 `ContentTertiary.copy(0.6f)`、选区底 `Accent.copy(0.10f)`
- 播放头 2dp `ContentPrimary`；把手 4dp accent + 命中区 48dp（`pointerInput` 判定）

### 动效（spring 统一参数）

```kotlin
// 全局统一：把手回弹 / Sheet / 状态切换
val rmSpring = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

// 逐条纪律（来自 compose-animations skill：选最小够用的 API）：
// 显隐 → AnimatedVisibility；单值 → animate*AsState(rmSpring, label=)
// 多值联动 → rememberTransition；拖拽中断续 → Animatable
// 颜色背景过渡 → animateColorAsState + Modifier.drawBehind（性能）
```

### 底部操作条（编辑页灵魂布局）
- 底部固定三键：「AI 生成 / 试听 / 导出」——primary 实心（accent），其余 tonal（surfaceVariant）
- AI 处理中：按钮内波形图标随能量脉动（`rememberInfiniteTransition` 驱动 scale 1.0→1.15，**唯一允许的 infinite 动画**，有物理意义）

### ModalBottomSheet
- `ModalBottomSheet(containerColor = BgElevated, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))`
- 用于：设为铃声三选（电话/通知/闹钟）、重命名、导出选项

## 图标
Material Symbols（`Icons.Rounded.*`），线宽统一 2dp 风格；**禁止 emoji**。

## 走查清单（Phase 1 验收）
- [ ] darkColorScheme 固定，无 dynamicColor、无紫色、无默认蓝
- [ ] 全部触控目标 ≥48dp
- [ ] 阴影 elevation ≤2dp（默认 0）
- [ ] 把手回弹 rmSpring 参数与 iOS 对齐（damping 0.7）
- [ ] AI 分析无转圈，是能量脉动
- [ ] 时间码等宽字体
