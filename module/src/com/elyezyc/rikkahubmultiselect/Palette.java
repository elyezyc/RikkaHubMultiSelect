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
import android.content.res.Configuration;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 面板用的配色，尽量取 RikkaHub 当前正在用的那一套，而不是自己编一套。
 *
 * <p>顺序：
 * <ol>
 *   <li>宿主的 {@code PresetThemeKt.findThemeById(themeId, customThemes)} 拿到 {@code PresetTheme}，
 *       再读它预生成的 {@code ColorScheme}（预设主题和自定义主题都走这里，是最准的一档）；</li>
 *   <li>取不到且用户开了动态取色（Android 12+）：用系统的 system_accent / system_neutral 色阶，
 *       和 Compose 的 dynamicLight/DarkColorScheme 是同一批颜色；</li>
 *   <li>再取不到：Material 3 基线配色。</li>
 * </ol>
 *
 * <p>深色模式、AMOLED 纯黑、字体缩放都按宿主自己的偏好来（{@code rikkahub.preferences} 里的
 * {@code colorMode} / {@code amoledDark}，以及设置里的 {@code displaySetting.fontSizeRatio}）。
 */
final class Palette {

    private static final int LIGHT_SURFACE = 0xFFFEF7FF;
    private static final int LIGHT_SURFACE_LOW = 0xFFF7F2FA;
    private static final int LIGHT_SURFACE_HIGH = 0xFFECE6F0;
    private static final int LIGHT_ON_SURFACE = 0xFF1D1B20;
    private static final int LIGHT_ON_SURFACE_VARIANT = 0xFF49454F;
    private static final int LIGHT_OUTLINE_VARIANT = 0xFFCAC4D0;
    private static final int LIGHT_PRIMARY = 0xFF6750A4;
    private static final int LIGHT_ON_PRIMARY = 0xFFFFFFFF;
    private static final int LIGHT_PRIMARY_CONTAINER = 0xFFEADDFF;
    private static final int LIGHT_ON_PRIMARY_CONTAINER = 0xFF21005D;
    private static final int LIGHT_SECONDARY_CONTAINER = 0xFFE8DEF8;
    private static final int LIGHT_ON_SECONDARY_CONTAINER = 0xFF1D192B;
    private static final int LIGHT_ERROR = 0xFFB3261E;
    private static final int LIGHT_ON_ERROR = 0xFFFFFFFF;
    private static final int LIGHT_ERROR_CONTAINER = 0xFFF9DEDC;
    private static final int LIGHT_ON_ERROR_CONTAINER = 0xFF410E0B;

    private static final int DARK_SURFACE = 0xFF141218;
    private static final int DARK_SURFACE_LOW = 0xFF1D1B20;
    private static final int DARK_SURFACE_HIGH = 0xFF2B2930;
    private static final int DARK_ON_SURFACE = 0xFFE6E0E9;
    private static final int DARK_ON_SURFACE_VARIANT = 0xFFCAC4D0;
    private static final int DARK_OUTLINE_VARIANT = 0xFF49454F;
    private static final int DARK_PRIMARY = 0xFFD0BCFF;
    private static final int DARK_ON_PRIMARY = 0xFF381E72;
    private static final int DARK_PRIMARY_CONTAINER = 0xFF4F378B;
    private static final int DARK_ON_PRIMARY_CONTAINER = 0xFFEADDFF;
    private static final int DARK_SECONDARY_CONTAINER = 0xFF4A4458;
    private static final int DARK_ON_SECONDARY_CONTAINER = 0xFFE8DEF8;
    private static final int DARK_ERROR = 0xFFF2B8B5;
    private static final int DARK_ON_ERROR = 0xFF601410;
    private static final int DARK_ERROR_CONTAINER = 0xFF8C1D18;
    private static final int DARK_ON_ERROR_CONTAINER = 0xFFF9DEDC;

    final boolean dark;
    final float fontScale;

    final int surface;
    final int surfaceContainerHigh;
    final int onSurface;
    final int onSurfaceVariant;
    final int outlineVariant;
    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int secondaryContainer;
    final int onSecondaryContainer;
    final int error;
    final int onError;
    final int errorContainer;
    final int onErrorContainer;

