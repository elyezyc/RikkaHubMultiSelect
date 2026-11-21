/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 反射小工具。模块不编译期依赖宿主，所有宿主类型都按名字取。
 *
 * <p>约定：找不到就返回 {@code null} / 抛异常，由调用方决定是降级还是报错。
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 沿继承链找字段。 */
    static Field field(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 往父类走
            }
        }
        return null;
    }

    static Object get(Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean set(Field field, Object target, Object value) {
        if (field == null) {
            return false;
        }
        try {
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 按名字 + 形参个数找方法（沿继承链）。 */
    static Method method(Class<?> type, String name, int parameterCount) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /** 按名字 + 形参类型精确找方法（沿继承链）。 */
    static Method methodExact(Class<?> type, String name, Class<?>... types) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                // 往父类走
            }
        }
        return null;
    }

    /** 公开方法（用于调宿主 / 平台类的公开 API）。 */
    static Method publicMethod(Class<?> type, String name, Class<?>... types) {
        try {
            return type.getMethod(name, types);
        } catch (Throwable ignored) {
            return methodExact(type, name, types);
        }
    }

    static Constructor<?> constructor(Class<?> type, Class<?>... types) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调用并剥掉 InvocationTargetException，让调用方看到宿主真正抛出的异常。 */
    static Object call(Method method, Object target, Object... args) throws Throwable {
        if (method == null) {
            throw new IllegalStateException("方法不存在");
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        }
    }

    /** 读属性：先找 getter，再找同名字段。 */
    static Object property(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method getter = publicMethod(type, "get" + capitalized);
        if (getter != null) {
            try {
                return getter.invoke(target);
            } catch (Throwable ignored) {
                // 落到字段
            }
        }
        Field field = field(type, name);
        return get(field, target);
    }

    static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }
}
