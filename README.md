# RikkaHubMultiSelect

给 RikkaHub 加一个多选。长按会话，或者点提供商、助手右边的三个点，菜单第一项会多出一个「多选」，
点进去就能一次处理好几条。

宿主是 [RikkaHub](https://github.com/rikkahub/rikkahub)（`me.rerere.rikkahub`），按 3.3.0 的源码对过。
包名 `com.elyezyc.rikkahubmultiselect`，Xposed API 82，GPL-3.0-or-later。
安装包在 [Releases](https://github.com/elyezyc/RikkaHubMultiSelect/releases)，每次改了什么记在
[CHANGELOG.md](CHANGELOG.md)。

## 为什么做这个

RikkaHub 里的列表都是单条操作：删会话要一条条长按，改提供商开关得一个个点进去，助手多了也只能挨个收拾。
本来只想能批量删会话，做着做着顺手把提供商和助手也加上了。

## 怎么用

三个入口都塞进了 RikkaHub 原来的菜单里，没有另加按钮：

- 侧边栏长按某个会话，菜单最上面是「多选」，下面还是原来那五项。
- 设置 → 提供商，点某一项右边的三个点，菜单第一项是「多选」。
- 助手列表，点某一项右边的三个点，一样。

点「多选」之后不会跳到另一个列表，就在当前这个列表里点。你刚才长按 / 点三点的那一条会自动选上：

- 对话：点一行切换选中，选中的行用的是 RikkaHub 自己的选中颜色。
- 提供商和助手：点整行切换，没选中的行会暗一点。
- 底部浮出一条操作栏，左边是关闭和「已选 N 项」，右边一排图标，长按图标会提示是什么动作。
- 切到别的页面、或者退到后台，多选会自己退出。

能做的事：

- 对话：置顶、取消置顶、移到其他助手、移到文件夹、重新生成标题、删除。
- 提供商：启用、停用、删除。
- 助手：设为当前、克隆、删除（连带清掉它名下的对话）。

删除、移文件夹这些确认框是模块自己画的 Material 3 对话框，不是系统那个旧样式。

## 安装

1. 先装 [Vector](https://github.com/JingMatrix/Vector)。它是 Zygisk 模块，需要 Magisk 或 KernelSU + Zygisk，
   刷完重启。
2. 从 [Releases](https://github.com/elyezyc/RikkaHubMultiSelect/releases) 下载 APK 装上。
3. 打开 Vector 管理界面，启用 `RikkaHubMultiSelect`。
4. 作用域勾上 RikkaHub。清单里写了 `xposedscope`，一般会自动勾好。
5. 强制停止 RikkaHub，再打开。

## 大概是这么实现的

模块里没打包任何宿主代码，启动时按类名反射拿到宿主的 `SettingsStore`、`ConversationRepository`
和数据库，之后都走宿主自己的数据层：

- 删会话调 `ConversationRepository.deleteConversation`，搜索索引、收藏、附件都由宿主自己清理。
- 置顶、换助手、换文件夹只是改一列，用一条 UPDATE 包在 Room 事务里。
- 提供商和助手的增删改走 `SettingsStore.update`，和设置页里点按钮是同一条路；
  新的 `Settings` 用编译器生成的 `copy$default` 造，不去改宿主手里那份对象。
- 宿主的挂起函数用一个自带的续体桥同步调用。R8 把不少续体形参收窄成了 `ContinuationImpl`，
  所以传进去的得是它真正的子类。
- 菜单项是这么加的：R8 把 `ItemActionMenu` 那些封装全内联掉了，本来没有地方能改菜单的动作列表，
  而应用菜单实际走的是 `CardKt.DropdownMenuItem`。模块在列表项组合时记住「这是哪个列表、哪一行」，
  等菜单第一项开始组合时，用宿主自己的 `DropdownMenuItem` 多加一项。
- 选中状态直接在行上改参数：会话行把宿主的 `selected` 置上，点击换成切换选中；提供商和助手行
  包一层点击、未选中的压暗。宿主不会因为我们变了就自己重组，所以每次选中变化还会轻轻推一下
  宿主的设置流，让列表重画一次。
- 颜色从宿主当前主题的 `ColorScheme` 里读，预设主题、自定义主题、动态取色都能跟，
  AMOLED 纯黑和字体缩放也一起跟着。

各个挂钩点都列在 [`docs/ANCHORS.md`](docs/ANCHORS.md) 里。

## 已知问题

**内置提供商删不掉。** RikkaHub 每次读设置、写设置都会把缺掉的 `DEFAULT_PROVIDERS` 补回来，
删了也自己长回来，所以这里只给内置条目提供启用和停用。真要删的话，装配套的
[RikkaHubNoSPON](https://github.com/elyezyc/RikkaHubNoSPON)：它会在规整之后把补回来的条目丢掉、
把 `builtIn` 清成 `false`，两个模块一起用时这边的删除就生效了。

**菜单注入依赖 Compose 内部实现。** 签名在启动时会逐项校验，对不上就把注入整个关掉，
不会把宿主搞崩，但宿主大改版之后可能要重新适配。

**删除不可撤销。** 确认框里会写清楚会影响多少条。

已经在真机上用过（RikkaHub 3.3.0，AMOLED 深色），菜单入口、列表内选中和底部操作栏都正常。
`./verify.sh` 的三项校验也一直在跑。

## 自己编译

不用 Gradle，直接 `aapt2` / `javac` / `d8` / `apksigner`。需要 JDK 17、build-tools 36、
platform 36 的 `android.jar`，还有 Xposed 的 `api-82.jar`：

```bash
./tools/fetch_toolchain.sh   # 第一次得跑；走代理时用 PROXY=http://host:port
./build.sh
./verify.sh
```

产物在 `dist/`。签名信息从 `Key/keystore.properties` 读，也可以用 `KS_PASS`、`KEY_ALIAS`、
`KEY_PASS` 覆盖。`Key/`、`tools/` 下的工具链和 `dist/` 都不进仓库，APK 只放 Releases。

`./verify.sh` 干三件事：拿 dexdump 对着宿主 APK 核 86 个反射锚点；跑 21 项 JVM 单测；
检查产出的 APK（入口、清单，以及有没有把编译期替身和 Xposed API 打进去）。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。
