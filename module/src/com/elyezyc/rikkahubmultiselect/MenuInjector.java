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
 * <p>RikkaHub 的长按菜单、提供商和助手的右侧三点菜单都是 Compose 的 {@code DropdownMenu}，
 * 而 R8 把 {@code ItemActionMenu} 之类的封装全内联掉了，没有可以改列表的入口。所以这里换了个办法：
 *
 * <ol>
 *   <li>挂 {@code MenuKt.DropdownMenuPopup}（每个下拉菜单打开时调用一次，调用栈还在打开菜单的
 *       composable 里）。从栈上认出这是哪个列表的菜单：{@code ChatListKt.ConversationItem}、
 *       {@code SettingProviderPageKt.ProviderItem}、{@code AssistantPageKt.AssistantItem}。</li>
 *   <li>记下这个菜单的 {@code onDismissRequest}，等菜单的第一项开始组合时，用宿主自己的
 *       {@code DropdownMenuItem} 先画一项「多选」——形状、颜色、内边距、文字样式都直接用宿主那一项的，
 *       所以看起来和原版菜单完全一致。</li>
 *   <li>点了之后先关掉菜单，再打开多选面板。</li>
 * </ol>
 *
 * <p>宿主换了 Compose 版本就可能对不上：签名在安装时逐项校验，对不上就整体关掉（退回悬浮按钮），
 * 不会半途炸掉宿主。
 */
final class MenuInjector {

    static final int NONE = -1;
    static final int CONVERSATIONS = 0;
    static final int PROVIDERS = 1;
    static final int ASSISTANTS = 2;

    /** 注入的菜单项在组合里的 key，固定即可（同一时刻只会有一项）。 */
    private static final int LAMBDA_KEY = 0x5A17E1;

    /** 菜单打开后这么久还没开始画菜单项，就不再认为是它了。 */
    private static final long PENDING_TTL_MS = 3000L;

    private static Class<?> menuKt;
    private static Class<?> textKt;
    private static Class<?> lambdaImpl;
    private static Class<?> function0;
    private static Class<?> function2;
    private static Constructor<?> lambdaCtor;
    private static Method popupMethod;
    private static Method itemMethod;
    private static Method textMethod;
    private static Object unitInstance;

    private static boolean resolved;
    private static boolean broken;

    private static int pendingTarget = NONE;
    private static long pendingAt;
    private static Object pendingDismiss;
    private static boolean injectedForPending;
    private static boolean injectedOnce;
    private static int debugDumps;

    private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<>();

    private MenuInjector() {
    }

    static boolean available() {
        return resolved && !broken;
    }

    /** 是否已经成功往宿主菜单里加过项（成功过就不再挂兜底按钮）。 */
    static boolean hasInjected() {
        return injectedOnce;
    }

    // ---------------------------------------------------------------- 安装

    static void install(ClassLoader loader) {
        try {
            menuKt = Class.forName("androidx.compose.material3.MenuKt", false, loader);
            textKt = Class.forName("androidx.compose.material3.TextKt", false, loader);
            lambdaImpl = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl",
                    false, loader);
            function0 = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);

            unitInstance = Reflect.get(Reflect.field(unit, "INSTANCE"), null);
            lambdaCtor = lambdaImpl.getDeclaredConstructor(int.class, boolean.class, Object.class);
            lambdaCtor.setAccessible(true);

            popupMethod = findPopupMethod();
            itemMethod = findItemMethod();
            textMethod = findTextMethod();

            checkItemSignature();
            checkTextSignature();

