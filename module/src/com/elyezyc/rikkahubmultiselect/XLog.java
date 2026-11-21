/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import de.robv.android.xposed.XposedBridge;

/** 统一日志前缀，方便在 Vector / LSPosed 的日志页里过滤。 */
final class XLog {

    private static final String TAG = "RikkaHubMultiSelect";

    private XLog() {
    }

    static void i(String message) {
        try {
            XposedBridge.log("[" + TAG + "] " + message);
        } catch (Throwable ignored) {
            // 日志本身失败就算了，绝不能影响宿主
        }
    }

    static void e(String message, Throwable throwable) {
        i(message + (throwable == null ? "" : "：" + throwable));
        if (throwable != null) {
            try {
                XposedBridge.log(throwable);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }
}
