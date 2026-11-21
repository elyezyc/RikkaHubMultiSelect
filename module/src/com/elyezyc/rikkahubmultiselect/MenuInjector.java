/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 菜单里的「多选」，以及选中状态怎么落到宿主自己的列表上。
 *
 * <p>应用菜单实际调的是 {@code CardKt.DropdownMenuItem}（text 为 Function2 的经典重载），
 * 这里在那个函数上挂钩，往菜单最前面插一项「多选」。菜单的来源靠三条线索认：
 * 列表项 composable 组合时记下的类型、点击修饰符被点开时记下的目标、以及当前导航页。
 *
 * <p>选中动作不另开界面：行 composable 组合时按 {@link Selection} 的状态改参数——
 * 会话行用宿主自己的 {@code selected} 变色、点击改成切换选中；提供商 / 助手行点击改成切换，
 * 未选中的行整体压暗一点。
 */
final class MenuInjector {

    static final int NONE = -1;

    /** 点开菜单后这么久没画菜单项，就不再认这次点击。 */
    private static final long ARM_TTL_MS = 1500L;
    private static final int LAMBDA_KEY = 0x5A17E1;

    private static Class<?> function0;
    private static Class<?> function1;
    private static Class<?> function2;
    private static Class<?> lambdaImpl;
    private static Class<?> textKt;
    private static Class<?> menuItemColors;
    private static Class<?> iconKt;
    private static Class<?> mutableState;
    private static Method stateGet;
    private static Method stateSet;
    private static Method classicItem;
    private static Method expressiveItem;
    private static Method textMethod;
    private static Method iconMethod;
    private static Method popupMethod;
    private static Method alphaMethod;
    private static Constructor<?> lambdaCtor;
    private static Constructor<?> colorsCtor;
    private static Object unitInstance;
    private static Object emptyModifier;
    private static Object selectIcon;
    private static boolean iconAvailable;

    private static boolean resolved;
    private static boolean classicBroken;
    private static boolean expressiveBroken;

    /** 当前正在组合的列表项：类型 + 这一行的 id（进多选时顺手把它选上）。 */
    private static final class ItemRef {
        final int type;
        final String id;

        ItemRef(int type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    private static final ThreadLocal<ItemRef> ITEM = new ThreadLocal<>();

    private static volatile int armedTarget = NONE;
    private static volatile String armedId;
    private static volatile long armedAt;
    private static volatile int pendingTarget = NONE;
    private static volatile String pendingId;
    private static volatile long pendingAt;
    private static volatile Object pendingDismiss;
    private static volatile long dismissAt;
    /** 刚注入的那个菜单里，宿主自己那一项的点击回调（它会把菜单的展开状态设回 false）。 */
    private static volatile Object hostItemClick;
    /** 当前菜单的内容 lambda（里面也捕获了展开状态）。 */
    private static volatile Object contentLambda;
    private static volatile boolean injectedForPending;
    private static volatile boolean injectedOnce;

    private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<>();

    private MenuInjector() {
    }

    static boolean available() {
        return resolved && !classicBroken;
    }

    // ---------------------------------------------------------------- 安装

    static void install(ClassLoader loader) {
        try {
            function0 = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            function1 = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            lambdaImpl = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl",
                    false, loader);
            textKt = Class.forName("androidx.compose.material3.TextKt", false, loader);
            menuItemColors = Class.forName("androidx.compose.material3.MenuItemColors",
                    false, loader);
            iconKt = Class.forName("androidx.compose.material3.IconKt", false, loader);
            mutableState = Reflect.load(loader, "androidx.compose.runtime.MutableState");
            Class<?> state = Reflect.load(loader, "androidx.compose.runtime.State");
            if (mutableState != null) {
                stateSet = mutableState.getMethod("setValue", Object.class);
                stateSet.setAccessible(true);
            }
            if (state != null) {
                stateGet = state.getMethod("getValue");
                stateGet.setAccessible(true);
            }
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);

            unitInstance = Reflect.get(Reflect.field(unit, "INSTANCE"), null);
            lambdaCtor = lambdaImpl.getDeclaredConstructor(int.class, boolean.class, Object.class);
            lambdaCtor.setAccessible(true);
            colorsCtor = menuItemColors.getDeclaredConstructor(
                    long.class, long.class, long.class, long.class,
                    long.class, long.class, long.class, long.class);
            colorsCtor.setAccessible(true);

            textMethod = findTextMethod();
            iconMethod = findIconMethod();
            classicItem = findClassicItem();
            expressiveItem = findExpressiveItem();
            popupMethod = findPopupMethod();
            alphaMethod = findAlphaMethod();
            emptyModifier = findEmptyModifier();
            selectIcon = findSelectIcon();
            if (classicItem == null) {
                throw new NoSuchMethodException("找不到 CardKt#DropdownMenuItem");
            }

            hookConversationItem();
            hookProviderItem();
            hookAssistantItem();
            hookClickables();
            if (popupMethod != null) {
                hookPopup();
            }
            hookContent();
            hookMenuItem(classicItem, true);
            if (expressiveItem != null) {
                hookMenuItem(expressiveItem, false);
            }
            iconAvailable = iconMethod != null && selectIcon != null && emptyModifier != null;
            resolved = true;
            XLog.i("菜单注入已启用（经典菜单项：" + classicItem.getDeclaringClass().getSimpleName()
                    + "，expressive：" + (expressiveItem != null) + "，图标：" + (selectIcon != null) + "）");
        } catch (Throwable t) {
            resolved = false;
            XLog.e("菜单注入不可用（宿主可能改版了）", t);
        }
    }

