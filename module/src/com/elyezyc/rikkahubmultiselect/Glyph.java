/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 底部操作栏的图标，直接用 Canvas 画线条图形（24×24 坐标系，按控件大小缩放）。
 *
 * <p>不用图片也不用 Compose 的 ImageVector：样式是细线条 + 圆头，和 RikkaHub 那套 stroke 图标接近，
 * 而且不依赖宿主的资源表，纯代码画一定画得出来。
 */
final class Glyph {

    private Glyph() {
    }

    static void draw(Canvas canvas, String name, float width, float height, int color) {
        float size = Math.min(width, height);
        if (size <= 0f) {
            return;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2f);

        canvas.save();
        canvas.translate((width - size) / 2f, (height - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        switch (name) {
            case "close":
                canvas.drawLine(7f, 7f, 17f, 17f, paint);
                canvas.drawLine(17f, 7f, 7f, 17f, paint);
                break;
            case "check": {
                Path path = new Path();
                path.moveTo(5f, 12.5f);
                path.lineTo(10f, 17.5f);
                path.lineTo(19f, 7f);
                canvas.drawPath(path, paint);
                break;
            }
            case "pin":
                drawThumbtack(canvas, paint, false);
                break;
            case "unpin":
                drawThumbtack(canvas, paint, true);
                break;
            case "forward": {
                canvas.drawLine(5f, 12f, 18f, 12f, paint);
                Path path = new Path();
                path.moveTo(12.5f, 6.5f);
                path.lineTo(18f, 12f);
                path.lineTo(12.5f, 17.5f);
                canvas.drawPath(path, paint);
                break;
            }
            case "folder": {
                Path path = new Path();
                path.moveTo(4f, 8f);
                path.lineTo(4f, 19f);
                path.lineTo(20f, 19f);
                path.lineTo(20f, 9.5f);
                path.lineTo(12f, 9.5f);
                path.lineTo(10f, 7f);
                path.lineTo(5.5f, 7f);
                path.lineTo(4.5f, 8f);
                canvas.drawPath(path, paint);
                break;
            }
            case "refresh": {
                Path arc = new Path();
                android.graphics.RectF box = new android.graphics.RectF(5f, 5f, 19f, 19f);
                arc.addArc(box, -60f, 285f);
                canvas.drawPath(arc, paint);
                Path head = new Path();
                head.moveTo(15.5f, 3.5f);
                head.lineTo(18.5f, 7.2f);
                head.lineTo(14f, 8.2f);
                canvas.drawPath(head, paint);
                break;
            }
            case "delete": {
                canvas.drawLine(4.5f, 7f, 19.5f, 7f, paint);
                Path body = new Path();
                body.moveTo(6.5f, 7f);
                body.lineTo(7.3f, 19.5f);
                body.lineTo(16.7f, 19.5f);
                body.lineTo(17.5f, 7f);
                canvas.drawPath(body, paint);
                Path handle = new Path();
                handle.moveTo(9.5f, 7f);
                handle.lineTo(9.5f, 4.5f);
                handle.lineTo(14.5f, 4.5f);
                handle.lineTo(14.5f, 7f);
                canvas.drawPath(handle, paint);
                break;
            }
            case "copy": {
                canvas.drawRoundRect(new android.graphics.RectF(9f, 9f, 19f, 19f), 2f, 2f, paint);
                Path back = new Path();
                back.moveTo(15f, 9f);
                back.lineTo(15f, 5.5f);
                back.lineTo(5f, 5.5f);
                back.lineTo(5f, 15f);
                back.lineTo(9f, 15f);
                canvas.drawPath(back, paint);
                break;
            }
            case "block":
                canvas.drawCircle(12f, 12f, 7.5f, paint);
                canvas.drawLine(6.7f, 17.3f, 17.3f, 6.7f, paint);
                break;
            case "select_all": {
                canvas.drawRoundRect(new android.graphics.RectF(4f, 4f, 20f, 20f), 2.5f, 2.5f, paint);
                Path check = new Path();
                check.moveTo(8f, 12f);
                check.lineTo(11f, 15f);
                check.lineTo(16.5f, 9f);
                canvas.drawPath(check, paint);
                break;
            }
            default:
                canvas.drawCircle(12f, 12f, 7f, paint);
                break;
        }
        canvas.restore();
    }

    /** 大头钉：宽帽 + 颈部 + 针，和宿主那套 Pin 图标形状接近。 */
    private static void drawThumbtack(Canvas canvas, Paint paint, boolean struck) {
        canvas.drawRoundRect(new android.graphics.RectF(6.5f, 4f, 17.5f, 8.2f), 1.6f, 1.6f, paint);
        Path body = new Path();
        body.moveTo(10f, 8.2f);
        body.lineTo(10f, 11.6f);
        body.lineTo(14f, 11.6f);
        body.lineTo(14f, 8.2f);
        canvas.drawPath(body, paint);
        canvas.drawLine(12f, 11.6f, 12f, 19.5f, paint);
        if (struck) {
            canvas.drawLine(5f, 19f, 19f, 5f, paint);
        }
    }
}
