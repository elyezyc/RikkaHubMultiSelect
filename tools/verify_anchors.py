#!/usr/bin/env python3
"""核对模块依赖的宿主锚点，在 RikkaHub 的 APK 里逐项确认存在。

模块不编译期依赖宿主，只按类名 / 方法名反射，所以「这些名字还在不在」必须单独验。
默认读仓库根目录的 rikkahub-3.3.0.apk，也可以用参数或 HOST_APK 指定别的版本：

    python3 tools/verify_anchors.py /path/to/rikkahub.apk

SPDX-License-Identifier: GPL-3.0-or-later
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "rikkahub-3.3.0.apk")
DEXDUMP = os.environ.get(
    "DEXDUMP",
    os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/dexdump"),
)

DESC_RE = re.compile(r"^  Class descriptor\s+: '(.*)'$")
SECTION_RE = re.compile(r"^  (Static fields|Instance fields|Direct methods|Virtual methods)\s+-")
NAME_RE = re.compile(r"^      name\s+: '(.*)'$")
TYPE_RE = re.compile(r"^      type\s+: '(.*)'$")


def parse_dex(path):
    """返回 {类描述符: {"field": [...], "method": [...]}}，成员是 (名字, 类型描述符)。"""
    out = subprocess.run([DEXDUMP, path], capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        raise SystemExit("dexdump 失败: %s\n%s" % (path, out.stderr[-2000:]))
    classes = {}
    current = None
    kind = None
    pending = None
    for line in out.stdout.splitlines():
        match = DESC_RE.match(line)
        if match:
            current = classes.setdefault(match.group(1), {"field": [], "method": []})
            kind = None
            pending = None
            continue
        match = SECTION_RE.match(line)
        if match:
            kind = "field" if "fields" in match.group(1) else "method"
            pending = None
            continue
        match = NAME_RE.match(line)
        if match:
            pending = match.group(1)
            continue
        match = TYPE_RE.match(line)
        if match and current is not None and kind and pending is not None:
            current[kind].append((pending, match.group(1)))
            pending = None
    return classes


# (说明, 类描述符, 类别, 成员名, 类型里必须出现的片段)
REQUIRED = [
    ("宿主主界面 RouteActivity", "Lme/rerere/rikkahub/RouteActivity;", "class", None, None),
    ("RouteActivity#onCreate", "Lme/rerere/rikkahub/RouteActivity;", "method", "onCreate",
     ("Landroid/os/Bundle;",)),

    ("SettingsStore", "Lme/rerere/rikkahub/data/datastore/SettingsStore;", "class", None, None),
    ("SettingsStore.settingsFlow", "Lme/rerere/rikkahub/data/datastore/SettingsStore;", "field",
     "settingsFlow", ("StateFlow",)),
    ("SettingsStore#update", "Lme/rerere/rikkahub/data/datastore/SettingsStore;", "method", "update",
     ("Lkotlin/jvm/functions/Function1;", "Continuation;")),

    ("ConversationRepository", "Lme/rerere/rikkahub/data/repository/ConversationRepository;",
     "class", None, None),
    ("ConversationRepository.database", "Lme/rerere/rikkahub/data/repository/ConversationRepository;",
     "field", "database", ("AppDatabase;",)),
    ("ConversationRepository#getConversationById",
     "Lme/rerere/rikkahub/data/repository/ConversationRepository;", "method",
     "getConversationById", ("Lkotlin/uuid/Uuid;", "ContinuationImpl;")),
    ("ConversationRepository#deleteConversation",
     "Lme/rerere/rikkahub/data/repository/ConversationRepository;", "method",
     "deleteConversation", ("Lme/rerere/rikkahub/data/model/Conversation;", "ContinuationImpl;")),

    ("Settings", "Lme/rerere/rikkahub/data/datastore/Settings;", "class", None, None),
    ("Settings.providers", "Lme/rerere/rikkahub/data/datastore/Settings;", "field", "providers",
     ("Ljava/util/List;",)),
    ("Settings.assistants", "Lme/rerere/rikkahub/data/datastore/Settings;", "field", "assistants",
     ("Ljava/util/List;",)),
    ("Settings#getProviders", "Lme/rerere/rikkahub/data/datastore/Settings;", "method", "getProviders",
     ("()Ljava/util/List;",)),
    ("Settings#getAssistants", "Lme/rerere/rikkahub/data/datastore/Settings;", "method",
     "getAssistants", ("()Ljava/util/List;",)),
    ("Settings#getAssistantId", "Lme/rerere/rikkahub/data/datastore/Settings;", "method",
     "getAssistantId", ("Lkotlin/uuid/Uuid;",)),
    ("Settings#copy$default", "Lme/rerere/rikkahub/data/datastore/Settings;", "method",
     "copy$default", ("Lme/rerere/rikkahub/data/datastore/Settings;", "Ljava/lang/Object;")),
    ("Settings#component1", "Lme/rerere/rikkahub/data/datastore/Settings;", "method", "component1",
     ("()",)),

    ("ProviderSetting", "Lme/rerere/ai/provider/ProviderSetting;", "class", None, None),
    ("ProviderSetting#getId", "Lme/rerere/ai/provider/ProviderSetting;", "method", "getId",
     ("()Lkotlin/uuid/Uuid;",)),
    ("ProviderSetting#getEnabled", "Lme/rerere/ai/provider/ProviderSetting;", "method", "getEnabled",
     ("()Z",)),
    ("ProviderSetting#getName", "Lme/rerere/ai/provider/ProviderSetting;", "method", "getName",
     ("()Ljava/lang/String;",)),
    ("ProviderSetting#getModels", "Lme/rerere/ai/provider/ProviderSetting;", "method", "getModels",
     ("()Ljava/util/List;",)),
    ("ProviderSetting#getBuiltIn", "Lme/rerere/ai/provider/ProviderSetting;", "method", "getBuiltIn",
     ("()Z",)),
    ("ProviderSetting#copyProvider", "Lme/rerere/ai/provider/ProviderSetting;", "method",
     "copyProvider", ("Lkotlin/jvm/functions/Function2;", "Lme/rerere/ai/provider/ProviderSetting;")),

    ("Assistant", "Lme/rerere/rikkahub/data/model/Assistant;", "class", None, None),
    ("Assistant#getId", "Lme/rerere/rikkahub/data/model/Assistant;", "method", "getId",
     ("()Lkotlin/uuid/Uuid;",)),
    ("Assistant#getName", "Lme/rerere/rikkahub/data/model/Assistant;", "method", "getName",
     ("()Ljava/lang/String;",)),

    ("PresetThemeKt#getPresetThemes", "Lme/rerere/rikkahub/ui/theme/PresetThemeKt;", "method",
     "getPresetThemes", ("()Ljava/util/List;",)),
    ("PresetTheme.id", "Lme/rerere/rikkahub/ui/theme/PresetTheme;", "field", "id",
     ("Ljava/lang/String;",)),
    ("CustomTheme#generateColorScheme", "Lme/rerere/rikkahub/ui/theme/CustomTheme;", "method",
     "generateColorScheme", ("(Z)Landroidx/compose/material3/ColorScheme;",)),
    ("MenuKt#DropdownMenuPopup", "Landroidx/compose/material3/MenuKt;", "method",
     "DropdownMenuPopup", ("ComposableLambdaImpl;", "Lkotlin/jvm/functions/Function0;")),
    ("MenuKt#DropdownMenuItem", "Landroidx/compose/material3/MenuKt;", "method",
     "DropdownMenuItem", ("ComposableLambdaImpl;", "Lkotlin/jvm/functions/Function2;", "GapComposer;")),
    ("ComposableLambdaImpl#<init>", "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
     "method", "<init>", ("(IZLjava/lang/Object;)V",)),
    ("ChatListKt#ConversationItem", "Lme/rerere/rikkahub/ui/pages/chat/ChatListKt;", "method",
     "ConversationItem", ("Lme/rerere/rikkahub/data/model/Conversation;",)),
    ("SettingProviderPageKt#ProviderItem", "Lme/rerere/rikkahub/ui/pages/setting/SettingProviderPageKt;",
     "method", "ProviderItem", ("Lme/rerere/ai/provider/ProviderSetting;",)),
    ("AssistantPageKt#AssistantItem", "Lme/rerere/rikkahub/ui/pages/assistant/AssistantPageKt;",
     "method", "AssistantItem", ("Lme/rerere/rikkahub/data/model/Assistant;",)),
    ("kotlin.Unit.INSTANCE", "Lkotlin/Unit;", "field", "INSTANCE", ("Lkotlin/Unit;",)),

    ("PresetTheme.standardLight", "Lme/rerere/rikkahub/ui/theme/PresetTheme;", "field",
     "standardLight", ("ColorScheme;",)),
    ("ColorScheme.surface", "Landroidx/compose/material3/ColorScheme;", "field", "surface", ("J",)),
    ("ColorScheme.surfaceContainerHigh", "Landroidx/compose/material3/ColorScheme;", "field", "surfaceContainerHigh", ("J",)),
    ("ColorScheme.onSurface", "Landroidx/compose/material3/ColorScheme;", "field", "onSurface", ("J",)),
    ("ColorScheme.onSurfaceVariant", "Landroidx/compose/material3/ColorScheme;", "field", "onSurfaceVariant", ("J",)),
    ("ColorScheme.outlineVariant", "Landroidx/compose/material3/ColorScheme;", "field", "outlineVariant", ("J",)),
    ("ColorScheme.primary", "Landroidx/compose/material3/ColorScheme;", "field", "primary", ("J",)),
    ("ColorScheme.onPrimary", "Landroidx/compose/material3/ColorScheme;", "field", "onPrimary", ("J",)),
    ("ColorScheme.primaryContainer", "Landroidx/compose/material3/ColorScheme;", "field", "primaryContainer", ("J",)),
    ("ColorScheme.onPrimaryContainer", "Landroidx/compose/material3/ColorScheme;", "field", "onPrimaryContainer", ("J",)),
    ("ColorScheme.secondaryContainer", "Landroidx/compose/material3/ColorScheme;", "field", "secondaryContainer", ("J",)),
    ("ColorScheme.onSecondaryContainer", "Landroidx/compose/material3/ColorScheme;", "field", "onSecondaryContainer", ("J",)),
    ("ColorScheme.error", "Landroidx/compose/material3/ColorScheme;", "field", "error", ("J",)),
    ("ColorScheme.onError", "Landroidx/compose/material3/ColorScheme;", "field", "onError", ("J",)),
    ("ColorScheme.errorContainer", "Landroidx/compose/material3/ColorScheme;", "field", "errorContainer", ("J",)),
    ("ColorScheme.onErrorContainer", "Landroidx/compose/material3/ColorScheme;", "field", "onErrorContainer", ("J",)),
    ("PresetTheme.standardDark", "Lme/rerere/rikkahub/ui/theme/PresetTheme;", "field",
     "standardDark", ("ColorScheme;",)),

    ("kotlin.uuid.Uuid", "Lkotlin/uuid/Uuid;", "class", None, None),
    ("Uuid#<init>(JJ)", "Lkotlin/uuid/Uuid;", "method", "<init>", ("(JJ)V",)),
    ("Uuid.mostSignificantBits", "Lkotlin/uuid/Uuid;", "field", "mostSignificantBits", ("J",)),
    ("Uuid.leastSignificantBits", "Lkotlin/uuid/Uuid;", "field", "leastSignificantBits", ("J",)),

    ("ContinuationImpl", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;", "class", None, None),
    ("ContinuationImpl#<init>", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;", "method",
     "<init>", ("Lkotlin/coroutines/Continuation;",)),
    ("BaseContinuationImpl#invokeSuspend", "Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;",
     "method", "invokeSuspend", ("Ljava/lang/Object;",)),
    ("EmptyCoroutineContext.INSTANCE", "Lkotlin/coroutines/EmptyCoroutineContext;", "field",
     "INSTANCE", ("EmptyCoroutineContext;",)),
    ("CoroutineSingletons.COROUTINE_SUSPENDED", "Lkotlin/coroutines/intrinsics/CoroutineSingletons;",
     "field", "COROUTINE_SUSPENDED", ("CoroutineSingletons;",)),
    ("kotlin.Result$Failure", "Lkotlin/Result$Failure;", "class", None, None),

    ("StateFlow#getValue", "Lkotlinx/coroutines/flow/StateFlow;", "method", "getValue",
     ("()Ljava/lang/Object;",)),
    ("Function0", "Lkotlin/jvm/functions/Function0;", "class", None, None),
    ("Function1", "Lkotlin/jvm/functions/Function1;", "class", None, None),

    ("RoomDatabase#getOpenHelper", "Landroidx/room/RoomDatabase;", "method", "getOpenHelper",
     ("SupportSQLiteOpenHelper;",)),
    ("RoomDatabase#runInTransaction", "Landroidx/room/RoomDatabase;", "method", "runInTransaction",
     ("Lkotlin/jvm/functions/Function0;",)),
    ("SupportSQLiteDatabase#query", "Landroidx/sqlite/db/SupportSQLiteDatabase;", "method", "query",
     ("Ljava/lang/String;", "Landroid/database/Cursor;")),
    ("SupportSQLiteDatabase#execSQL", "Landroidx/sqlite/db/SupportSQLiteDatabase;", "method",
     "execSQL", ("Ljava/lang/String;", "[Ljava/lang/Object;")),
    ("SupportSQLiteOpenHelper#getWritableDatabase", "Landroidx/sqlite/db/SupportSQLiteOpenHelper;",
     "method", "getWritableDatabase", ("SupportSQLiteDatabase;",)),
]


# 名字带混淆后缀的成员：按前缀 + 形参个数核对
PREFIX_REQUIRED = [
    ("TextKt#Text-(String)", "Landroidx/compose/material3/TextKt;", 20),
]


def _split_params(descriptor):
    """把 (Ljava/lang/String;IJZ) 这种描述符拆成形参列表。"""
    body = descriptor[1:descriptor.index(")")]
    params = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "L":
            end = body.index(";", index)
            params.append(body[index:end + 1])
            index = end + 1
        elif char == "[":
            index += 1
        else:
            params.append(char)
            index += 1
    return params


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("HOST_APK", DEFAULT_APK)
    if not os.path.isfile(apk):
        raise SystemExit(
            "找不到宿主 APK：%s\n"
            "把 RikkaHub 的 APK 放到仓库根目录（或设置 HOST_APK），再跑一次。\n"
            "下载地址：https://github.com/rikkahub/rikkahub/releases" % apk)
    if not os.path.isfile(DEXDUMP):
        raise SystemExit("找不到 dexdump：%s\n先跑 ./tools/fetch_toolchain.sh" % DEXDUMP)

    classes = {}
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            names = [name for name in archive.namelist() if re.match(r"classes\d*\.dex$", name)]
            if not names:
                raise SystemExit("APK 里没有 dex")
            for name in names:
                path = os.path.join(tmp, name.replace("/", "_"))
                with open(path, "wb") as handle:
                    handle.write(archive.read(name))
                parsed = parse_dex(path)
                for desc, members in parsed.items():
                    target = classes.setdefault(desc, {"field": [], "method": []})
                    target["field"].extend(members["field"])
                    target["method"].extend(members["method"])

    failures = []
    for label, desc, kind, name, fragments in REQUIRED:
        members = classes.get(desc)
        ok = False
        if members is None:
            detail = "类不存在"
        elif kind == "class":
            ok = True
            detail = ""
        else:
            detail = "没有匹配的成员"
            for member_name, member_type in members[kind]:
                if member_name != name:
                    continue
                if fragments and not all(fragment in member_type for fragment in fragments):
                    detail = "%s 的类型是 %s" % (name, member_type)
                    continue
                ok = True
                break
        print(("ok    " if ok else "FAIL  ") + label + ("" if ok else "  " + detail))
        if not ok:
            failures.append(label)

    for label, desc, param_count in PREFIX_REQUIRED:
        members = classes.get(desc)
        ok = False
        if members is not None:
            for member_name, member_type in members["method"]:
                if member_name.startswith("Text-") and member_type.startswith("(Ljava/lang/String;") \
                        and member_type.count(";") + member_type.count("[") >= 0 \
                        and len(_split_params(member_type)) == param_count:
                    ok = True
                    break
        print(("ok    " if ok else "FAIL  ") + label)
        if not ok:
            failures.append(label)

    print()
    print("宿主：%s" % apk)
    if failures:
        raise SystemExit("锚点缺失 %d 项" % len(failures))
    print("锚点全部存在（%d 项）" % (len(REQUIRED) + len(PREFIX_REQUIRED)))


if __name__ == "__main__":
    main()