            XposedBridge.hookMethod(popupMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        rememberMenu(param);
                    } catch (Throwable t) {
                        XLog.e("识别菜单失败", t);
                    }
                }
            });
            XposedBridge.hookMethod(itemMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        injectIfNeeded(param);
                    } catch (Throwable t) {
                        broken = true;
                        XLog.e("注入菜单项失败，后续不再尝试（重启 RikkaHub 后重试）", t);
                    }
                }
            });
            resolved = true;
            XLog.i("菜单注入已启用");
        } catch (Throwable t) {
            resolved = false;
            XLog.e("菜单注入不可用，退回悬浮按钮", t);
        }
    }

    private static Method findPopupMethod() throws NoSuchMethodException {
        for (Method method : menuKt.getDeclaredMethods()) {
            if (!method.getName().equals("DropdownMenuPopup")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 8
                    && types[1] == function0
                    && types[5] == lambdaImpl
                    && types[6].getName().equals("androidx.compose.runtime.GapComposer")) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("MenuKt#DropdownMenuPopup");
    }

    private static Method findItemMethod() throws NoSuchMethodException {
        for (Method method : menuKt.getDeclaredMethods()) {
            if (!method.getName().equals("DropdownMenuItem")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 11
                    && types[0] == function0
                    && types[1] == lambdaImpl
                    && types[4] == function2
                    && types[9].getName().equals("androidx.compose.runtime.GapComposer")) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("MenuKt#DropdownMenuItem");
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

    private static void checkItemSignature() throws NoSuchMethodException {
        Class<?>[] types = itemMethod.getParameterTypes();
        String[] expected = {
                "kotlin.jvm.functions.Function0",
                "androidx.compose.runtime.internal.ComposableLambdaImpl",
                "androidx.compose.ui.graphics.Shape",
                "androidx.compose.ui.Modifier",
                "kotlin.jvm.functions.Function2",
                "boolean",
                "androidx.compose.material3.MenuItemColors",
                "androidx.compose.foundation.layout.Arrangement$Horizontal",
                "androidx.compose.foundation.layout.PaddingValues",
                "androidx.compose.runtime.GapComposer",
                "int",
        };
        for (int i = 0; i < expected.length; i++) {
            if (!types[i].getName().equals(expected[i])) {
                throw new NoSuchMethodException("DropdownMenuItem 第 " + i + " 个参数是 "
                        + types[i].getName() + "，期望 " + expected[i]);
            }
        }
    }

    private static void checkTextSignature() throws NoSuchMethodException {
        Class<?>[] types = textMethod.getParameterTypes();
        if (types[0] != String.class
                || !types[types.length - 4].getName().equals("androidx.compose.runtime.GapComposer")
                || types[types.length - 3] != int.class
                || types[types.length - 2] != int.class
                || types[types.length - 1] != int.class) {
            throw new NoSuchMethodException("Text 的签名和预期不一致");
        }
    }

    // ---------------------------------------------------------------- 识别菜单

    private static void rememberMenu(XC_MethodHook.MethodHookParam param) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int target = detectTarget(stack);
        if (target == NONE) {
            pendingTarget = NONE;
            pendingDismiss = null;
            if (debugDumps < 8) {
                debugDumps++;
                XLog.i("菜单未识别（第 " + debugDumps + " 次）：" + topFrames(stack, 6));
            }
            return;
        }
        pendingTarget = target;
        pendingAt = System.currentTimeMillis();
        pendingDismiss = param.args[1];
        injectedForPending = false;
        XLog.i("识别到" + tabName(target) + "菜单");
    }

    /** 打开菜单的 composable 还留在栈上，按方法名认人。 */
    private static int detectTarget(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            String type = element.getClassName();
            String method = element.getMethodName();
            if (method.equals("ConversationItem") && type.endsWith("ChatListKt")) {
                return CONVERSATIONS;
            }
            if (method.equals("ProviderItem") && type.endsWith("SettingProviderPageKt")) {
                return PROVIDERS;
            }
            if (method.equals("AssistantItem") && type.endsWith("AssistantPageKt")) {
                return ASSISTANTS;
            }
        }
        return NONE;
    }

    private static String topFrames(StackTraceElement[] stack, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < stack.length && i < count; i++) {
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
        }
        return builder.toString();
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

    // ---------------------------------------------------------------- 注入

    private static void injectIfNeeded(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (!available() || Boolean.TRUE.equals(REENTRANT.get())) {
            return;
        }
        if (pendingTarget == NONE || injectedForPending) {
            return;
        }
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) {
            pendingTarget = NONE;
            return;
        }
        injectedForPending = true;

        Object text = buildTextLambda();
        Object onClick = buildClickLambda(pendingTarget);
        Object[] args = new Object[]{
                onClick,
                text,
                param.args[2],   // shape
                param.args[3],   // modifier
                null,            // leadingIcon
                Boolean.TRUE,    // enabled
                param.args[6],   // colors
                param.args[7],   // arrangement
                param.args[8],   // contentPadding
                param.args[9],   // composer
                0,               // changed
        };
        REENTRANT.set(Boolean.TRUE);
        try {
            itemMethod.invoke(null, args);
            injectedOnce = true;
        } finally {
            REENTRANT.set(null);
        }
        FloatingButton.detach(Host.currentActivity());
    }

    /** 菜单项的文字：「多选」。样式走宿主菜单项自己的 LocalTextStyle / LocalContentColor。 */
    private static Object buildTextLambda() throws Throwable {
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
        return lambdaCtor.newInstance(LAMBDA_KEY, false, block);
    }

    private static void drawText(Object composer) throws Throwable {
        Object[] args = new Object[20];
        args[0] = T.s("多选", "Select");
        args[1] = null;        // modifier
        args[2] = 0L;          // color
        args[3] = null;        // autoSize
        args[4] = 0L;          // fontSize
        args[5] = null;        // fontWeight
        args[6] = null;        // fontFamily
        args[7] = 0L;          // letterSpacing
        args[8] = null;        // textDecoration
        args[9] = null;        // textAlign
        args[10] = 0L;         // lineHeight
        args[11] = 0;          // overflow
        args[12] = Boolean.FALSE; // softWrap
        args[13] = 0;          // maxLines
        args[14] = 0;          // minLines
        args[15] = null;       // style
        args[16] = composer;
        args[17] = 0;          // changed
        args[18] = 0;          // changed
        args[19] = 0xFFFE;     // 除文字外全部取默认值
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
                            Method call = function0.getMethod("invoke");
                            call.setAccessible(true);
                            call.invoke(dismiss);
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
