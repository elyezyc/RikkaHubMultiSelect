/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口，类名要和 {@code assets/xposed_init} 里那行一致。
 *
 * <p>只在 RikkaHub 自己的进程里注入，别的应用一概不碰。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!Host.isHostPackage(lpparam.packageName)) {
            return;
        }
        try {
            Host.install(lpparam.classLoader);
            XLog.i("已注入 " + lpparam.packageName);
        } catch (Throwable t) {
            XLog.e("注入失败", t);
        }
    }
}
