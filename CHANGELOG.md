# 更新日志

## 1.0.0 — 2026-11-21

首个版本，对照 RikkaHub 3.3.0。

### 新增：三个入口都在 RikkaHub 自己的菜单里

- **对话**：长按侧边栏里的会话，菜单第一项是「多选」，后面才是置顶、重新生成标题、移至助理、
  移至资料夹、删除。
- **提供商**：设置 → 提供商，每一项右侧三点菜单的第一项是「多选」。
- **助手**：助手列表每一项右侧三点菜单的第一项是「多选」。

菜单项由模块用宿主自己的 `DropdownMenuItem` 画出来，圆角、内边距、文字样式、颜色都沿用同一份菜单，
图标取宿主自带的 HugeIcons 清单图标；取不到时文字前自动补一个 ✓ 兜底。

### 新增：选中就在原列表里做，不另开面板

- 点「多选」后直接在当前列表里选，不弹出模块自己的列表。
- 进入多选时会**自动选中**你长按 / 点三点的那一条。
- 对话行用宿主自己的选中底色高亮；提供商、助手行点击切换，未选中的行压暗。
- 底部浮出一条操作栏（位置、高度、圆角、配色和 RikkaHub 自己选消息时那条悬浮工具栏一致）：
  左边是关闭和已选数量，右边是一排圆形线条图标，长按图标会提示这个动作是什么。
- 切到别的页面或退到后台会自动退出多选、收起操作栏。

### 新增：批量动作

| 栏 | 动作 |
| --- | --- |
| 对话 | 置顶、取消置顶、移到其他助手、移到文件夹、重新生成标题、删除 |
| 提供商 | 启用、停用、删除（内置条目会被跳过） |
| 助手 | 设为当前、克隆、删除（连带清理名下对话） |

- 「重新生成标题」调用宿主自己的 `onRegenerateTitle` 回调，逐个会话触发。
- 「移到文件夹」读宿主的 `conversation_folder` 表，只列所选会话那个助手的文件夹；
  混选不同助手时会提示，避免把会话塞进别人的文件夹里变成看不见。
- 「克隆」按宿主自己的做法复制一份助手并加 ` (Clone)`。
- 删除走宿主的清理路径：搜索索引、收藏、附件文件都由宿主处理。

### 新增：M3 风格的对话框

选择文件夹、移动助手、删除确认全部替换为模块自己按 Material 3 规格画的对话框：

- 28dp 圆角、`surfaceContainer` 底色、24dp 内边距；AMOLED 纯黑时加一圈细描边。
- 标题 22sp，正文 14sp 并放大行距，按钮是胶囊形（确认用 `secondaryContainer`，
  破坏性操作用 `errorContainer`）。
- 选择列表每行 52dp、圆角 ripple；选项多时按内容自适应，超过屏高再滚动。

不再使用 `android.app.AlertDialog`——宿主主题没有给平台对话框配 `alertDialogTheme`，
那个会掉回旧版 Android 的样式。

### 主题与观感

- 配色读 RikkaHub 当前主题里预生成好的 `ColorScheme`：预设主题直接取，自定义主题按种子色现算；
  读不到时依次退到 Android 12+ 动态取色色阶、Material 3 基线配色。
- 深色模式跟随宿主的 `colorMode` 偏好，AMOLED 纯黑、字体缩放（`displaySetting.fontSizeRatio`）也跟随。
- 底部操作栏的图标用 Canvas 直接画线条（24×24 坐标系，细线圆头），不依赖宿主资源表；
  置顶是大头钉（宽帽 + 颈部 + 针），取消置顶在同一形状上加斜杠。

### 实现

- 不打包宿主代码，启动时按类名反射拿 `SettingsStore`、`ConversationRepository`、`AppDatabase`。
- 宿主的挂起函数用模块自带的续体桥同步调用。R8 把不少挂起函数的续体形参从 `Continuation`
  收窄成了 `ContinuationImpl`，所以交给它的是一个真正的 `ContinuationImpl` 子类。
- 写设置走 `SettingsStore.update`，新的对象用编译器生成的 `copy$default` 造，
  不直接改宿主手里那份；置顶、换助手、换文件夹是包在 Room 事务里的单列 `UPDATE`。
- 菜单注入：R8 把 `ItemActionMenu` 这类封装全内联掉了，应用菜单实际走的是
  `CardKt.DropdownMenuItem`（text 为 `Function2` 的经典重载）。模块在列表项组合时记下
  「这是哪个列表、哪一行」，包一层点击 / 长按回调，在菜单第一项组合时用宿主自己的
  `DropdownMenuItem` 多加一项；菜单来源再按当前页面兜底识别。
- 关闭宿主菜单：优先用它给的 `onDismissRequest`；不行就在菜单项回调 / 菜单内容 lambda 里找出
  那个 `MutableState<Boolean>` 直接设回 `false`（宿主自己点菜单项就是这么关的）；最后才发返回键。
- 因为模块在 composable 外面读 Compose 状态登记不到依赖，选中变化后会用一次很轻的
  `SettingsStore.update { it }`（250ms 内合并）推一下，让读设置的列表整体重组。

### 修复（开发过程中遇到的问题）

- 一开始挂的是 `MenuKt` 的 expressive 重载，应用菜单根本不走它，导致三个入口全都没反应；
  改为挂 `CardKt.DropdownMenuItem`。
- 「同一菜单只插一次」的标记曾是粘性的，某个菜单不经过 `DropdownMenuContent` 时会挡掉后面所有菜单，
  导致提供商三点没有「多选」；改成 700ms 时间窗 + 按菜单内容识别。
- 点「多选」后宿主菜单不收起；改为翻转宿主自己的展开状态，并加 250ms 重试和返回键兜底。
- 底部操作栏图标一度是空白圆底；改用自己的 Canvas 线条图标。
- 进入多选时不会选中触发的那一条；改为在行组合时把 id 带进点击回调。

### 已知限制

- **内置提供商删不掉**：RikkaHub 每次规整设置都会把缺掉的 `DEFAULT_PROVIDERS` 补回来。
  面板只给内置条目提供启用 / 停用；若另外装了清 `builtIn` 标记的模块，删除就能生效。
- 删除操作不可撤销。
- 菜单注入依赖 Compose 内部实现（`CardKt.DropdownMenuItem`、`DropdownMenuPopup`、
  `ComposableLambdaImpl` 等），签名在启动时逐项校验，对不上就整体关掉，不会在组合过程中崩宿主；
  宿主大改版后需要重新适配。
- 这个版本没有在真机上做过完整回归。静态校验（86 个锚点、21 项单元测试、产物检查）都通过，
  但设备上的实际表现仍以实机为准。用之前建议先备份一次 RikkaHub 数据。

### 构建与校验

- 不需要 Gradle：`aapt2` / `javac` / `d8` / `zipalign` / `apksigner`。
- 需要 JDK 17、Android build-tools 36、platform 36 的 `android.jar`、Xposed `api-82.jar`。
- `./tools/fetch_toolchain.sh` 拉工具链，`./build.sh` 出包，`./verify.sh` 跑三项校验。
- `./verify.sh`：86 个反射锚点对照 RikkaHub 3.3.0 发布包；21 项 JVM 单元测试
  （SQL 占位符、UUID 拆分、`copy$default` 位掩码、data class 拷贝、挂起函数桥）；
  产物检查（`xposed_init`、清单 Xposed 元数据、dex 类清单，确认编译期替身、Xposed API、
  签名口令都没有进 APK）。

### 许可

GPL-3.0-or-later。
