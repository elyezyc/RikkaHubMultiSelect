# RikkaHubMultiSelect

RikkaHub 的 Xposed 模块。给会话、提供商、助手列表加上多选，一次处理好几条。

- 宿主：[RikkaHub](https://github.com/rikkahub/rikkahub)（`me.rerere.rikkahub`），对照 3.3.0 源码
- 包名：`com.elyezyc.rikkahubmultiselect`
- 模块 API：Xposed 82
- 许可证：GPL-3.0-or-later
- 安装包：[Releases](https://github.com/elyezyc/RikkaHubMultiSelect/releases)

## 它解决什么

RikkaHub 自己的列表都是单条操作：删会话要一条条长按，改提供商开关要一个个点进去，
助手多的时候也只好挨个收拾。这个模块把「多选」加到应用自己的菜单里，
勾选多条之后批量处理。

## 入口

不额外堆界面，入口就在 RikkaHub 原来的菜单里：

- **对话**：长按侧边栏里的会话，菜单第一项是「多选」（下面才是置顶、重新生成标题、移至助理、移至资料夹、删除）。
- **提供商**：设置 → 提供商，每一项右侧三点菜单的第一项是「多选」。
- **助手**：助手列表每一项右侧三点菜单的第一项是「多选」。

菜单项由模块用宿主自己的 `DropdownMenuItem` 画出来，形状、颜色、文字样式都跟着那一份菜单走。

## 选起来是什么样

点「多选」之后不会另开一个列表，就在 RikkaHub 自己的列表里选：

- 对话：点一下切换选中，选中的行用宿主自己的选中底色；长按行原来那套菜单不受影响。
- 提供商 / 助手：点整行切换选中，没选中的行会压暗一点。
- 底部浮出一条操作栏（位置和形状跟宿主自己选消息时那条一样），显示已选数量，右边是这一栏能做的动作。

| 栏 | 能做的事 |
| --- | --- |
| 对话 | 置顶、取消置顶、移到其他助手、移到文件夹、重新生成标题、删除 |
| 提供商 | 启用、停用、删除（内置的删不掉，见下面） |
| 助手 | 设为当前、克隆、删除（连带清理它名下的对话） |

配色直接读 RikkaHub 当前的主题（预设主题、自定义主题、Android 12+ 动态取色都能跟），
深色、AMOLED 纯黑、字体缩放也跟随宿主设置。删除类操作都会先弹确认框，写明会影响多少条。

## 安装

1. 装 [Vector](https://github.com/JingMatrix/Vector)。它是 Zygisk 模块，支持从 Android 8.1 起，
   模块 API 和原始 Xposed 一致。需要 Magisk 或 KernelSU + Zygisk，刷入系统模块后重启。
2. 从 [Releases](https://github.com/elyezyc/RikkaHubMultiSelect/releases) 下载 APK 装上。
3. 从系统通知打开 Vector 管理界面，在模块列表里启用 `RikkaHubMultiSelect`。
4. 作用域勾选 RikkaHub（`me.rerere.rikkahub`）。清单里声明了 `xposedscope`，一般会自动勾好。
5. 强制停止 RikkaHub 再打开。

第一次打开时界面右侧会有一个「多选」悬浮按钮兜底。等你在任意一个列表里打开过一次菜单、
菜单注入生效之后，这个按钮会自己收起来，之后都从菜单进。

## 实现方式

模块不打包宿主代码，也不往宿主的安装包里塞东西。RikkaHub 启动时按类名反射拿到几个单例，
之后所有操作都走宿主自己的数据层：

- 删会话调用 [`ConversationRepository.deleteConversation`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt)，
  搜索索引（FTS）、收藏、附件文件由宿主自己清理，行为和在应用里删一条一样。
- 置顶、换助手只是改一列，用一条 `UPDATE` 包在 Room 事务里，宿主的触发器照常通知界面刷新。
- 启用/停用/删除提供商、删除助手走 [`SettingsStore.update`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt)，
  和设置页里点按钮是同一条路径；新的 `Settings` 用编译器生成的 `copy$default` 造，不直接改宿主手里那份对象。
- 宿主的挂起函数用模块自带的续体桥同步调用。R8 会把不少挂起函数的续体形参从
  `Continuation` 收窄成 `ContinuationImpl`，所以交给它的是一个真正的 `ContinuationImpl` 子类。
- 菜单项：R8 把 `ItemActionMenu` 这类封装全内联掉了，没有能改动作列表的入口。应用菜单实际走的是
  `CardKt.DropdownMenuItem`（text 为 `Function2` 的经典重载）。模块在列表项组合时记下「这是哪个列表」，
  包一层点击 / 长按回调，菜单第一项开始组合时用宿主自己的 `DropdownMenuItem` 多加一项「多选」，
  内边距、颜色、文字样式、图标都沿用同一份菜单。
- 选中状态：行 composable 组合时按选中集合改参数——会话行直接把宿主的 `selected` 置上（所以高亮是
  宿主自己的），点击换成切换选中；提供商 / 助手行包一层点击，未选中的行套一个 `Modifier.alpha`。
  宿主不会因为我们变了就重组，所以另外用一个 Compose 的 `MutableState` 在每行组合时登记依赖，
  改它就能让列表重新组合。
- 面板配色从宿主的 `PresetTheme` 里读已经生成好的 `ColorScheme`，取不到时依次退到
  系统动态取色色阶、Material 3 基线配色。

挂钩点全部按名字来，具体清单在 [`docs/ANCHORS.md`](docs/ANCHORS.md)。

## 已知限制

- **内置提供商删不掉**。RikkaHub 每次读盘、每次写设置都会跑一遍
  [规整](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt)，
  把缺掉的 `DEFAULT_PROVIDERS` 补回列表。所以面板只给内置条目提供启用/停用；删了也会自己长回来。
  如果你另外装了会清 `builtIn` 标记的模块，删除就能生效。
- 菜单注入依赖 Compose 内部实现（`DropdownMenuPopup` / `DropdownMenuItem` / `ComposableLambdaImpl`）。
  签名在启动时会逐项校验，对不上就整体关掉、退回悬浮按钮，不会把宿主带崩。宿主大改版后可能要重新适配。
- 删除操作不可撤销。
- 这个版本没在真机上跑过。`./verify.sh` 里的三项校验（86 个锚点对照宿主发布包、
  21 项 JVM 单测、产物检查）都过了，但设备上的实际表现——比如 ART 对续体恢复、
  反射改 `final` 字段的处理、Compose 里注入菜单项——还要装上才能确认。
  用之前先备份一次 RikkaHub 数据比较稳妥。

## 构建

不需要 Gradle，直接 `aapt2` / `javac` / `d8` / `apksigner`。需要 JDK 17、Android build-tools 36、
platform 36 的 `android.jar`，以及 Xposed 的 `api-82.jar`：

```bash
./tools/fetch_toolchain.sh   # 首次执行；需要代理时用 PROXY=http://host:port
./build.sh
./verify.sh
```

`build.sh` 产物在 `dist/RikkaHubMultiSelect-<版本>.apk`。签名信息从 `Key/keystore.properties` 读
（`storePassword`、`keyAlias`、`keyPassword`），也可以用 `KS_PASS`、`KEY_ALIAS`、`KEY_PASS` 覆盖。

`Key/`、`tools/` 下的工具链和 `dist/` 都不进仓库，APK 只发 Releases。

## 校验

`./verify.sh` 依次跑：

1. **反射锚点**：用 `dexdump` 扫 RikkaHub 的发布包，逐项确认模块用到的类、方法、字段都在，
   包括 R8 收窄后的续体类型、`Settings.copy$default`、以及注入菜单项要用的 Compose 内部方法。
   宿主升级后先跑这个，能立刻看出哪里断了。
2. **JVM 单测**：SQL 占位符、UUID 拆分、`copy$default` 位掩码、data class 拷贝，
   以及挂起函数桥的三种情况（直接返回 / 别的线程恢复 / 以失败恢复）。
3. **产物检查**：`xposed_init`、清单里的 Xposed 元数据、dex 里的类，确认编译期的 kotlin 替身、
   Xposed API 本体、签名口令都没有被打进 APK。

## 目录

```
module/   模块源码与资源（HookEntry、Host、菜单注入、面板界面、宿主动作）
stubs/    编译期的 kotlin 替身，只参与编译，不进 APK
tests/    JVM 单测
tools/    工具链下载、锚点校验、产物校验
```

## 许可

以 GPL-3.0-or-later 发布，见 [LICENSE](LICENSE)。
