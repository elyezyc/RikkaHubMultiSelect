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
 * 把「多选」塞进 RikkaHub 自己的菜单里。
 *
 * <p>RikkaHub 的菜单是 Compose 的 {@code DropdownMenu}。R8 之后真实调用链是这样的：
 * 列表项 composable（{@code ChatListKt.ConversationItem} / {@code SettingProviderPageKt.ProviderItem} /
 * {@code AssistantPageKt.AssistantItem}，都还在）里构造点击修饰符，点开菜单后由
 * <b>{@code androidx.compose.material3.CardKt.DropdownMenuItem}</b>（经典版，text 是 Function2）
 * 逐项画菜单项——{@code MenuKt} 里那个 expressive 重载应用菜单根本不用。
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>在列表项组合时把「这是哪个列表的项」放进 ThreadLocal，再包一层点击回调：
 *       长按 / 点三点时记下目标，菜单项一组合就把「多选」插到最前面。</li>
 *   <li>「多选」这一项用宿主自己的 {@code DropdownMenuItem} 画，颜色、内边距、文字样式
 *       都沿用同菜单的其它项，看起来和原版一致。</li>
 * </ol>
 *
 * <p>签名在启动时逐项校验，任何一项对不上就整体关掉（退回悬浮按钮），不会在组合过程中炸宿主。
 */
final class MenuInjector {

    static final int NONE = -1;
    static final int CONVERSATIONS = 0;
    static final int PROVIDERS = 1;
    static final int ASSISTANTS = 2;

    /** 点开菜单后这么久没画菜单项，就不再认这次点击。 */
    private static final long ARM_TTL_MS = 1500L;

    /** 注入项在组合里的 key（expressive 重载要用 ComposableLambdaImpl）。 */
    private static final int LAMBDA_KEY = 0x5A17E1;

    private static Class<?> function0;
    private static Class<?> function2;
    private static Class<?> lambdaImpl;
    private static Class<?> textKt;
    private static Class<?> menuItemColors;
    private static Method classicItem;
    private static Method expressiveItem;
    private static Method textMethod;
    private static Method popupMethod;
    private static Constructor<?> lambdaCtor;
    private static Constructor<?> colorsCtor;
    private static Object unitInstance;

    private static boolean resolved;
    private static boolean broken;

    /** 当前正在组合的列表项类型（由 item composable 的钩子设置）。 */
    private static final ThreadLocal<Integer> ITEM = new ThreadLocal<>();

    private static volatile int armedTarget = NONE;
    private static volatile long armedAt;
    private static volatile int pendingTarget = NONE;
    private static volatile long pendingAt;
    private static volatile Object pendingDismiss;
    private static volatile boolean injectedForPending;
    private static volatile boolean injectedOnce;
    private static int debugDumps;

    private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<>();

    private MenuInjector() {
    }

    static boolean available() {
        return resolved && !broken;
    }

    static boolean hasInjected() {
        return injectedOnce;
    }

    // ---------------------------------------------------------------- 安装

    static void install(ClassLoader loader) {
        try {
            function0 = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            lambdaImpl = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl",
                    false, loader);
            textKt = Class.forName("androidx.compose.material3.TextKt", false, loader);
            menuItemColors = Class.forName("androidx.compose.material3.MenuItemColors",
                    false, loader);
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);

            unitInstance = Reflect.get(Reflect.field(unit, "INSTANCE"), null);
            lambdaCtor = lambdaImpl.getDeclaredConstructor(int.class, boolean.class, Object.class);
            lambdaCtor.setAccessible(true);
            colorsCtor = menuItemColors.getDeclaredConstructor(
                    long.class, long.class, long.class, long.class,
                    long.class, long.class, long.class, long.class);
            colorsCtor.setAccessible(true);

            textMethod = findTextMethod();
            classicItem = findClassicItem();
            expressiveItem = findExpressiveItem();
            popupMethod = findPopupMethod();
            if (classicItem == null) {
                throw new NoSuchMethodException("找不到 CardKt#DropdownMenuItem");
            }

            hookItemType("me.rerere.rikkahub.ui.pages.chat.ChatListKt", "ConversationItem",
                    CONVERSATIONS);
            hookItemType("me.rerere.rikkahub.ui.pages.setting.SettingProviderPageKt", "ProviderItem",
                    PROVIDERS);
            hookItemType("me.rerere.rikkahub.ui.pages.assistant.AssistantPageKt", "AssistantItem",
                    ASSISTANTS);

