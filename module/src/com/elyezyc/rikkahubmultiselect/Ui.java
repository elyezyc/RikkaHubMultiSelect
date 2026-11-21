/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 控件工厂。尺寸和形状按 Material 3（RikkaHub 用的就是 MaterialExpressiveTheme）：
 * 列表项 56dp、按钮 40dp 高 / 20dp 圆角、面板顶部 28dp 圆角。
 */
final class Ui {

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int alpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, factor)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** 底部面板：只有上面两个角是圆的。 */
    static GradientDrawable sheetBackground(Context context, int color, float radiusDp) {
        float r = dp(context, radiusDp);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return drawable;
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(view.getTypeface(), style));
        return view;
    }

    /** ListItem 标题：bodyLarge 16sp。 */
    static TextView itemTitle(Context context, Palette palette, CharSequence value, int color) {
        TextView view = text(context, value, palette.sp(16f), color, Typeface.NORMAL);
        view.setSingleLine(true);
        return view;
    }

    /** ListItem 副标题：bodyMedium 12sp。 */
    static TextView itemSupporting(Context context, Palette palette, CharSequence value, int color) {
        TextView view = text(context, value, palette.sp(12f), color, Typeface.NORMAL);
        view.setMaxLines(2);
        return view;
    }

    /** FilledTonalButton：40dp 高、20dp 圆角、labelLarge 14sp。 */
    static TextView tonalButton(Context context, Palette palette, CharSequence label, boolean destructive) {
        int background = destructive
                ? Ui.alpha(palette.errorContainer, 0.85f)
                : Ui.alpha(palette.secondaryContainer, 0.9f);
        int foreground = destructive ? palette.onErrorContainer : palette.onSecondaryContainer;
        TextView view = text(context, label, palette.sp(14f), foreground, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        view.setBackground(rounded(context, background, 20f));
        view.setClickable(true);
        view.setFocusable(true);
        view.setMinHeight(dp(context, 40));
        return view;
    }

    /** TextButton：无底色，主色文字。 */
    static TextView textButton(Context context, Palette palette, CharSequence label) {
        TextView view = text(context, label, palette.sp(14f), palette.primary, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    static void margins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;
            params.leftMargin = left;
            params.topMargin = top;
            params.rightMargin = right;
            params.bottomMargin = bottom;
            view.setLayoutParams(params);
        }
    }
}
