/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 自己画的 Material 3 Expressive 风格对话框。
 *
 * <p>宿主主题里没有配置平台的 alertDialogTheme，直接用 {@code android.app.AlertDialog} 会掉回
 * 旧版 Android 的样式。这里按 M3 的对话框规格画：28dp 圆角、surfaceContainer 底色、24dp 内边距、
 * 标题 headlineSmall、正文 bodyMedium、底部一排胶囊按钮；AMOLED 纯黑时加一圈细描边。
 */
final class M3Dialog {

    interface Picker {
        void onPick(int index);
    }

    private M3Dialog() {
    }

    /** 确认框：标题 + 说明 + 取消 / 确认。 */
    static void confirm(Activity activity, CharSequence title, CharSequence message,
                        CharSequence confirmLabel, boolean destructive, Runnable onConfirm) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Palette palette = Palette.resolve(activity);
        LinearLayout root = base(activity, palette);

        root.addView(Ui.text(activity, title, palette.sp(22f), palette.onSurface, Typeface.NORMAL),
                wrap());
        TextView body = Ui.text(activity, message, palette.sp(14f), palette.onSurfaceVariant,
                Typeface.NORMAL);
        body.setLineSpacing(Ui.dp(activity, 3), 1f);
        LinearLayout.LayoutParams bodyParams = wrap();
        bodyParams.topMargin = Ui.dp(activity, 12);
        root.addView(body, bodyParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = wrap();
        actionParams.topMargin = Ui.dp(activity, 20);

        final Dialog dialog = new Dialog(activity);
        TextView cancel = textButton(activity, palette, T.s("取消", "Cancel"), palette.primary);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, withRightMargin(activity, 8));

        TextView confirm = pillButton(activity, palette, confirmLabel,
                destructive ? palette.error : palette.onSecondaryContainer,
                destructive ? Ui.alpha(palette.errorContainer, 0.85f) : palette.secondaryContainer);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        actions.addView(confirm, wrap());
        root.addView(actions, actionParams);

        show(activity, dialog, root);
    }

    /** 选择框：标题 + 一列选项（可以滚动）。 */
    static void choose(Activity activity, CharSequence title, String[] options, Picker picker) {
        if (activity == null || activity.isFinishing() || options == null || options.length == 0) {
            return;
        }
        Palette palette = Palette.resolve(activity);
        LinearLayout root = base(activity, palette);
        root.addView(Ui.text(activity, title, palette.sp(22f), palette.onSurface, Typeface.NORMAL),
                wrap());

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(activity, 8), 0, 0);
        final Dialog dialog = new Dialog(activity);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView row = Ui.text(activity, options[i], palette.sp(16f), palette.onSurface,
                    Typeface.NORMAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
            row.setMinHeight(Ui.dp(activity, 52));
            row.setBackground(ripple(activity, palette));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (picker != null) {
                    picker.onPick(index);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        final ScrollView scroller = new ScrollView(activity);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = Ui.dp(activity, 8);
        root.addView(scroller, listParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = textButton(activity, palette, T.s("取消", "Cancel"), palette.primary);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, wrap());
        LinearLayout.LayoutParams actionParams = wrap();
        actionParams.topMargin = Ui.dp(activity, 12);
        root.addView(actions, actionParams);

        show(activity, dialog, root);
        // 选项多的时候给列表一个上限，超过就滚动；少了就让对话框自然收缩
        scroller.post(new Runnable() {
            @Override
            public void run() {
                DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                int max = Math.min((int) (metrics.heightPixels * 0.55f), Ui.dp(activity, 420));
                if (list.getHeight() > max) {
                    ViewGroup.LayoutParams params = scroller.getLayoutParams();
                    params.height = max;
                    scroller.setLayoutParams(params);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 内部

    private static LinearLayout base(Activity activity, Palette palette) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 22),
                Ui.dp(activity, 24), Ui.dp(activity, 16));
        boolean amoled = palette.dark && palette.surface == 0xFF000000;
        GradientDrawable background = Ui.rounded(activity,
                amoled ? 0xFF000000 : palette.surfaceContainerHigh, 28f);
        if (amoled) {
            background.setStroke(Ui.dp(activity, 1), Ui.alpha(palette.outlineVariant, 0.55f));
        }
        root.setBackground(background);
        return root;
    }

    private static void show(Activity activity, Dialog dialog, View content) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.32f;
        window.setAttributes(attributes);
        window.setGravity(Gravity.CENTER);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        window.setLayout(Math.min((int) (metrics.widthPixels * 0.9f), Ui.dp(activity, 420)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static TextView textButton(Activity activity, Palette palette, CharSequence label,
                                       int color) {
        TextView view = Ui.text(activity, label, palette.sp(14f), color, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10),
                Ui.dp(activity, 14), Ui.dp(activity, 10));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static TextView pillButton(Activity activity, Palette palette, CharSequence label,
                                       int color, int background) {
        TextView view = Ui.text(activity, label, palette.sp(14f), color, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(activity, 22), 0, Ui.dp(activity, 22), 0);
        view.setMinHeight(Ui.dp(activity, 40));
        view.setBackground(Ui.rounded(activity, background, 20f));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static RippleDrawable ripple(Activity activity, Palette palette) {
        GradientDrawable shape = Ui.rounded(activity, Color.TRANSPARENT, 14f);
        return new RippleDrawable(
                ColorStateList.valueOf(Ui.alpha(palette.onSurface, 0.12f)), shape, null);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams withRightMargin(Activity activity, float dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(activity, dp);
        return params;
    }
}