            hookClickables();
            if (popupMethod != null) {
                hookPopup();
            }
            hookContent();
            hookMenuItem(classicItem, true);
            if (expressiveItem != null) {
                hookMenuItem(expressiveItem, false);
            }
            resolved = true;
            XLog.i("菜单注入已启用（经典菜单项：" + classicItem.getDeclaringClass().getSimpleName()
                    + "，expressive：" + (expressiveItem != null) + "）");
        } catch (Throwable t) {
            resolved = false;
            XLog.e("菜单注入不可用，退回悬浮按钮", t);
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
                    && types[16].getName().equals("androidx.compose.runtime.GapComposer")
                    && types[17] == int.class && types[18] == int.class && types[19] == int.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("TextKt#Text-");
    }

    /** 应用菜单真正用的那个：text 在前、onClick 在后、没有 Shape / Arrangement。 */
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
                        && types[5] == boolean.class
                        && types[6] == menuItemColors) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /** Material Expressive 那个重载，个别菜单可能在用。 */
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
            if (types.length == 11
                    && types[0] == function0
                    && types[1] == lambdaImpl
                    && types[4] == function2) {
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
            if (types.length == 8
                    && types[0] == boolean.class
                    && types[1] == function0
                    && types[5] == lambdaImpl) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 标记列表项

    private static void hookItemType(String className, String methodName, final int target) {
        Class<?> type = Reflect.load(Host.loader(), className);
        if (type == null) {
            XLog.i("找不到 " + className);
            return;
        }
        Method method = null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            XLog.i("找不到 " + className + "#" + methodName);
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ITEM.set(target);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ITEM.remove();
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 " + className + "#" + methodName + " 失败", t);
        }
    }

    // ---------------------------------------------------------------- 点击时记下目标

    private static void hookClickables() {
        hookConstructor("androidx.compose.foundation.CombinedClickableElement", new int[]{4}, true);
        hookConstructor("androidx.compose.foundation.ClickableElement", new int[]{6}, false);
    }

    /**
     * 包一层点击 / 长按回调。
     *
     * @param wrapLongClick true 包 onLongClick（会话行），false 包 onClick（三点按钮）
     */
    private static void hookConstructor(String className, final int[] indexes,
                                        final boolean wrapLongClick) {
        Class<?> type = Reflect.load(Host.loader(), className);
        if (type == null) {
            XLog.i("找不到 " + className);
            return;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            int index = -1;
            for (int candidate : indexes) {
                if (types.length > candidate && types[candidate] == function0) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                continue;
            }
            final int slot = index;
            constructor.setAccessible(true);
            try {
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object original = param.args[slot];
                            if (!(original instanceof Proxy) || !isOurProxy(original)) {
                                Integer item = ITEM.get();
                                if (item != null) {
                                    param.args[slot] = wrap(original, item);
                                }
                            }
                        } catch (Throwable t) {
                            XLog.e("包装点击回调失败", t);
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 " + className + " 构造器失败", t);
            }
        }
    }

    private static boolean isOurProxy(Object value) {
        return value.getClass().getName().contains("$Proxy");
    }

    private static Object wrap(final Object original, final int target) {
        return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"invoke".equals(method.getName())) {
                            return fallback(proxy, method, args);
                        }
                        arm(target);
                        if (original == null) {
                            return null;
                        }
                        Method call = function0.getMethod("invoke");
                        call.setAccessible(true);
                        return call.invoke(original);
                    }
                });
    }

    private static void arm(int target) {
        armedTarget = target;
        armedAt = System.currentTimeMillis();
        injectedForPending = false;
        XLog.i("点开" + tabName(target) + "菜单");
    }

    /** 菜单内容每组合一次就把「本轮已经插过」清掉，这样重组合时结构不会错位。 */
    private static void hookContent() {
        Class<?> type = Reflect.load(Host.loader(), "androidx.compose.material3.MenuKt");
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().startsWith("DropdownMenuContent")) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        injectedForPending = false;
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 DropdownMenuContent 失败", t);
            }
        }
    }

    /** 提供商 / 助手页面上的菜单一般就是列表项的菜单，可以按当前页面兜底认一下。 */
    private static int screenTarget() {
        String screen = Host.currentScreen();
        if (screen == null) {
            return NONE;
        }
        if (screen.endsWith("$SettingProvider")) {
            return PROVIDERS;
        }
        if (screen.endsWith("$Assistant")) {
            return ASSISTANTS;
        }
        return NONE;
    }

    private static String tabName(int target) {
        switch (target) {
            case PROVIDERS:
                return T.s("提供商", "provider");
            case ASSISTANTS:
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
                            pendingAt = now;
                            pendingDismiss = param.args[1];
                            armedTarget = NONE;
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
                        broken = true;
                        XLog.e("注入菜单项失败，后续不再尝试（重启 RikkaHub 后重试）", t);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("挂钩 DropdownMenuItem 失败", t);
        }
    }

    private static boolean consumePending() {
        if (broken || Boolean.TRUE.equals(REENTRANT.get()) || injectedForPending) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (pendingTarget == NONE) {
            // 没等到 popup 也算：点击后紧接着组合的第一个菜单项就是它；
            // 提供商 / 助手页还可以直接按当前页面认。
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
            pendingAt = now;
            armedTarget = NONE;
        }
        if (now - pendingAt > ARM_TTL_MS) {
            pendingTarget = NONE;
            return false;
        }
        return true;
    }

    private static void injectClassic(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (!consumePending()) {
            return;
        }
        injectedForPending = true;
        Object[] args = param.args;
        Object text = buildTextLambda(false);
        Object click = buildClickLambda(pendingTarget);
        Object colors = defaultColors();
        if (colors == null) {
            colors = args[6];
        }
        Object[] call = new Object[]{
                text,                    // text
                click,                   // onClick
                args[2],                 // modifier
                null,                    // leadingIcon
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
        injectedOnce = true;
        pendingAt = System.currentTimeMillis();
        XLog.i("已在" + tabName(pendingTarget) + "菜单里插入多选");
        FloatingButton.detach(Host.currentActivity());
    }

    private static void injectExpressive(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (!consumePending()) {
            return;
        }
        injectedForPending = true;
        Object[] args = param.args;
        Object text = buildTextLambda(true);
        Object click = buildClickLambda(pendingTarget);
        Object colors = defaultColors();
        if (colors == null) {
            colors = args[6];
        }
        Object[] call = new Object[]{
                click,                   // onClick
                text,                    // text (ComposableLambdaImpl)
                args[2],                 // shape
                args[3],                 // modifier
                null,                    // leadingIcon
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
        injectedOnce = true;
        FloatingButton.detach(Host.currentActivity());
    }

    /** 菜单项颜色：跟面板用同一套（一般就是 onSurface / onSurfaceVariant）。 */
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

    // ---------------------------------------------------------------- lambda

    private static Object buildTextLambda(boolean expressive) throws Throwable {
        Object block = Proxy.newProxyInstance(function2.getClassLoader(), new Class<?>[]{function2},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("invoke".equals(method.getName()) && args != null && args.length >= 2) {
                            drawText(args[0]);
                            return unitInstance;
                        }
                        return fallback(proxy, method, args);
                    }
                });
        if (expressive) {
            return lambdaCtor.newInstance(LAMBDA_KEY, false, block);
        }
        return block;
    }

    private static void drawText(Object composer) throws Throwable {
        Object[] args = new Object[20];
        args[0] = T.s("多选", "Select");
        args[1] = null;           // modifier
        args[2] = 0L;             // color
        args[3] = null;           // autoSize
        args[4] = 0L;             // fontSize
        args[5] = null;           // fontWeight
        args[6] = null;           // fontFamily
        args[7] = 0L;             // letterSpacing
        args[8] = null;           // textDecoration
        args[9] = null;           // textAlign
        args[10] = 0L;            // lineHeight
        args[11] = 0;             // overflow
        args[12] = Boolean.FALSE; // softWrap
        args[13] = 0;             // maxLines
        args[14] = 0;             // minLines
        args[15] = null;          // style
        args[16] = composer;
        args[17] = 0;             // changed
        args[18] = 0;             // changed
        // 除文字外全部取默认值。这里按「每个形参一位」和「每个槽位一位」（宽形参占两位）两种
        // 约定都能覆盖：低 16 位全是 1，超出部分对只有 16 个形参的函数无意义。
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
                        pendingTarget = NONE;
                        pendingDismiss = null;
                        if (activity == null) {
                            XLog.i("点了多选，但拿不到 Activity");
                            return null;
                        }
                        try {
                            if (dismiss != null) {
                                Method call = function0.getMethod("invoke");
                                call.setAccessible(true);
                                call.invoke(dismiss);
                            }
                        } catch (Throwable t) {
                            XLog.e("关闭宿主菜单失败", t);
                        }
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    MultiSelectDialog.show(activity, target);
                                } catch (Throwable t) {
                                    XLog.e("打开多选面板失败", t);
                                }
                            }
                        });
                        return null;
                    }
                });
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