    private Palette(boolean dark, float fontScale, int surface, int surfaceContainerHigh, int onSurface,
                    int onSurfaceVariant, int outlineVariant, int primary, int onPrimary,
                    int primaryContainer, int onPrimaryContainer, int secondaryContainer,
                    int onSecondaryContainer, int error, int onError, int errorContainer,
                    int onErrorContainer) {
        this.dark = dark;
        this.fontScale = fontScale;
        this.surface = surface;
        this.surfaceContainerHigh = surfaceContainerHigh;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.outlineVariant = outlineVariant;
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.primaryContainer = primaryContainer;
        this.onPrimaryContainer = onPrimaryContainer;
        this.secondaryContainer = secondaryContainer;
        this.onSecondaryContainer = onSecondaryContainer;
        this.error = error;
        this.onError = onError;
        this.errorContainer = errorContainer;
        this.onErrorContainer = onErrorContainer;
    }

    float sp(float size) {
        return size * fontScale;
    }

    static Palette resolve(Activity activity) {
        Context context = activity.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE);
        String colorMode = prefs.getString("colorMode", "SYSTEM");
        boolean amoled = prefs.getBoolean("amoledDark", false);

        boolean systemDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "DARK".equals(colorMode) || ("SYSTEM".equals(colorMode) && systemDark);

        float fontScale = 1f;
        Object settings = null;
        try {
            settings = Host.settings();
            Object display = Reflect.property(settings, "displaySetting");
            Object ratio = display == null ? null : Reflect.property(display, "fontSizeRatio");
            if (ratio instanceof Number) {
                fontScale = Math.max(0.85f, Math.min(1.35f, ((Number) ratio).floatValue()));
            }
        } catch (Throwable t) {
            XLog.i("读不到设置，配色按系统深色状态来：" + t);
        }

        int surface = dark ? DARK_SURFACE : LIGHT_SURFACE;
        int surfaceHigh = dark ? DARK_SURFACE_HIGH : LIGHT_SURFACE_HIGH;
        int onSurface = dark ? DARK_ON_SURFACE : LIGHT_ON_SURFACE;
        int onSurfaceVariant = dark ? DARK_ON_SURFACE_VARIANT : LIGHT_ON_SURFACE_VARIANT;
        int outlineVariant = dark ? DARK_OUTLINE_VARIANT : LIGHT_OUTLINE_VARIANT;
        int primary = dark ? DARK_PRIMARY : LIGHT_PRIMARY;
        int onPrimary = dark ? DARK_ON_PRIMARY : LIGHT_ON_PRIMARY;
        int primaryContainer = dark ? DARK_PRIMARY_CONTAINER : LIGHT_PRIMARY_CONTAINER;
        int onPrimaryContainer = dark ? DARK_ON_PRIMARY_CONTAINER : LIGHT_ON_PRIMARY_CONTAINER;
        int secondaryContainer = dark ? DARK_SECONDARY_CONTAINER : LIGHT_SECONDARY_CONTAINER;
        int onSecondaryContainer = dark ? DARK_ON_SECONDARY_CONTAINER : LIGHT_ON_SECONDARY_CONTAINER;
        int error = dark ? DARK_ERROR : LIGHT_ERROR;
        int onError = dark ? DARK_ON_ERROR : LIGHT_ON_ERROR;
        int errorContainer = dark ? DARK_ERROR_CONTAINER : LIGHT_ERROR_CONTAINER;
        int onErrorContainer = dark ? DARK_ON_ERROR_CONTAINER : LIGHT_ON_ERROR_CONTAINER;