    private static Method findTextMethod() throws NoSuchMethodException {
        for (Method method : textKt.getDeclaredMethods()) {
            if (!method.getName().startsWith("Text-")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 20
                    && types[0] == String.class
                    && types[16].getName().equals("androidx.compose.runtime.GapComposer")) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("TextKt#Text-");
    }

    private static Method findIconMethod() {
        try {
            for (Method method : iconKt.getDeclaredMethods()) {
                if (!method.getName().startsWith("Icon-")) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 7
                        && types[0].getName().equals("androidx.compose.ui.graphics.vector.ImageVector")
                        && types[4].getName().equals("androidx.compose.runtime.GapComposer")) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable t) {
            XLog.e("找 Icon 失败", t);
        }
        return null;
    }

    private static Method findClassicItem() {
        for (String className : new String[]{
                "androidx.compose.material3.CardKt",
                "androidx.compose.material3.MenuKt"}) {
            Class<?> type = Reflect.load(Host.loader(), className);
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("DropdownMenuItem")) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 11
                        && types[0] == function2
                        && types[1] == function0
                        && types[6] == menuItemColors) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findExpressiveItem() {
        Class<?> type = Reflect.load(Host.loader(), "androidx.compose.material3.MenuKt");
        if (type == null) {
            return null;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("DropdownMenuItem")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 11 && types[0] == function0 && types[1] == lambdaImpl) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findPopupMethod() {
        Class<?> type = Reflect.load(Host.loader(), "androidx.compose.material3.MenuKt");
        if (type == null) {
            return null;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("DropdownMenuPopup")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 8 && types[0] == boolean.class && types[1] == function0) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /** Modifier.alpha：给未选中的行压暗一点。R8 把文件类合并了，按候选类名找。 */
    private static Method findAlphaMethod() {
        String[] classes = {
                "contrast.Contrast",
                "androidx.compose.ui.draw.AlphaKt",
                "androidx.compose.ui.draw.DrawModifierKt",
        };
        for (String name : classes) {
            Class<?> type = Reflect.load(Host.loader(), name);
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals("alpha")
                        && types.length == 2
                        && types[0].getName().equals("androidx.compose.ui.Modifier")
                        && types[1] == float.class) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        XLog.i("找不到 Modifier.alpha，选中时不做压暗");
        return null;
    }

    private static Object findEmptyModifier() {
        Class<?> companion = Reflect.load(Host.loader(), "androidx.compose.ui.Modifier$Companion");
        if (companion == null) {
            return null;
        }
        Object value = Reflect.get(Reflect.field(companion, "$$INSTANCE"), null);
        if (value == null) {
            value = Reflect.get(Reflect.field(companion, "INSTANCE"), null);
        }
        return value;
    }

    /** 「多选」的图标：用宿主自带的 HugeIcons 里那个清单勾选图标。 */
    private static Object findSelectIcon() {
        String[][] candidates = {
                {"io.ktor.http.cio.HttpBodyKt", "getCheckList"},
                {"io.ktor.util.TextKt", "getTick01"},
                {"me.rerere.ai.core.UsageKt", "getMoreVertical"},
        };
        for (String[] candidate : candidates) {
            Class<?> type = Reflect.load(Host.loader(), candidate[0]);
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(candidate[1])
                        && method.getParameterCount() == 0
                        && method.getReturnType().getName()
                        .equals("androidx.compose.ui.graphics.vector.ImageVector")) {
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(null);
                        if (value != null) {
                            XLog.i("菜单图标取自 " + candidate[0] + "#" + candidate[1]);
                            return value;
                        }
                    } catch (Throwable t) {
                        XLog.e("取图标失败：" + candidate[0], t);
                    }
                }
            }
        }
        XLog.i("找不到可用的菜单图标");
        return null;
    }

