/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.SparseArray;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 底部操作栏用的图标。
 *
 * <p>宿主那套 HugeIcons 是 Compose 的 ImageVector，没法直接画到普通 View 上，所以这里用
 * Material Icons 的路径数据自己画。{@code androidx.core} 自带 {@code PathParser}，
 * 把这些路径串变成 {@link Path} 就行；解析器找不到时退化成圆点，至少不会崩。
 */
final class Glyph {

    private static final Map<String, String> PATHS = new HashMap<>();
    private static final SparseArray<Bitmap> CACHE = new SparseArray<>();

    private static Method pathParser;

    static {
        PATHS.put("close",
                "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 "
                        + "17.59,19 19,17.59 13.41,12z");
        PATHS.put("check",
                "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z");
        PATHS.put("pin",
                "M16,12V4h1V2H7v2h1v8l-2,2v2h5.2v6h1.6v-6H18v-2l-2,-2z");
        PATHS.put("unpin",
                "M16,12V4h1V2H7v2h1v8l-2,2v2h5.2v6h1.6v-6H18v-2l-2,-2z");
        PATHS.put("forward",
                "M12,8V4l8,8 -8,8v-4H4V8z");
        PATHS.put("folder",
                "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 "
                        + "2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z");
        PATHS.put("refresh",
                "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 "
                        + "7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 "
                        + "-3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z");
        PATHS.put("delete",
                "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z");
        PATHS.put("block",
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"
                        + "M4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12z"
                        + "M12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z");
        PATHS.put("copy",
                "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 "
                        + "0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z");
        PATHS.put("select_all",
                "M3,5h2L5,3c-1.1,0 -2,0.9 -2,2zM3,13h2v-2L3,11v2zM7,21h2v-2L7,19v2zM3,9h2L5,7L3,7v2z"
                        + "M13,3h-2v2h2L13,3zM19,3v2h2c0,-1.1 -0.9,-2 -2,-2zM9,3L7,3v2h2L9,3z"
                        + "M5,21v-2L3,19c0,1.1 0.9,2 2,2zM3,17h2v-2L3,15v2zM19,13h2v-2h-2v2z"
                        + "M19,21c1.1,0 2,-0.9 2,-2h-2v2zM19,9h2L21,7h-2v2zM19,17h2v-2h-2v2z"
                        + "M15,21h2v-2h-2v2zM15,5h2L17,3h-2v2zM7,17h10L17,7L7,7v10zM9,9h6v6L9,15L9,9z");
    }

    private Glyph() {
    }

    static void draw(Canvas canvas, String name, float widthPx, float heightPx, int color) {
        Bitmap bitmap = bitmap(name, Math.round(widthPx), color);
        if (bitmap == null) {
            return;
        }
        float left = (widthPx - bitmap.getWidth()) / 2f;
        float top = (heightPx - bitmap.getHeight()) / 2f;
        canvas.drawBitmap(bitmap, left, top, null);
    }

    private static Bitmap bitmap(String name, int sizePx, int color) {
        String pathData = PATHS.get(name);
        if (pathData == null || sizePx <= 0) {
            return null;
        }
        int key = (name.hashCode() * 31 + color) * 31 + sizePx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Path path = parse(pathData);
        if (path == null) {
            return null;
        }
        float scale = sizePx / 24f;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(scale, scale);
        path.transform(matrix);

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        if ("unpin".equals(name)) {
            // 取消置顶：在同一个 pin 上划一道斜杠
            Paint slash = new Paint(Paint.ANTI_ALIAS_FLAG);
            slash.setColor(color);
            slash.setStyle(Paint.Style.STROKE);
            slash.setStrokeWidth(Math.max(1.5f, sizePx / 10f));
            slash.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(sizePx * 0.18f, sizePx * 0.82f, sizePx * 0.82f, sizePx * 0.18f, slash);
        }
        CACHE.put(key, bitmap);
        return bitmap;
    }

    private static Path parse(String data) {
        if (pathParser == null) {
            try {
                Class<?> type = Class.forName("androidx.core.graphics.PathParser");
                pathParser = type.getMethod("createPathFromPathData", String.class);
                pathParser.setAccessible(true);
            } catch (Throwable t) {
                XLog.e("找不到 androidx.core 的 PathParser", t);
                return null;
            }
        }
        try {
            return (Path) pathParser.invoke(null, data);
        } catch (Throwable t) {
            XLog.e("解析图标路径失败", t);
            return null;
        }
    }
}