        Object scheme = null;
        if (settings != null) {
            scheme = presetScheme(settings, dark);
            Object dynamic = Reflect.property(settings, "dynamicColor");
            if (scheme == null && Boolean.TRUE.equals(dynamic) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    return dynamicPalette(context, dark, amoled, fontScale);
                } catch (Throwable t) {
                    XLog.i("动态取色失败，改用基线配色：" + t);
                }
            }
        }
        if (scheme != null) {
            surface = role(scheme, "Surface", surface);
            surfaceHigh = role(scheme, "SurfaceContainerHigh", surfaceHigh);
            onSurface = role(scheme, "OnSurface", onSurface);
            onSurfaceVariant = role(scheme, "OnSurfaceVariant", onSurfaceVariant);
            outlineVariant = role(scheme, "OutlineVariant", outlineVariant);
            primary = role(scheme, "Primary", primary);
            onPrimary = role(scheme, "OnPrimary", onPrimary);
            primaryContainer = role(scheme, "PrimaryContainer", primaryContainer);
            onPrimaryContainer = role(scheme, "OnPrimaryContainer", onPrimaryContainer);
            secondaryContainer = role(scheme, "SecondaryContainer", secondaryContainer);
            onSecondaryContainer = role(scheme, "OnSecondaryContainer", onSecondaryContainer);
            error = role(scheme, "Error", error);
            onError = role(scheme, "OnError", onError);
            errorContainer = role(scheme, "ErrorContainer", errorContainer);
            onErrorContainer = role(scheme, "OnErrorContainer", onErrorContainer);
        }
        if (dark && amoled) {
            surface = 0xFF000000;
        }
        return new Palette(dark, fontScale, surface, surfaceHigh, onSurface, onSurfaceVariant,
                outlineVariant, primary, onPrimary, primaryContainer, onPrimaryContainer,
                secondaryContainer, onSecondaryContainer, error, onError, errorContainer,
                onErrorContainer);
    }

    /** 宿主当前主题预生成好的 ColorScheme；自定义主题按种子色现算一份。 */
    private static Object presetScheme(Object settings, boolean dark) {
        try {
            Object themeId = Reflect.property(settings, "themeId");
            if (!(themeId instanceof String)) {
                return null;
            }
            Class<?> kt = Reflect.load(Host.loader(), "me.rerere.rikkahub.ui.theme.PresetThemeKt");
            if (kt != null) {
                Method getPresetThemes = Reflect.methodExact(kt, "getPresetThemes");
                Object presets = getPresetThemes == null ? null : getPresetThemes.invoke(null);
                if (presets instanceof List) {
                    for (Object preset : (List<?>) presets) {
                        if (themeId.equals(Reflect.property(preset, "id"))) {
                            Field field = Reflect.field(preset.getClass(),
                                    dark ? "standardDark" : "standardLight");
                            return Reflect.get(field, preset);
                        }
                    }
                }
            }
            Object customThemes = Reflect.property(settings, "customThemes");
            if (customThemes instanceof List) {
                for (Object custom : (List<?>) customThemes) {
                    if (!themeId.equals(Reflect.property(custom, "id"))) {
                        continue;
                    }
                    Method generate = Reflect.methodExact(custom.getClass(), "generateColorScheme",
                            boolean.class);
                    return generate == null ? null : generate.invoke(custom, dark);
                }
            }
        } catch (Throwable t) {
            XLog.i("读宿主主题配色失败：" + t);
        }
        return null;
    }

    /**
     * Compose 的 ColorScheme 把颜色存成打包的 ULong（高 32 位是 ARGB）。
     *
     * <p>R8 把 getter 全内联掉了，包里只剩同名的 {@code long} 字段，所以先按字段读；
     * 字段没了再退回按 getter 前缀找（getter 名字带混淆后缀，只能前缀匹配）。
     */
    private static int role(Object scheme, String role, int fallback) {
        if (scheme == null) {
            return fallback;
        }
        String fieldName = Character.toLowerCase(role.charAt(0)) + role.substring(1);
        Field field = Reflect.field(scheme.getClass(), fieldName);
        if (field != null) {
            try {
                return (int) (field.getLong(scheme) >>> 32);
            } catch (Throwable ignored) {
                // 落到 getter
            }
        }
        for (Method method : scheme.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (!name.equals("get" + role) && !name.startsWith("get" + role + "-")) {
                continue;
            }
            try {
                Class<?> type = method.getReturnType();
                if (type == long.class) {
                    return (int) (((Long) method.invoke(scheme)) >>> 32);
                }
                Object value = method.invoke(scheme);
                if (value instanceof Number) {
                    return (int) (((Number) value).longValue() >>> 32);
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return fallback;
    }

    /** Android 12+ 的动态取色，取的是 system_* 色阶，和 Compose dynamicColorScheme 同源。 */
    private static Palette dynamicPalette(Context context, boolean dark, boolean amoled, float fontScale) {
        int surface = sys(context, dark ? "system_neutral1_10" : "system_neutral1_99",
                dark ? DARK_SURFACE : LIGHT_SURFACE);
        int surfaceHigh = sys(context, dark ? "system_neutral1_22" : "system_neutral1_90",
                dark ? DARK_SURFACE_HIGH : LIGHT_SURFACE_HIGH);
        int onSurface = sys(context, dark ? "system_neutral1_90" : "system_neutral1_10",
                dark ? DARK_ON_SURFACE : LIGHT_ON_SURFACE);
        int onSurfaceVariant = sys(context, dark ? "system_neutral2_80" : "system_neutral2_30",
                dark ? DARK_ON_SURFACE_VARIANT : LIGHT_ON_SURFACE_VARIANT);
        int outlineVariant = sys(context, dark ? "system_neutral2_30" : "system_neutral2_80",
                dark ? DARK_OUTLINE_VARIANT : LIGHT_OUTLINE_VARIANT);
        int primary = sys(context, dark ? "system_accent1_80" : "system_accent1_40",
                dark ? DARK_PRIMARY : LIGHT_PRIMARY);
        int onPrimary = sys(context, dark ? "system_accent1_20" : "system_accent1_100",
                dark ? DARK_ON_PRIMARY : LIGHT_ON_PRIMARY);
        int primaryContainer = sys(context, dark ? "system_accent1_30" : "system_accent1_90",
                dark ? DARK_PRIMARY_CONTAINER : LIGHT_PRIMARY_CONTAINER);
        int onPrimaryContainer = sys(context, dark ? "system_accent1_90" : "system_accent1_10",
                dark ? DARK_ON_PRIMARY_CONTAINER : LIGHT_ON_PRIMARY_CONTAINER);
        int secondaryContainer = sys(context, dark ? "system_accent2_30" : "system_accent2_90",
                dark ? DARK_SECONDARY_CONTAINER : LIGHT_SECONDARY_CONTAINER);
        int onSecondaryContainer = sys(context, dark ? "system_accent2_90" : "system_accent2_10",
                dark ? DARK_ON_SECONDARY_CONTAINER : LIGHT_ON_SECONDARY_CONTAINER);
        int error = sys(context, dark ? "system_error_80" : "system_error_40",
                dark ? DARK_ERROR : LIGHT_ERROR);
        int onError = sys(context, dark ? "system_error_20" : "system_error_100",
                dark ? DARK_ON_ERROR : LIGHT_ON_ERROR);
        int errorContainer = sys(context, dark ? "system_error_30" : "system_error_90",
                dark ? DARK_ERROR_CONTAINER : LIGHT_ERROR_CONTAINER);
        int onErrorContainer = sys(context, dark ? "system_error_90" : "system_error_10",
                dark ? DARK_ON_ERROR_CONTAINER : LIGHT_ON_ERROR_CONTAINER);
        if (dark && amoled) {
            surface = 0xFF000000;
        }
        return new Palette(dark, fontScale, surface, surfaceHigh, onSurface, onSurfaceVariant,
                outlineVariant, primary, onPrimary, primaryContainer, onPrimaryContainer,
                secondaryContainer, onSecondaryContainer, error, onError, errorContainer,
                onErrorContainer);
    }

    /** 系统色阶是按名字取的：这些颜色没有进 android.R，不同 SDK 上有没有也得试了才知道。 */
    private static int sys(Context context, String name, int fallback) {
        try {
            int id = context.getResources().getIdentifier(name, "color", "android");
            if (id == 0) {
                return fallback;
            }
            return context.getResources().getColor(id, context.getTheme());
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
