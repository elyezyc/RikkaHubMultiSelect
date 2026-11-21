/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多选状态。选中动作直接在宿主列表里做，不再单开一个面板：
 * 行 composable 会根据这里的状态改自己的点击回调和高亮。
 *
 * <p>宿主不会因为这里变了就自己重组，所以额外准备了一个 Compose 的 {@code MutableState}
 * （{@code Updater.mutableStateOf$default} 造的），在每个行 composable 组合时读一下。
 * 只有读了才会登记依赖，改它就能让整份列表重新组合。
 */
final class Selection {

    static final int NONE = -1;
    static final int CONVERSATIONS = 0;
    static final int PROVIDERS = 1;
    static final int ASSISTANTS = 2;

    private static final LinkedHashSet<String> SELECTED = new LinkedHashSet<>();
    /** 行组合时收集到的会话对象和宿主回调，重生成标题要用。 */
    private static final Map<String, Object[]> ROW_DATA = new HashMap<>();

    private static volatile int mode = NONE;
    private static Object tick;
    private static Method tickGet;
    private static Method tickSet;

    private Selection() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> updater = Class.forName("androidx.compose.runtime.Updater", false, loader);
            Method factory = null;
            for (Method method : updater.getDeclaredMethods()) {
                if (method.getName().startsWith("mutableStateOf")
                        && method.getParameterCount() >= 1
                        && method.getReturnType().getName()
                        .equals("androidx.compose.runtime.ParcelableSnapshotMutableState")) {
                    factory = method;
                    break;
                }
            }
            if (factory == null) {
                throw new NoSuchMethodException("Updater#mutableStateOf");
            }
            factory.setAccessible(true);
            Class<?> state = Class.forName("androidx.compose.runtime.State", false, loader);
            tickGet = state.getMethod("getValue");
            tickSet = Class.forName("androidx.compose.runtime.MutableState", false, loader)
                    .getMethod("setValue", Object.class);
            tick = factory.getParameterCount() == 1
                    ? factory.invoke(null, 0L)
                    : factory.invoke(null, 0L, null);
            XLog.i("多选状态已就绪");
        } catch (Throwable t) {
            XLog.e("多选状态初始化失败（列表里不会有选中效果）", t);
        }
    }

    /** 在行 composable 组合时调用：登记对 tick 的依赖。 */
    static void observe() {
        if (tick == null || tickGet == null) {
            return;
        }
        try {
            tickGet.invoke(tick);
        } catch (Throwable ignored) {
            // 只是登记依赖，失败不影响功能
        }
    }

    private static void bump() {
        if (tick == null || tickSet == null) {
            return;
        }
        try {
            tickSet.invoke(tick, System.nanoTime());
        } catch (Throwable t) {
            XLog.e("刷新列表失败", t);
        }
    }

    static int mode() {
        return mode;
    }

    static boolean active(int target) {
        return mode == target;
    }

    static void enter(int target) {
        mode = target;
        SELECTED.clear();
        ROW_DATA.clear();
        bump();
    }

    static void exit() {
        mode = NONE;
        SELECTED.clear();
        ROW_DATA.clear();
        bump();
    }

    static boolean contains(String id) {
        return SELECTED.contains(id);
    }

    static void toggle(String id) {
        if (!SELECTED.remove(id)) {
            SELECTED.add(id);
        }
        bump();
    }

    static int size() {
        return SELECTED.size();
    }

    static List<String> ids() {
        return new ArrayList<>(SELECTED);
    }

    static void putRow(String id, Object[] data) {
        ROW_DATA.put(id, data);
    }

    static Object[] row(String id) {
        return ROW_DATA.get(id);
    }
}
