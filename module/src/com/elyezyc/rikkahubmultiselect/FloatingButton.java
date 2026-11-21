/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 宿主界面右侧那个可以拖动的小按钮。
 *
 * <p>Xposed 没法往 Compose 的组合里塞东西，所以入口是一层浮在 decorView 上的普通 View：
 * 点一下打开多选面板，拖动能换位置（位置记在宿主自己的 SharedPreferences 里）。
 */
final class FloatingButton {

    private static final String TAG = "rikkahubmultiselect.fab";
    private static final String PREFS = "rikkahubmultiselect";
    private static final String KEY_X = "fab_x";
    private static final String KEY_Y = "fab_y";

    private FloatingButton() {
    }

    static void attach(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) decor;
        root.post(new Runnable() {
            @Override
            public void run() {
                try {
                    install(activity, root);
                } catch (Throwable t) {
                    XLog.e("挂载悬浮按钮失败", t);
                }
            }
        });
    }

    /** 菜单注入生效后把兜底按钮收掉。 */
    static void detach(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) decor;
        root.post(new Runnable() {
            @Override
            public void run() {
                View view = root.findViewWithTag(TAG);
                if (view != null) {
                    root.removeView(view);
                }
            }
        });
    }

    private static void install(Activity activity, ViewGroup root) {
        if (root.findViewWithTag(TAG) != null) {
            return;
        }
        TextView button = create(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int savedX = prefs.getInt(KEY_X, -1);
        int savedY = prefs.getInt(KEY_Y, -1);
        if (savedX >= 0 && savedY >= 0) {
            params.leftMargin = savedX;
            params.topMargin = savedY;
        } else {
            params.leftMargin = Math.max(0, metrics.widthPixels - Ui.dp(activity, 74));
            params.topMargin = Math.round(metrics.heightPixels * 0.38f);
        }
        root.addView(button, params);
        button.setOnTouchListener(new DragTouch(activity, button, params, root, prefs));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                DragTouch.clampToParent(button, params, root);
            }
        });
        XLog.i("多选按钮已挂到 " + activity.getClass().getSimpleName());
    }

    private static TextView create(Activity activity) {
        Palette palette = Palette.resolve(activity);
        TextView button = Ui.text(activity, T.s("多选", "Select"), palette.sp(14f),
                palette.onSurface, android.graphics.Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        button.setMinHeight(Ui.dp(activity, 40));
        button.setBackground(Ui.rounded(activity, palette.surfaceContainerHigh, 20f));
        button.setElevation(Ui.dp(activity, 6));
        button.setAlpha(0.94f);
        button.setTag(TAG);
        button.setContentDescription(T.s("多选批量操作", "Batch actions"));
        return button;
    }

    private static final class DragTouch implements View.OnTouchListener {

        private final Activity activity;
        private final View button;
        private final FrameLayout.LayoutParams params;
        private final ViewGroup parent;
        private final SharedPreferences prefs;
        private final int slop;

        private float downRawX;
        private float downRawY;
        private int startLeft;
        private int startTop;
        private boolean moved;

        DragTouch(Activity activity, View button, FrameLayout.LayoutParams params,
                  ViewGroup parent, SharedPreferences prefs) {
            this.activity = activity;
            this.button = button;
            this.params = params;
            this.parent = parent;
            this.prefs = prefs;
            this.slop = Ui.dp(activity, 6);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startLeft = params.leftMargin;
                    startTop = params.topMargin;
                    moved = false;
                    button.setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true;
                    }
                    if (moved) {
                        params.leftMargin = startLeft + dx;
                        params.topMargin = startTop + dy;
                        clampToParent(button, params, parent);
                        button.setLayoutParams(params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    button.setAlpha(0.94f);
                    if (moved) {
                        prefs.edit()
                                .putInt(KEY_X, params.leftMargin)
                                .putInt(KEY_Y, params.topMargin)
                                .apply();
                    } else {
                        open();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    button.setAlpha(0.94f);
                    return true;
                default:
                    return false;
            }
        }

        private void open() {
            try {
                Host.recoverFrom(activity);
                MultiSelectDialog.show(activity);
            } catch (Throwable t) {
                XLog.e("打开多选面板失败", t);
                ToastHelper.error(activity, t);
            }
        }

        static void clampToParent(View button, FrameLayout.LayoutParams params, ViewGroup parent) {
            int width = parent.getWidth();
            int height = parent.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int maxLeft = Math.max(0, width - button.getWidth());
            int maxTop = Math.max(0, height - button.getHeight());
            params.leftMargin = Math.min(Math.max(0, params.leftMargin), maxLeft);
            params.topMargin = Math.min(Math.max(0, params.topMargin), maxTop);
        }
    }
}
