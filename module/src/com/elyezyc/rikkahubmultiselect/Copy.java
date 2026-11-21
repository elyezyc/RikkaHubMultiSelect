/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 改 Kotlin data class 的某个属性并拿到一个新实例。
 *
 * <p>走编译器生成的 {@code copy$default}：把要改的属性之外都标成「用默认值」，就不用关心
 * {@code Settings} 那五十多个构造参数分别是什么。属性下标靠 {@code componentN()} 的同一性反查
 * （{@code componentN} 的顺序就是构造参数顺序）。这条路走不通时退回到直接写字段。
 */
final class Copy {

    private Copy() {
    }

    static Object with(Object target, String property, Object value) throws Throwable {
        Class<?> type = target.getClass();
        Object current = Reflect.property(target, property);

        int index = indexOf(type, target, current);
        if (index >= 0) {
            Object copied = invokeCopyDefault(type, target, index, value);
            if (copied != null && Reflect.property(copied, property) == value) {
                return copied;
            }
        }

        // 兜底：Kotlin 的 val 编译成 private final 实例字段，ART 允许反射写入
        Field field = Reflect.field(type, property);
        if (field == null) {
            throw new NoSuchFieldException(type.getName() + "#" + property);
        }
        field.set(target, value);
        if (Reflect.property(target, property) != value) {
            throw new IllegalStateException("写入属性失败：" + property);
        }
        return target;
    }

    /** 用 componentN 的顺序找属性下标；靠对象同一性判断，避免同名同值的属性串位。 */
    private static int indexOf(Class<?> type, Object target, Object current) {
        if (current == null) {
            return -1;
        }
        for (int i = 1; ; i++) {
            Method component = Reflect.method(type, "component" + i, 0);
            if (component == null) {
                return -1;
            }
            try {
                if (component.invoke(target) == current) {
                    return i - 1;
                }
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }

    private static Object invokeCopyDefault(Class<?> type, Object target, int index, Object value) {
        Method copy = null;
        Method copyDefault = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("copy")) {
                continue;
            }
            if (method.getName().equals("copy") && method.getParameterCount() > 1
                    && !Reflect.isStatic(method) && !method.isSynthetic()) {
                copy = method;
            }
        }
        // copy$default 的名字不是 "copy"，单独再扫一遍
        for (Method method : type.getDeclaredMethods()) {
            if ("copy$default".equals(method.getName()) && Reflect.isStatic(method)) {
                copyDefault = method;
                break;
            }
        }
        if (copy == null || copyDefault == null) {
            return null;
        }

        int properties = copy.getParameterCount();
        int total = copyDefault.getParameterCount();
        int masks = total - properties - 2;
        if (masks <= 0 || index >= properties) {
            return null;
        }

        Class<?>[] propertyTypes = copy.getParameterTypes();
        Object[] args = new Object[total];
        args[0] = target;
        for (int i = 0; i < properties; i++) {
            args[1 + i] = i == index ? value : zeroOf(propertyTypes[i]);
        }
        int[] mask = masksFor(properties, index, masks);
        for (int m = 0; m < masks; m++) {
            args[1 + properties + m] = mask[m];
        }
        args[total - 1] = null;

        try {
            copyDefault.setAccessible(true);
            return copyDefault.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * copy$default 的位掩码：第 i 位为 1 表示第 i 个属性用原件里的值。
     * 属性超过 32 个时按每 32 个一个 int 依次排列（{@code Settings} 就是两个）。
     */
    static int[] masksFor(int properties, int index, int maskCount) {
        int[] mask = new int[maskCount];
        for (int i = 0; i < properties; i++) {
            if (i != index) {
                mask[i / 32] |= 1 << (i % 32);
            }
        }
        return mask;
    }

    private static Object zeroOf(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
