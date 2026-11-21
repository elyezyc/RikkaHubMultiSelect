/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.ContinuationImpl;

/**
 * 把 Kotlin 的 {@code suspend} 函数当同步函数调用。
 *
 * <p>做法是给挂起函数传一个自己的续体（{@link Waiter}）：函数要么直接返回结果，要么返回
 * {@code COROUTINE_SUSPENDED} 并在之后的某个线程上恢复续体。调用方线程一直等到续体被恢复为止。
 *
 * <p>续体必须是 {@code kotlin.coroutines.jvm.internal.ContinuationImpl} 的子类，因为 R8 会把不少
 * 挂起函数的续体形参从 {@code Continuation} 收窄成这个具体类，普通 {@code Continuation} 会被反射拒收。
 * 这类调用都从后台线程发起，所以阻塞等待是安全的。
 */
final class Suspend {

    /** {@code kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED}。 */
    private static Object coroutineSuspended;

    /** {@code kotlin.Result.Failure}：续体以失败恢复时传的就是它的实例。 */
    private static Class<?> failureType;
    private static Method failureGetter;
    private static java.lang.reflect.Field failureField;

    /** 单次宿主操作最多等这么久，避免界面卡死在某个永远不恢复的续体上。 */
    private static final long TIMEOUT_MS = 60_000L;

    private Suspend() {
    }

    static void init(ClassLoader loader) {
        Class<?> singletons = Reflect.load(loader, "kotlin.coroutines.intrinsics.CoroutineSingletons");
        if (singletons != null) {
            coroutineSuspended = Reflect.get(Reflect.field(singletons, "COROUTINE_SUSPENDED"), null);
        }
        if (coroutineSuspended == null) {
            XLog.i("找不到 COROUTINE_SUSPENDED，将按类名判断挂起");
        }

        Class<?> failure = Reflect.load(loader, "kotlin.Result$Failure");
        if (failure != null) {
            failureType = failure;
            failureGetter = Reflect.method(failure, "getException", 0);
            failureField = Reflect.field(failure, "exception");
        }
    }

    /**
     * 阻塞调用一个挂起函数。
     *
     * @param target 调用目标
     * @param method 挂起函数（最后一个形参是续体）
     * @param args   业务形参，续体会被自动追加
     * @return 挂起函数的结果（{@code Unit} 类型时是 kotlin.Unit 实例）
     */
    static Object call(Object target, Method method, Object... args) throws Throwable {
        if (method == null) {
            throw new IllegalStateException("宿主方法不存在");
        }
        Waiter waiter = new Waiter();
        Object[] full = new Object[args.length + 1];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = waiter;

        Object result;
        try {
            result = method.invoke(target, full);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        }

        if (isSuspended(result)) {
            waiter.await(TIMEOUT_MS);
            if (waiter.error != null) {
                throw waiter.error;
            }
            return waiter.value;
        }
        return result;
    }

    private static boolean isSuspended(Object result) {
        if (result == null) {
            return false;
        }
        if (coroutineSuspended != null) {
            return result == coroutineSuspended;
        }
        return result.getClass().getName().startsWith("kotlin.coroutines.intrinsics.CoroutineSingletons");
    }

    private static Throwable failureOf(Object result) {
        try {
            if (failureGetter != null) {
                Object value = failureGetter.invoke(result);
                if (value instanceof Throwable) {
                    return (Throwable) value;
                }
            }
            if (failureField != null) {
                Object value = failureField.get(result);
                if (value instanceof Throwable) {
                    return (Throwable) value;
                }
            }
        } catch (Throwable ignored) {
            // 下面给个通用错误
        }
        return new IllegalStateException("宿主协程以失败结束（无法读取异常）");
    }

    /** 真正的续体：宿主恢复它时，把值或异常记下来并唤醒等待线程。 */
    private static final class Waiter extends ContinuationImpl {

        private final CountDownLatch latch = new CountDownLatch(1);

        private volatile Object value;
        private volatile Throwable error;

        private Waiter() {
            super(NoOp.INSTANCE);
        }

        @Override
        protected Object invokeSuspend(Object result) {
            if (result != null && failureType != null && failureType.isInstance(result)) {
                error = failureOf(result);
            } else {
                value = result;
            }
            latch.countDown();
            return null;
        }

        private void await(long timeoutMs) throws InterruptedException {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("宿主操作超过 " + (timeoutMs / 1000) + " 秒没有返回");
            }
        }
    }

    /** 续体需要一个「下游」：结果没人接，丢掉即可。 */
    private static final class NoOp implements Continuation<Object> {

        private static final NoOp INSTANCE = new NoOp();

        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object result) {
            // 结果由 Waiter 自己保存
        }
    }
}
