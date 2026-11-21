# 挂钩点

模块不编译期依赖宿主，所有宿主类型都按名字反射。这份清单是「名字必须对得上」的全部内容，
`./verify.sh` 会拿它逐项扫宿主发布包；宿主升级后先跑这个，能立刻看出哪里断了。

对照版本：[RikkaHub 3.3.0](https://github.com/rikkahub/rikkahub/tree/3.3.0)。

## 入口

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `me.rerere.rikkahub.RouteActivity#onCreate` | [`RouteActivity.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt) | 主界面创建后把悬浮按钮挂到 `decorView` 上 |
| `RouteActivity#onDestroy` | 同上 | 面板开着时跟随 Activity 一起关掉 |
| `SettingsStore` 构造器 | [`PreferencesStore.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt) | 在宿主启动时抓住单例 |
| `ConversationRepository` 构造器 | [`ConversationRepository.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt) | 同上 |

单例没抓到时有一个兜底：`RouteActivity` 里的 `by inject()` 会留下 `Lazy` 委托字段，
把它取出来、类型对得上就用。

## 读设置

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `SettingsStore.settingsFlow` | `PreferencesStore.kt` | 读当前设置快照（`StateFlow.getValue()`） |
| `SettingsStore#update(Function1, Continuation)` | 同上 | 写设置，和设置页同一条路径 |
| `Settings#getProviders` / `getAssistants` / `getAssistantId` | [`Settings.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt) | 取列表 |
| `Settings.providers` / `assistants` | 同上 | 拷贝时的定位依据（`componentN` 同一性） |
| `Settings#copy$default` / `component1` | 同上 | 生成改了某个属性的新 `Settings`，不用关心剩下五十多个参数 |

## 提供商与助手

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `ProviderSetting#getId/#getEnabled/#getName/#getModels/#getBuiltIn` | [`ProviderSetting.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt) | 列表展示、内置判断 |
| `ProviderSetting#copyProvider(Uuid, boolean, String, List, List, boolean, Function2, Function2)` | 同上 | 复制一份只改 `enabled` |
| `Assistant#getId/#getName` | [`Assistant.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt) | 助手列表 |

## 会话

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `ConversationRepository.database` | `ConversationRepository.kt` | 拿到宿主的 `AppDatabase` |
| `ConversationRepository#getConversationById(Uuid, ContinuationImpl)` | 同上 | 删除前取完整会话（含附件） |
| `ConversationRepository#deleteConversation(Conversation, ContinuationImpl)` | 同上 | 删除，宿主自己清 FTS、收藏、附件 |
| `conversationentity` 表（`id` / `title` / `assistant_id` / `is_pinned` / `update_at` / `folder_id`） | [`ConversationEntity.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt) | 列表读取；置顶、换助手是单列 `UPDATE` |
| `RoomDatabase#getOpenHelper` / `runInTransaction` | androidx.room | 走宿主的数据库连接和事务 |
| `SupportSQLiteDatabase#query` / `#execSQL`、`SupportSQLiteOpenHelper#getWritableDatabase` | androidx.sqlite | 同上 |

## 菜单注入

「多选」要出现在 RikkaHub 自己的长按菜单 / 右侧三点菜单里。R8 把 `ItemActionMenu`、`ConversationListKt`
这类封装整个内联掉了，没有能改「动作列表」的入口，所以只能借 Compose 菜单本身：

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `MenuKt.DropdownMenuPopup` | androidx.compose.material3 | 菜单打开时调用一次；从调用栈认出这是哪个列表的菜单，同时记下 `onDismissRequest` |
| `CardKt.DropdownMenuItem` | 同上 | **应用菜单真正用的重载**（text 是 Function2）：菜单第一项开始组合时，用宿主自己的这个函数多画一项「多选」，内边距、文字样式都复用那一项的 |
| `MenuKt.DropdownMenuItem` | 同上 | expressive 重载，少数菜单在用，同样注入 |
| `MenuKt.DropdownMenuContent` | 同上 | 菜单内容每组合一次就重置「本轮已插入」标记，重组合时结构不会错位 |
| `MenuItemColors#<init>` | 同上 | 给注入项配一套和其它项一致的文字颜色 |
| `TextKt#Text-(String…)` | 同上 | 在注入的项里画「多选」两个字；名字带混淆后缀，按 `Text-` 前缀 + 20 个形参找 |
| `IconKt#Icon-(ImageVector…)` | 同上 | 给「多选」这一项画图标 |
| `HugeIcons.getCheckList` / `getTick01` | `me.rerere.hugeicons` | 菜单图标用的 ImageVector（R8 把图标 getter 合并进了别的类，按候选类名找） |
| `ComposableLambdaImpl#<init>(int, boolean, Object)` | androidx.compose.runtime | 文字要包成 Compose 的 lambda |
| `kotlin.Unit.INSTANCE` | kotlin-stdlib | 上面的 lambda 返回值 |
| `CombinedClickableElement#<init>` | androidx.compose.foundation | 包一层长按回调：在会话行上长按时记下「这次是对话菜单」 |
| `ClickableElement#<init>` | 同上 | 包一层点击回调：点提供商 / 助手的三点时记下目标 |
| `ChatListKt.ConversationItem` | [`ChatDrawer.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt) / [`ConversationList.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt) | 长按菜单的来源（R8 把这两个文件合进了 `ChatListKt`） |
| `SettingProviderPageKt.ProviderItem` | [`SettingProviderPage.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt) | 提供商三点菜单的来源 |
| `AssistantPageKt.AssistantItem` | [`AssistantPage.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/AssistantPage.kt) | 助手三点菜单的来源 |

这些签名在模块启动时逐项校验（参数类型也要对得上），任何一项对不上就整体关掉菜单注入，
退回右侧的悬浮按钮，不会让宿主在组合过程中崩掉。

## 在列表里选中

| 挂钩点 | 用途 |
| --- | --- |
| `Updater#mutableStateOf$default` | 造一个 Compose 的 `MutableState`，改它触发列表重组 |
| `State#getValue` / `MutableState#setValue` | 行组合时读它登记依赖；切换选中时写它 |
| `Modifier$Companion.$$INSTANCE` | `Icon` 需要的空 Modifier |
| `contrast.Contrast#alpha(Modifier, float)` | 未选中的提供商 / 助手行压暗（R8 合并后的类名） |
| `ChatListKt#ConversationItem` 的 `selected` 参数 | 会话行直接复用宿主自己的选中底色 |
| `ProviderItem` / `AssistantItem` 的点击参数 | 选中模式下把「打开详情」换成「切换选中」 |

底部那条操作栏是模块自己画的 Android View，形状和宿主选消息时的悬浮工具栏一致；
选中动作本身都发生在宿主列表里。

## 配色

| 挂钩点 | 宿主源码 | 用途 |
| --- | --- | --- |
| `PresetThemeKt#getPresetThemes` | [`PresetTheme.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/theme/PresetTheme.kt) | 预设主题列表 |
| `PresetTheme.id` / `standardLight` / `standardDark` | 同上 | 按 `themeId` 找当前主题，直接用它预生成的 `ColorScheme` |
| `CustomTheme#generateColorScheme(boolean)` | [`CustomTheme.kt`](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/theme/CustomTheme.kt) | 自定义主题按种子色现算 |

颜色在 `ColorScheme` 里是内联类 `Color`，实际存成 `long` 字段（打包的 `ULong`，高 32 位是 ARGB）。
R8 把 getter 全内联掉了，所以按字段名读（`surface`、`primary`、`onSurfaceVariant`…），
读到 `long` 再右移 32 位；字段不在时再退回按 `getPrimary` / `getPrimary-…` 前缀找 getter。

## Kotlin / Compose 运行时

| 挂钩点 | 用途 |
| --- | --- |
| `kotlin.uuid.Uuid#<init>(long, long)` + `mostSignificantBits` / `leastSignificantBits` | 从数据库 id 串还原 `Uuid`（构造顺序会读回字段核对） |
| `kotlin.coroutines.jvm.internal.ContinuationImpl#<init>(Continuation)` | 模块自己的续体必须真继承它 |
| `kotlin.coroutines.jvm.internal.BaseContinuationImpl#invokeSuspend` | 续体被恢复时的入口 |
| `kotlin.coroutines.EmptyCoroutineContext.INSTANCE` | 续体的下游上下文 |
| `kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED` | 判断挂起函数是不是真的挂起了 |
| `kotlin.Result$Failure.exception` | 以失败恢复时取出异常 |
| `kotlinx.coroutines.flow.StateFlow#getValue` | 读设置快照 |
| `kotlin.jvm.functions.Function0` / `Function1` | 传给 `runInTransaction` / `update` 的 lambda 代理 |

## R8 之后不存在的东西

宿主没混淆，但 R8 删掉了不少「源码里有、包里没有」的成员。模块刻意绕开它们：

| 源码里有的 | 包里的情况 | 模块的做法 |
| --- | --- | --- |
| `Uuid.Companion.parse` | 连同 Companion 一起没了 | 用 `Uuid(long, long)` 构造 |
| `ConversationRepository.updatePinStatus` / `updateConversationAssistant` | 被内联后删除 | 直接用一列 `UPDATE` |
| `PresetThemeKt.findThemeById` | 删除 | 从 `getPresetThemes()` 列表里按 id 找 |
| `ConversationDAO` 接口 | 接口类被删，只剩实现类 | 走 Room 的 `SupportSQLiteDatabase` |
| `ItemActionMenuKt` / `ItemAction` | 内联进调用方后删除 | 直接挂 `MenuKt.DropdownMenuItem` 加一项 |
| `ConversationListKt` / `ChatDrawerKt` | 被合并进 `ChatListKt` | 按 `ChatListKt.ConversationItem` 认栈帧 |
| `ClickableKt` | 类被删（只剩 `CombinedClickableNode` 等） | 不走点击修饰符这条路 |

另外不少挂起函数的续体形参从 `kotlin.coroutines.Continuation` 被收窄成了
`kotlin.coroutines.jvm.internal.ContinuationImpl`，传普通 `Continuation` 会被反射拒收，
这也是模块要自带一个 `ContinuationImpl` 子类的原因。
