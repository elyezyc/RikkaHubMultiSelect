/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.widget.Toast;

/** 提示。所有异常都要变成一句能看懂的话，不能把堆栈甩给用户。 */
final class ToastHelper {

    private ToastHelper() {
    }

    static void show(Activity activity, String message) {
        if (activity == null || activity.isFinishing() || message == null) {
            return;
        }
        try {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            XLog.e("Toast 失败", t);
        }
    }

    static void error(Activity activity, Throwable throwable) {
        show(activity, T.s("操作失败：", "Failed: ") + describe(throwable));
    }

    static String describe(Throwable throwable) {
        if (throwable == null) {
            return T.s("未知错误", "unknown error");
        }
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        return message;
    }
}
