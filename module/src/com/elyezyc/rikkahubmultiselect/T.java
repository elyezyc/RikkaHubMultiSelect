/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.util.Locale;

/** 面板里用到的文案。面板跑在宿主进程里，拿不到模块自己的资源表，所以直接写在代码里。 */
final class T {

    static final boolean ZH = Locale.getDefault().getLanguage().startsWith("zh");

    private T() {
    }

    static String s(String zh, String en) {
        return ZH ? zh : en;
    }

    static String f(String zh, String en, Object... args) {
        return String.format(Locale.getDefault(), ZH ? zh : en, args);
    }
}
