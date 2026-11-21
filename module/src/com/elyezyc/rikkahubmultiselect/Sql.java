/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

/** 拼 SQL 的小工具，纯字符串处理。 */
final class Sql {

    private Sql() {
    }

    /** {@code ?,?,?} 这样的占位符串。 */
    static String placeholders(int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }

    /** {@code id IN (?,?,?)}。 */
    static String inClause(String column, int count) {
        return column + " IN (" + placeholders(count) + ")";
    }
}
