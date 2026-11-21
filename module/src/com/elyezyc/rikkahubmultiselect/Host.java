/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.os.Bundle;

import java.lang.ref.WeakReference;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 和宿主打交道的那一层：按名字找到宿主的类，在宿主初始化时抓住 {@code SettingsStore} /
 * {@code ConversationRepository} 两个单例，再把 RouteActivity 挂上悬浮按钮。
 *
 * <p>宿主用 R8 处理过（没有混淆，但会删掉和改写），所以这里所有类型都是按名字 lazily 取的，
 * 取不到就记日志并让功能降级，绝不抛到宿主里去。
 */
final class Host {

    private static final String HOST_PACKAGE = "me.rerere.rikkahub";
    private static final String HOST_PACKAGE_DEBUG = "me.rerere.rikkahub.debug";

    private static ClassLoader loader;
    private static boolean installed;

    private static volatile Object settingsStore;
    private static volatile Object conversationRepo;
    private static volatile WeakReference<Activity> currentActivity;

    private static Class<?> settingsStoreClass;
    private static Class<?> repositoryClass;
    private static Class<?> settingsClass;
    private static Class<?> providerSettingClass;
    private static Class<?> stateFlowClass;
    private static Class<?> roomDatabaseClass;
    private static Class<?> function0Class;
    private static Class<?> function1Class;

    private Host() {
    }

    static void install(ClassLoader classLoader) {
        synchronized (Host.class) {
            if (installed) {
                return;
            }
            installed = true;
        }
        loader = classLoader;
        XLog.i("开始注入");

        Suspend.init(classLoader);
        try {
            Uuids.init(classLoader);
        } catch (Throwable t) {
            XLog.e("Uuid 反射初始化失败", t);
        }

        settingsStoreClass = Reflect.load(classLoader, "me.rerere.rikkahub.data.datastore.SettingsStore");
        repositoryClass = Reflect.load(classLoader, "me.rerere.rikkahub.data.repository.ConversationRepository");
        settingsClass = Reflect.load(classLoader, "me.rerere.rikkahub.data.datastore.Settings");
        providerSettingClass = Reflect.load(classLoader, "me.rerere.ai.provider.ProviderSetting");
        stateFlowClass = Reflect.load(classLoader, "kotlinx.coroutines.flow.StateFlow");
        roomDatabaseClass = Reflect.load(classLoader, "androidx.room.RoomDatabase");
        function0Class = Reflect.load(classLoader, "kotlin.jvm.functions.Function0");
        function1Class = Reflect.load(classLoader, "kotlin.jvm.functions.Function1");
        Conversations.init(classLoader);
        Selection.install(classLoader);
        MenuInjector.install(classLoader);

        capture(settingsStoreClass, "SettingsStore", new Sink() {
            @Override
            public void accept(Object value) {
                settingsStore = value;
            }
        });
        capture(repositoryClass, "ConversationRepository", new Sink() {
            @Override
            public void accept(Object value) {
                conversationRepo = value;
            }
        });
        hookActivity();
    }

    static boolean isHostPackage(String packageName) {
        return HOST_PACKAGE.equals(packageName) || HOST_PACKAGE_DEBUG.equals(packageName);
    }

    // ---------------------------------------------------------------- 类

    static Class<?> settingsStoreClass() {
        return settingsStoreClass;
    }

    static Class<?> settingsClass() {
        return settingsClass;
    }

    static Class<?> providerSettingClass() {
        return providerSettingClass;
    }

    static Class<?> roomDatabaseClass() {
        return roomDatabaseClass;
    }

    static Class<?> repositoryClass() {
        return repositoryClass;
    }

    static ClassLoader loader() {
        return loader;
    }

    // ---------------------------------------------------------------- 单例

