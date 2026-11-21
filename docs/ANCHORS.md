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

另外不少挂起函数的续体形参从 `kotlin.coroutines.Continuation` 被收窄成了
`kotlin.coroutines.jvm.internal.ContinuationImpl`，传普通 `Continuation` 会被反射拒收，
这也是模块要自带一个 `ContinuationImpl` 子类的原因。
