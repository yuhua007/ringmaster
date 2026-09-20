# RingMaster Design System — MASTER（双端共用宪法）

> v1.0 · 2026-08 · dark-first · 本文件是双端唯一设计真相源（single source of truth）。
> Android 按 `ANDROID.md` 落地，iOS 按 `IOS.md` 落地；冲突时以本文件为准。

## 1. 品牌调性

**Tone：refined / focused（克制·精致·专注）**——「本地、买断、零广告的精品工具」。

一句话气质：安静深色房间里的一件金（青）色精密仪器。波形是主角，UI 是舞台，永远不抢戏。

## 2. 色彩令牌（双主色方案，Android MVP 开工时二选一定稿）

### 方案 A · 暖金（推荐，默认）
| Token | 值 | 用途 |
|---|---|---|
| accent | `#C8A24B` | 主操作、选区波形、进度 |
| accent-press | `#A8873B` | 按下态 |
| accent-dim | `#C8A24B` @ 40% | 焦点环、次要强调 |

### 方案 B · 冷青
| Token | 值 | 用途 |
|---|---|---|
| accent | `#3FD0C9` | 同上 |
| accent-press | `#35ABA5` | 按下态 |
| accent-dim | `#3FD0C9` @ 40% | 次要强调 |

### 中性色阶（两案通用，暗色优先）
| Token | 值 | 用途 |
|---|---|---|
| bg-base | `#121212` | 页面底 |
| bg-elevated | `#1C1C1E` | 卡片、Sheet |
| bg-elevated-2 | `#2A2A2D` | 输入框、次级浮层 |
| content-primary | `#F5F5F2`（暖白） | 主文字 |
| content-secondary | `#9E9E99` | 次文字 |
| content-tertiary | `#5E5E5B` | 占位/禁用 |
| divider | `#FFFFFF` @ 8% | 分隔线 |
| success | `#5BB98C` · error `#D0604F` | 语义 |

**色彩纪律**：主色统治——accent 只出现在「当前可操作/被选中」的东西上（AI 按钮、选区波形、把手、进度条）。大面积永远是中性色阶。**禁止均匀分布的彩色**（ timid/uncommitted）；**禁止紫色/靛蓝渐变**（AI slop 标志）；**禁止默认蓝**。

## 3. 字体层级（双端映射）

| 层级 | 规格（暗色基准） | iOS | Android |
|---|---|---|---|
| display | 28/34 半粗 | `.fontDesign(.rounded)` | `FontFamily.SansSerif` W600（或 `sans-serif-rounded`） |
| title | 20/26 半粗 | rounded | W600 |
| body | 16/24 常规 | 默认 SF Pro | W400 |
| caption | 13/18 常规 | 默认 | W400 |
| mono（时间码） | 15 常规 | `.monospacedDigit()` | `FontFamily.Monospace` |

标题一律 rounded（圆体）传达「音频工具的精密+亲和」；正文用系统默认保证可读性与本地化。时间码必须等宽数字，防止跳变。

## 4. 间距与网格

- 基础单位 **8pt**，半步 4pt 仅用于图标与文字的内间隙
- 屏幕左右边距 16pt；卡片内边距 16pt；组件间垂直间距 12/16/24 三档
- 触控目标最小 **48×48dp**（Android）/ **44×44pt**（iOS），视觉元素可以小于此，命中区不许

## 5. 形状与高度

- 组件圆角 12–16dp；底部 Sheet 顶部圆角 **28dp**；把手（handle bar）胶囊形
- 阴影 0–2dp 极轻或不用——层次靠色阶（bg-base → elevated → elevated-2）不靠投影

## 6. 动效宪法

1. **意义驱动**：动效只用于状态变化（选区、展开、成功反馈），不做纯装饰
2. **spring 优先**：交互回弹统一 `dampingRatio ≈ 0.6–0.8`、中低 stiffness；补间动画时长 150–300ms
3. **把手拖拽松手**：spring 回弹（这是「手感一致性」的双端验收点）
4. **AI 分析态**：波形随 RMS 能量脉动（振幅包络做 1.0→1.15 呼吸），**禁止转圈圈**，禁止 infinite 闪烁/蹦迪动画
5. **空间连续**：列表→编辑器的过渡保持波形元素位置连续（shared element 优先）
6. **可及性**：跟随系统 reduce-motion 设置降级为淡入淡出

## 7. 材质边界（iOS Liquid Glass / Android Surface）

- **玻璃/模糊只允许出现在控制层与导航层**（底部操作条、Sheet 头、顶栏）
- **波形与内容区永远实色**（bg-base 上直接绘制），允许极弱径向光晕（accent @ 4–6%）营造纵深，禁止磨砂盖住波形——波形被磨砂遮住等于自废武功
- Android 不追玻璃拟态，用 elevated 色阶 + 1px divider 表达层次

## 8. 禁止清单（Anti-Slop，双端同权）

| 禁止 | 替代 |
|---|---|
| 紫色/靛蓝渐变 | 方案 A/B 主色 + 中性色阶 |
| emoji 当图标 | iOS: SF Symbols；Android: Material Symbols |
| 默认蓝按钮 | accent 实心按钮 |
| glass 满天飞/遮内容 | 仅控制层 |
| cookie-cutter 卡片白矩形+阴影 | 深色卡片 bg-elevated，圆角 16，无阴影 |
| 均匀彩色分布 | 主色统治 + 锐利点缀 |
| infinite 闪烁动画 | 能量脉动（有物理意义） |
| 空状态用灰色大图标+「暂无数据」 | 引导性插画位 + 主行动按钮（「选一首歌开始」） |

## 9. 组件状态（所有可交互组件）

5 态齐全：normal / press(accent-press) / disabled(content-tertiary @ 40%，并保持 48dp 命中) / loading(accent 脉动，不出新色) / focus(2dp accent-dim 焦点环，键盘/无障碍导航用)。

## 10. 波形区域规范（本产品灵魂组件）

- 高度 120pt 起步；未选中段 content-tertiary 色 @ 60%，选中段 accent，选区底色 accent @ 10%
- 把手：4dp 宽 accent 竖线 + 顶端小胶囊抓手，命中区 48dp 宽
- 播放头：2dp 暖白竖线，顶部小三角
- 拖动时实时显示时间码气泡（mono 字体）；松手 spring 回弹
- 空数据/加载：骨架用低振幅静态波形占位（不是转圈）

## 11. 双端一致性验收（Phase 1/2 走查项）

- [ ] 同一屏幕截图并排，色板/字级/间距无法区分出「两个团队」
- [ ] 把手拖拽回弹手感一致（spring 参数对齐：damping 0.7 ± 0.1）
- [ ] 波形渲染：同音频同选区，双端视觉密度一致（桶宽/像素比统一）
- [ ] 深色为唯一首发主题；浅色主题后置到 Phase 6