    /**
     * 当前导航栈最上面那个页面的类名（RikkaHub 用 navigation3，RouteActivity 里存着 navStack）。
     * 认不出来就返回 null。
     */
    static String currentScreen() {
        Activity activity = currentActivity();
        if (activity == null) {
            return null;
        }
        try {
            Object stack = Reflect.get(Reflect.field(activity.getClass(), "navStack"), activity);
            if (stack instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) stack;
                if (!list.isEmpty()) {
                    Object top = list.get(list.size() - 1);
                    return top == null ? null : top.getClass().getName();
                }
            }
        } catch (Throwable ignored) {
            // 认不出来就当作没有
        }
        return null;
    }

    /** 当前前台的主界面 Activity（菜单项点击时用）。 */
    static Activity currentActivity() {
        WeakReference<Activity> reference = currentActivity;
        return reference == null ? null : reference.get();
    }

    static Object settingsStore() {
        Object store = settingsStore;
        if (store == null) {
            throw new IllegalStateException(T.s(
                    "还没拿到 RikkaHub 的设置对象，重启一次 RikkaHub 再试",
                    "RikkaHub settings are not available yet; restart RikkaHub and try again"));
        }
        return store;
    }

    static Object repository() {
        Object repo = conversationRepo;
        if (repo == null) {
            throw new IllegalStateException(T.s(
                    "还没拿到 RikkaHub 的会话仓库，重启一次 RikkaHub 再试",
                    "RikkaHub conversation store is not available yet; restart RikkaHub and try again"));
        }
        return repo;
    }

    /** 当前设置快照（宿主内存里的那一份）。 */
    static Object settings() throws Throwable {
        Object store = settingsStore();
        Field field = Reflect.field(settingsStoreClass, "settingsFlow");
        Object flow = Reflect.get(field, store);
        if (flow == null || stateFlowClass == null) {
            throw new IllegalStateException("settingsFlow 不可用");
        }
        Method getValue = stateFlowClass.getMethod("getValue");
        return getValue.invoke(flow);
    }

    /** 宿主的 Room 数据库（AppDatabase），用来读会话列表、改置顶/归属。 */
    static Object database() throws Throwable {
        Object repo = repository();
        Field field = Reflect.field(repositoryClass, "database");
        Object database = Reflect.get(field, repo);
        if (database == null) {
            throw new IllegalStateException("拿不到宿主的数据库实例");
        }
        return database;
    }

    // ---------------------------------------------------------------- 回调

    interface Transform {
        Object apply(Object settings) throws Throwable;
    }

    /** 宿主的 {@code Function1<Settings, Settings>}（settingsStore.update 的形参）。 */
    static Object newFunction1(final Transform transform) {
        if (function1Class == null) {
            throw new IllegalStateException("宿主没有 kotlin.jvm.functions.Function1");
        }
        return Proxy.newProxyInstance(function1Class.getClassLoader(), new Class<?>[]{function1Class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        switch (method.getName()) {
                            case "invoke":
                                return transform.apply(args == null ? null : args[0]);
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == (args == null ? null : args[0]);
                            case "toString":
                                return "RikkaHubMultiSelect$Transform";
                            default:
                                return null;
                        }
                    }
                });
    }

    /** 宿主的 {@code Function0<T>}（RoomDatabase.runInTransaction 的形参）。 */
    static Object newFunction0(final Runnable body) {
        if (function0Class == null) {
            throw new IllegalStateException("宿主没有 kotlin.jvm.functions.Function0");
        }
        return Proxy.newProxyInstance(function0Class.getClassLoader(), new Class<?>[]{function0Class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "invoke":
                                body.run();
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == (args == null ? null : args[0]);
                            case "toString":
                                return "RikkaHubMultiSelect$Body";
                            default:
                                return null;
                        }
                    }
                });
    }

    // ---------------------------------------------------------------- 内部

    private interface Sink {
        void accept(Object value);
    }

    private static void capture(Class<?> type, String label, final Sink sink) {
        if (type == null) {
            XLog.i("找不到 " + label + " 类");
            return;
        }
        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null) {
                            sink.accept(param.thisObject);
                        }
                    }
                });
                count++;
            } catch (Throwable t) {
                XLog.e("挂钩 " + label + " 构造器失败", t);
            }
        }
        XLog.i(label + " 构造器已挂钩 " + count + " 个");
    }

    private static void hookActivity() {
        Class<?> activityClass = Reflect.load(loader, "me.rerere.rikkahub.RouteActivity");
        if (activityClass == null) {
            XLog.i("找不到 me.rerere.rikkahub.RouteActivity");
            return;
        }

        Method onCreate = Reflect.methodExact(activityClass, "onCreate", Bundle.class);
        if (onCreate == null) {
            XLog.i("找不到 RouteActivity#onCreate");
        } else {
            try {
                XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) {
                            return;
                        }
                        Activity activity = (Activity) param.thisObject;
                        currentActivity = new WeakReference<>(activity);
                        // Activity 重建（旋转屏幕等）后工具栏会跟着没，重新挂一下
                        if (Selection.mode() != Selection.NONE) {
                            SelectionBar.show(activity);
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 RouteActivity#onCreate 失败", t);
            }
        }

        Method onPause = Reflect.methodExact(activityClass, "onPause");
        if (onPause != null) {
            try {
                XposedBridge.hookMethod(onPause, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 切到后台 / 打开别的界面时收起多选
                        if (Selection.mode() != Selection.NONE) {
                            Selection.exit();
                            SelectionBar.detach();
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 RouteActivity#onPause 失败", t);
            }
        }

        Method onDestroy = Reflect.methodExact(activityClass, "onDestroy");
        if (onDestroy != null) {
            try {
                XposedBridge.hookMethod(onDestroy, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            Activity activity = (Activity) param.thisObject;
                            if (currentActivity != null && currentActivity.get() == activity) {
                                currentActivity = null;
                            }
                            SelectionBar.detach();
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.e("挂钩 RouteActivity#onDestroy 失败", t);
            }
        }
    }

    /**
     * 构造器钩子没抓住单例时的兜底：RouteActivity 里 {@code by inject()} 存的是 Lazy 委托，
     * 把委托里的值取出来，类型对得上就用。
     */
    static void recoverFrom(Activity activity) {
        if (settingsStoreClass == null || activity == null) {
            return;
        }
        try {
            for (Class<?> type = activity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    Object resolved = resolveLazy(readQuietly(field, activity));
                    if (resolved == null) {
                        continue;
                    }
                    if (settingsStore == null && settingsStoreClass.isInstance(resolved)) {
                        settingsStore = resolved;
                        XLog.i("通过 Activity 委托补抓到 SettingsStore");
                    }
                    if (conversationRepo == null && repositoryClass != null && repositoryClass.isInstance(resolved)) {
                        conversationRepo = resolved;
                        XLog.i("通过 Activity 委托补抓到 ConversationRepository");
                    }
                }
            }
        } catch (Throwable t) {
            XLog.e("从 Activity 补抓单例失败", t);
        }
    }

    private static Object resolveLazy(Object value) {
        if (value == null) {
            return null;
        }
        if (isLazy(value)) {
            try {
                Method getValue = value.getClass().getMethod("getValue");
                return getValue.invoke(value);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return value;
    }

    private static boolean isLazy(Object value) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                if ("kotlin.Lazy".equals(iface.getName())) {
                    return true;
                }
            }
            if ("kotlin.Lazy".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object readQuietly(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
