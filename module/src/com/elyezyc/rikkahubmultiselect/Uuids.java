/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigInteger;

/**
 * 把数据库里的 id 字符串还原成宿主的 {@code kotlin.uuid.Uuid}。
 *
 * <p>RikkaHub 用 R8 处理过，{@code Uuid.Companion.parse} 被内联后连 Companion 类都没了，
 * 所以这里直接用 {@code Uuid(long, long)} 构造。构造参数顺序按字段名核对：
 * 造完之后读回 {@code mostSignificantBits} / {@code leastSignificantBits}，
 * 对得上才认为成功，两种顺序都试一遍。
 */
final class Uuids {

    private static Constructor<?> constructor;
    private static Field most;
    private static Field least;

    private Uuids() {
    }

    static void init(ClassLoader loader) throws Throwable {
        Class<?> type = Reflect.load(loader, "kotlin.uuid.Uuid");
        if (type == null) {
            throw new ClassNotFoundException("kotlin.uuid.Uuid");
        }
        constructor = Reflect.constructor(type, long.class, long.class);
        most = Reflect.field(type, "mostSignificantBits");
        least = Reflect.field(type, "leastSignificantBits");
        if (constructor == null || most == null || least == null) {
            throw new NoSuchMethodException("kotlin.uuid.Uuid 的构造方式变了");
        }
    }

    /** 把 32 位十六进制拆成高 / 低 64 位（Uuid 内部就是这两个 long）。 */
    static long[] split(String text) {
        String hex = text == null ? "" : text.replace("-", "").trim();
        if (hex.length() != 32) {
            throw new IllegalArgumentException("不是合法的 UUID：" + text);
        }
        return new long[]{
                new BigInteger(hex.substring(0, 16), 16).longValue(),
                new BigInteger(hex.substring(16), 16).longValue(),
        };
    }

    /** 造一个新的随机 Uuid（克隆助手时用）。 */
    static Object random() throws Throwable {
        if (constructor == null) {
            throw new IllegalStateException("Uuid 反射还没初始化");
        }
        java.util.Random random = new java.util.Random();
        long msb = random.nextLong();
        long lsb = random.nextLong();
        Object direct = constructor.newInstance(msb, lsb);
        if (most.getLong(direct) == msb && least.getLong(direct) == lsb) {
            return direct;
        }
        return constructor.newInstance(lsb, msb);
    }

    static Object parse(String text) throws Throwable {
        if (constructor == null) {
            throw new IllegalStateException("Uuid 反射还没初始化");
        }
        long[] parts = split(text);
        long msb = parts[0];
        long lsb = parts[1];

        Object direct = constructor.newInstance(msb, lsb);
        if (most.getLong(direct) == msb && least.getLong(direct) == lsb) {
            return direct;
        }
        Object swapped = constructor.newInstance(lsb, msb);
        if (most.getLong(swapped) == msb && least.getLong(swapped) == lsb) {
            return swapped;
        }
        throw new IllegalStateException("Uuid 构造参数顺序无法确认：" + text);
    }
}