    // ---------------------------------------------------------------- 列表项

    private static void hookConversationItem() {
        Class<?> type = Reflect.load(Host.loader(), "me.rerere.rikkahub.ui.pages.chat.ChatListKt");
        Method method = type == null ? null : findByName(type, "ConversationItem");
        if (method == null) {
            XLog.i("找不到 ConversationItem");
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Selection.observe();
                        Object conversation = param.args[0];
                        String id = String.valueOf(Reflect.property(conversation, "id"));
                        ITEM.set(new ItemRef(Selection.CONVERSATIONS, id));
                        // 0 会话，1 删除，2 重新生成标题，3 置顶，4 移助手，5 移文件夹
                        Selection.putRow(id, new Object[]{
                                conversation, param.args[4], param.args[5], param.args[6],
                                param.args[7], param.args[8]});
                        if (Selection.active(Selection.CONVERSATIONS)) {
                            param.args[1] = Selection.contains(id);
                            param.args[9] = wrapFunction1(param.args[9], id,
                                    Selection.CONVERSATIONS);
                        }
                    } catch (Throwable t) {
                        XLog.e("处理会话行失败", t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ITEM.remove();
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 ConversationItem 失败", t);
        }
    }

    private static void hookProviderItem() {
        Class<?> type = Reflect.load(Host.loader(),
                "me.rerere.rikkahub.ui.pages.setting.SettingProviderPageKt");
        Method method = type == null ? null : findByName(type, "ProviderItem");
        if (method == null) {
            XLog.i("找不到 ProviderItem");
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Selection.observe();
                        String id = SettingsApi.providerId(param.args[0]);
                        ITEM.set(new ItemRef(Selection.PROVIDERS, id));
                        if (!Selection.active(Selection.PROVIDERS)) {
                            return;
                        }
                        param.args[1] = dim(param.args[1], Selection.contains(id));
                        param.args[2] = wrapFunction0(param.args[2], id, Selection.PROVIDERS);
                    } catch (Throwable t) {
                        XLog.e("处理提供商行失败", t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ITEM.remove();
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 ProviderItem 失败", t);
        }
    }

    private static void hookAssistantItem() {
        Class<?> type = Reflect.load(Host.loader(),
                "me.rerere.rikkahub.ui.pages.assistant.AssistantPageKt");
        Method method = type == null ? null : findByName(type, "AssistantItem");
        if (method == null) {
            XLog.i("找不到 AssistantItem");
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Selection.observe();
                        String id = SettingsApi.assistantId(param.args[0]);
                        ITEM.set(new ItemRef(Selection.ASSISTANTS, id));
                        if (!Selection.active(Selection.ASSISTANTS)) {
                            return;
                        }
                        param.args[2] = dim(param.args[2], Selection.contains(id));
                        param.args[4] = wrapFunction0(param.args[4], id, Selection.ASSISTANTS);
                    } catch (Throwable t) {
                        XLog.e("处理助手行失败", t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ITEM.remove();
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 AssistantItem 失败", t);
        }
    }

    private static Method findByName(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Object dim(Object modifier, boolean selected) {
        if (selected) {
            return modifier;
        }
        if (alphaMethod == null) {
            return modifier;
        }
        try {
            Object dimmed = alphaMethod.invoke(null, modifier, 0.45f);
            if (dimmed != null) {
                return dimmed;
            }
        } catch (Throwable t) {
            XLog.e("压暗未选中的行失败", t);
        }
        return modifier;
    }

    // ---------------------------------------------------------------- 点击 -> 选中

    private static void hookClickables() {
        hookConstructor("androidx.compose.foundation.CombinedClickableElement", 4);
        hookConstructor("androidx.compose.foundation.ClickableElement", 6);
    }

    private static void hookConstructor(String className, final int candidateIndex) {
        Class<?> type = Reflect.load(Host.loader(), className);
        if (type == null) {
            XLog.i("找不到 " + className);
            return;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length <= candidateIndex || types[candidateIndex] != function0) {
                continue;
            }
            constructor.setAccessible(true);
            try {
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object original = param.args[candidateIndex];
                            ItemRef item = ITEM.get();
                            if (item != null && original != null) {
                                param.args[candidateIndex] = wrapArm(original, item);
                            }
                        } catch (Throwable t) {
                            XLog.e("包装点击回调失败", t);
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 " + className + " 失败", t);
            }
        }
    }

    private static Object wrapArm(final Object original, final ItemRef item) {
        return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"invoke".equals(method.getName())) {
                            return fallback(proxy, method, args);
                        }
                        arm(item.type, item.id);
                        Method call = function0.getMethod("invoke");
                        call.setAccessible(true);
                        return call.invoke(original);
                    }
                });
    }

    private static Object wrapFunction0(final Object original, final String id, final int target) {
        return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"invoke".equals(method.getName())) {
                            return fallback(proxy, method, args);
                        }
                        if (Selection.active(target)) {
                            Selection.toggle(id);
                            SelectionBar.refresh();
                            return null;
                        }
                        if (original == null) {
                            return null;
                        }
                        Method call = function0.getMethod("invoke");
                        call.setAccessible(true);
                        return call.invoke(original);
                    }
                });
    }

    private static Object wrapFunction1(final Object original, final String id, final int target) {
        return Proxy.newProxyInstance(function1.getClassLoader(), new Class<?>[]{function1},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"invoke".equals(method.getName())) {
                            return fallback(proxy, method, args);
                        }
                        if (Selection.active(target)) {
                            Selection.toggle(id);
                            SelectionBar.refresh();
                            return null;
                        }
                        if (original == null) {
                            return null;
                        }
                        Method call = function1.getMethod("invoke", Object.class);
                        call.setAccessible(true);
                        return call.invoke(original, args == null ? null : args[0]);
                    }
                });
    }

    private static void arm(int target, String id) {
        armedTarget = target;
        armedId = id;
        armedAt = System.currentTimeMillis();
        injectedForPending = false;
        XLog.i("点开" + tabName(target) + "菜单" + (id == null ? "" : "（" + id + "）"));
    }

    private static String tabName(int target) {
        switch (target) {
            case Selection.PROVIDERS:
                return T.s("提供商", "provider");
            case Selection.ASSISTANTS:
                return T.s("助手", "assistant");
            default:
                return T.s("对话", "conversation");
        }
    }

    private static void hookPopup() {
        try {
            XposedBridge.hookMethod(popupMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Boolean.TRUE.equals(param.args[0])) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        int target = NONE;
                        if (armedTarget != NONE && now - armedAt <= ARM_TTL_MS) {
                            target = armedTarget;
                        } else {
                            target = screenTarget();
                        }
                        if (target != NONE) {
                            pendingTarget = target;
                            pendingId = armedId;
                            pendingAt = now;
                            if (function0.isInstance(param.args[1])) {
                                pendingDismiss = param.args[1];
                                dismissAt = now;
                            }
                            armedTarget = NONE;
                            armedId = null;
                        }
                    } catch (Throwable t) {
                        XLog.e("识别菜单失败", t);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("挂 DropdownMenuPopup 失败", t);
        }
    }

    private static void hookContent() {
        Class<?> type = Reflect.load(Host.loader(), "androidx.compose.material3.MenuKt");
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.startsWith("DropdownMenuContent") && !name.startsWith("DropdownMenuPopup")) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        injectedForPending = false;
                        // 菜单内容组合时把 onDismissRequest 记住，点「多选」以后用它关菜单
                        if (param.args != null) {
                            for (int i = 1; i < param.args.length; i++) {
                                if (function0.isInstance(param.args[i])
                                        && pendingDismiss != param.args[i]) {
                                    pendingDismiss = param.args[i];
                                    dismissAt = System.currentTimeMillis();
                                }
                                if (lambdaImpl.isInstance(param.args[i])) {
                                    contentLambda = param.args[i];
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 DropdownMenuContent 失败", t);
            }
        }
    }

    private static int screenTarget() {
        String screen = Host.currentScreen();
        if (screen == null) {
            return NONE;
        }
        if (screen.endsWith("$SettingProvider")) {
            return Selection.PROVIDERS;
        }
        if (screen.endsWith("$Assistant")) {
            return Selection.ASSISTANTS;
        }
        return NONE;
    }

    // ---------------------------------------------------------------- 注入菜单项

    private static void hookMenuItem(final Method method, final boolean classic) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (classic) {
                            injectClassic(param);
                        } else {
                            injectExpressive(param);
                        }
                    } catch (Throwable t) {
                        if (classic) {
                            classicBroken = true;
                        } else {
                            expressiveBroken = true;
                        }
                        XLog.e("注入菜单项失败（" + (classic ? "经典" : "expressive")
                                + "重载，后续不再尝试）", t);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 DropdownMenuItem 失败", t);
        }
    }

    private static boolean consumePending() {
        if (Boolean.TRUE.equals(REENTRANT.get())) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 同一个菜单里只插一次；但不要因为某次没重置标记就把后面所有菜单都挡掉
        if (injectedForPending && now - pendingAt < 700) {
            return false;
        }
        if (pendingTarget == NONE) {
            int target = NONE;
            if (armedTarget != NONE && now - armedAt <= ARM_TTL_MS) {
                target = armedTarget;
            } else {
                target = screenTarget();
            }
            if (target == NONE) {
                return false;
            }
            pendingTarget = target;
            pendingId = armedId;
            pendingAt = now;
            armedTarget = NONE;
            armedId = null;
        }
        if (now - pendingAt > ARM_TTL_MS) {
            pendingTarget = NONE;
            return false;
        }
        return true;
    }

    private static void injectClassic(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (classicBroken) {
            return;
        }
        if (!consumePending() && !consumeByMenuContent(param.args[0])) {
            return;
        }
        injectedForPending = true;
        Object[] args = param.args;
        hostItemClick = args[1];
        final boolean[] iconDrawn = new boolean[1];
        Object text = buildTextLambda(false, iconDrawn);
        Object click = buildClickLambda(pendingTarget);
        Object icon = buildIconLambda(iconDrawn);
        Object colors = defaultColors();
        if (colors == null) {
            colors = args[6];
        }
        Object[] call = new Object[]{
                text,                    // text
                click,                   // onClick
                args[2],                 // modifier
                icon,                    // leadingIcon
                null,                    // trailingIcon
                Boolean.TRUE,            // enabled
                colors,                  // colors
                args[7],                 // contentPadding
                args[8],                 // composer
                0,                       // changed
                0,                       // changed
        };
        REENTRANT.set(Boolean.TRUE);
        try {
            classicItem.invoke(null, call);
        } finally {
            REENTRANT.set(null);
        }
        markInjected();
    }

    private static void injectExpressive(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (expressiveBroken) {
            return;
        }
        if (!consumePending() && !consumeByMenuContent(param.args[1])) {
            return;
        }
        injectedForPending = true;
        Object[] args = param.args;
        hostItemClick = args[1];
        final boolean[] iconDrawn = new boolean[1];
        Object text = buildTextLambda(true, iconDrawn);
        Object click = buildClickLambda(pendingTarget);
        Object icon = buildIconLambda(iconDrawn);
        Object colors = defaultColors();
        if (colors == null) {
            colors = args[6];
        }
        Object[] call = new Object[]{
                click,                   // onClick
                text,                    // text (ComposableLambdaImpl)
                args[2],                 // shape
                args[3],                 // modifier
                icon,                    // leadingIcon
                Boolean.TRUE,            // enabled
                colors,                  // colors
                args[7],                 // arrangement
                args[8],                 // contentPadding
                args[9],                 // composer
                0,                       // changed
        };
        REENTRANT.set(Boolean.TRUE);
        try {
            expressiveItem.invoke(null, call);
        } finally {
            REENTRANT.set(null);
        }
        markInjected();
    }

    /**
     * 兜底：这次点的菜单项来自 {@code ItemActionMenu}（提供商 / 助手列表用的就是它），
     * 而当前页面正好是提供商或助手页，那就认定是这个列表的菜单。
     */
    private static boolean consumeByMenuContent(Object textLambda) {
        if (textLambda == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (injectedForPending && now - pendingAt < 700) {
            return false;
        }
        if (!textLambda.getClass().getName().contains("ItemActionMenuKt")) {
            return false;
        }
        int target = screenTarget();
        if (target == NONE) {
            return false;
        }
        pendingTarget = target;
        pendingId = null;
        pendingAt = System.currentTimeMillis();
        XLog.i("按菜单内容识别到" + tabName(target) + "菜单");
        return true;
    }

    private static void markInjected() {
        injectedOnce = true;
        pendingAt = System.currentTimeMillis();
        XLog.i("已在菜单里插入多选");
    }

    private static Object defaultColors() {
        if (colorsCtor == null) {
            return null;
        }
        try {
            Palette palette = Palette.current();
            if (palette == null) {
                Activity activity = Host.currentActivity();
                if (activity != null) {
                    palette = Palette.resolve(activity);
                }
            }
            long color = pack(palette == null ? 0xFF1D1B20 : palette.onSurface);
            return colorsCtor.newInstance(color, color, color, color, color, color, color, color);
        } catch (Throwable t) {
            XLog.i("拼菜单项颜色失败：" + t);
            return null;
        }
    }

    private static long pack(int argb) {
        return ((long) argb & 0xFFFFFFFFL) << 32;
    }

    private static Object unit() {
        return unitInstance;
    }

    // ---------------------------------------------------------------- lambda

    private static Object buildTextLambda(boolean expressive, final boolean[] iconDrawn)
            throws Throwable {
        Object block = Proxy.newProxyInstance(function2.getClassLoader(), new Class<?>[]{function2},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("invoke".equals(method.getName()) && args != null && args.length >= 2) {
                            drawText(args[0], iconDrawn);
                            return unit();
                        }
                        return fallback(proxy, method, args);
                    }
                });
        return expressive ? lambdaCtor.newInstance(LAMBDA_KEY, false, block) : block;
    }

    private static Object buildIconLambda(final boolean[] iconDrawn) {
        if (iconMethod == null || selectIcon == null || emptyModifier == null) {
            return null;
        }
        return Proxy.newProxyInstance(function2.getClassLoader(), new Class<?>[]{function2},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("invoke".equals(method.getName()) && args != null && args.length >= 2) {
                            try {
                                Palette palette = Palette.current();
                                int tint = palette == null ? 0xFF49454F : palette.onSurfaceVariant;
                                iconMethod.invoke(null, selectIcon, null, emptyModifier, pack(tint),
                                        args[0], 0, 0);
                                iconDrawn[0] = true;
                            } catch (Throwable t) {
                                XLog.e("画菜单图标失败", t);
                            }
                            return unit();
                        }
                        return fallback(proxy, method, args);
                    }
                });
    }

    /** 图标真画出来了就不加字符；图标没出来（或者宿主没调 leadingIcon）就用 ✓ 顶上。 */
    private static void drawText(Object composer, boolean[] iconDrawn) throws Throwable {
        Object[] args = new Object[20];
        boolean withGlyph = iconDrawn == null || !iconDrawn[0];
        args[0] = withGlyph ? T.s("✓ 多选", "✓ Select") : T.s("多选", "Select");
        args[1] = null;
        args[2] = 0L;
        args[3] = null;
        args[4] = 0L;
        args[5] = null;
        args[6] = null;
        args[7] = 0L;
        args[8] = null;
        args[9] = null;
        args[10] = 0L;
        args[11] = 0;
        args[12] = Boolean.FALSE;
        args[13] = 0;
        args[14] = 0;
        args[15] = null;
        args[16] = composer;
        args[17] = 0;
        args[18] = 0;
        args[19] = 0xFFFFE;
        textMethod.invoke(null, args);
    }

    private static Object buildClickLambda(final int target) {
        return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"invoke".equals(method.getName())) {
                            return fallback(proxy, method, args);
                        }
                        final Activity activity = Host.currentActivity();
                        final Object dismiss = pendingDismiss;
                        final String preselected = pendingId;
                        pendingTarget = NONE;
                        pendingId = null;
                        pendingDismiss = null;
                        if (activity == null) {
                            XLog.i("点了多选，但拿不到 Activity");
                            return null;
                        }
                        boolean closed = false;
                        // 1) 刚刚捕获到的 onDismissRequest
                        boolean fresh = dismiss != null
                                && System.currentTimeMillis() - dismissAt < 3000L;
                        try {
                            if (fresh) {
                                Method call = function0.getMethod("invoke");
                                call.setAccessible(true);
                                call.invoke(dismiss);
                                closed = true;
                            }
                        } catch (Throwable t) {
                            XLog.e("关闭宿主菜单失败", t);
                        }
                        // 2) 直接找到宿主菜单的展开状态，设回 false（宿主自己点菜单项就是干这个）
                        closed = flipMenuState(hostItemClick) || closed;
                        closed = flipMenuState(contentLambda) || closed;
                        closed = flipMenuState(dismiss) || closed;
                        // 3) 都不行就发返回键
                        if (!closed) {
                            backLater(activity);
                        }
                        // 4) 250ms 后再补一次（有些菜单的组合状态要晚一帧才稳定）
                        final boolean allowRetry = true;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                flipMenuState(hostItemClick);
                                flipMenuState(contentLambda);
                            }
                        }, 250L);
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    SelectionBar.hideIfIdle();
                                    Selection.enter(target);
                                    if (preselected != null) {
                                        Selection.toggle(preselected);
                                    }
                                    SelectionBar.show(activity);
                                } catch (Throwable t) {
                                    XLog.e("进入多选失败", t);
                                }
                            }
                        });
                        return null;
                    }
                });
    }

    /** 在给定对象的字段里找 Compose 的 MutableState&lt;Boolean&gt;，把它设成 false。 */
    private static boolean flipMenuState(Object holder) {
        if (holder == null || mutableState == null || stateSet == null || stateGet == null) {
            return false;
        }
        if (flipFields(holder)) {
            return true;
        }
        // ComposableLambdaImpl 捕获的东西在 _block 里
        Object block = Reflect.get(Reflect.field(holder.getClass(), "_block"), holder);
        return block != null && flipFields(block);
    }

    private static boolean flipFields(Object holder) {
        if (holder == null) {
            return false;
        }
        for (Class<?> type = holder.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value == null || !mutableState.isInstance(value)) {
                        continue;
                    }
                    Object current = stateGet.invoke(value);
                    if (current instanceof Boolean && (Boolean) current) {
                        stateSet.invoke(value, Boolean.FALSE);
                        XLog.i("已把宿主菜单的展开状态设回 false");
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 继续看下一个字段
                }
            }
        }
        return false;
    }

    /** 拿不到 onDismissRequest 时的兜底：让 Compose 的 Popup 自己走一次返回，把它关掉。 */
    private static void backLater(final Activity activity) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object dispatcher = activity.getClass()
                            .getMethod("getOnBackPressedDispatcher").invoke(activity);
                    if (dispatcher != null) {
                        dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
                        XLog.i("已发送返回键关闭菜单");
                    }
                } catch (Throwable t) {
                    XLog.e("关闭菜单（返回键）失败", t);
                }
            }
        }, 120L);
    }

    private static Object fallback(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "RikkaHubMultiSelect$MenuLambda";
            default:
                return null;
        }
    }
}
