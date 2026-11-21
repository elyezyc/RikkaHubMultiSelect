/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

/**
 * 一个纯说明页。多选面板本身跑在 RikkaHub 的进程里，这里只是给人看模块是做什么的。
 */
public final class MainActivity extends Activity {

    static final String VERSION = "1.0.0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView body = findViewById(R.id.body);
        body.setText(getString(R.string.main_body, VERSION));
        body.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
