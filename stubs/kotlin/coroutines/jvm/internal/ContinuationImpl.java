/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin.coroutines.jvm.internal;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;

/**
 * 编译期替身：宿主里真正被用来当「挂起函数最后一个参数」的类型。
 *
 * <p>R8 会把 {@code suspend} 函数的续体参数从 {@code kotlin.coroutines.Continuation}
 * 收窄成这个具体类（例如 {@code ConversationRepository.deleteConversation}），
 * 所以模块必须交一个<b>真是它的子类</b>的实例进去，普通 {@code Continuation} 会被反射拒收。
 */
public abstract class ContinuationImpl implements Continuation<Object> {

    public ContinuationImpl(Continuation<Object> completion) {
    }

    public ContinuationImpl(Continuation<Object> completion, CoroutineContext context) {
    }

    @Override
    public CoroutineContext getContext() {
        return null;
    }

    /**
     * 替身版的恢复逻辑：真实实现（BaseContinuationImpl）会转交给 invokeSuspend 并处理后继流程，
     * 这里只保留跑单元测试需要的那一步。
     */
    @Override
    public void resumeWith(Object result) {
        invokeSuspend(result);
    }

    /** 状态机入口：续体被恢复时由 BaseContinuationImpl 调用。 */
    protected abstract Object invokeSuspend(Object result);
}
