/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 面板上的每个批量动作都落在这里，界面只管收集 id 和显示结果。
 *
 * <p>顺序上尽量跟着宿主自己的实现走：删助手时先删助手本身，再清它名下的对话
 * （和 {@code AssistantVM.removeAssistant} 一致）。
 */
final class Actions {

    private Actions() {
    }

    static int pin(List<String> ids, boolean pinned) throws Throwable {
        return Conversations.pin(ids, pinned);
    }

    static int moveToAssistant(List<String> ids, String assistantId) throws Throwable {
        return Conversations.moveToAssistant(ids, assistantId);
    }

    static int moveToFolder(List<String> ids, String folderId) throws Throwable {
        return Conversations.moveToFolder(ids, folderId);
    }

    static int cloneAssistants(List<String> ids) throws Throwable {
        return SettingsApi.cloneAssistants(ids);
    }

    /** 重新生成标题交给宿主自己的回调做（每个会话一次），这里只负责把选中的都点一遍。 */
    static int regenerateTitles(List<String> ids) {
        final Method invoke = invokeMethod("kotlin.jvm.functions.Function1", Object.class);
        int count = 0;
        for (String id : ids) {
            Object[] row = Selection.row(id);
            if (row == null || row[0] == null || row[2] == null || invoke == null) {
                continue;
            }
            final Object conversation = row[0];
            final Object callback = row[2];
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    invoke.invoke(callback, conversation);
                } catch (Throwable t) {
                    XLog.e("重新生成标题失败", t);
                }
            });
            count++;
        }
        return count;
    }

    private static Method invokeMethod(String className, Class<?>... types) {
        Class<?> type = Host.loader() == null ? null : Reflect.load(Host.loader(), className);
        if (type == null) {
            return null;
        }
        try {
            Method method = type.getMethod("invoke", types);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int deleteConversations(List<String> ids) throws Throwable {
        return Conversations.delete(ids);
    }

    static int setProvidersEnabled(List<String> ids, boolean enabled) throws Throwable {
        return SettingsApi.setProvidersEnabled(ids, enabled);
    }

    static int deleteProviders(List<String> ids) throws Throwable {
        return SettingsApi.deleteProviders(ids);
    }

    /** @return {删掉的助手数, 连带清理的对话数} */
    static int[] deleteAssistants(List<String> ids) throws Throwable {
        List<String> owned = new ArrayList<>();
        for (Conversations.Row row : Conversations.load()) {
            if (ids.contains(row.assistantId)) {
                owned.add(row.id);
            }
        }
        int assistants = SettingsApi.deleteAssistants(ids);
        if (assistants == 0) {
            return new int[]{0, 0};
        }
        int conversations = owned.isEmpty() ? 0 : Conversations.delete(owned);
        return new int[]{assistants, conversations};
    }

    static void selectAssistant(String id) throws Throwable {
        SettingsApi.selectAssistant(id);
    }
}
