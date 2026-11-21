/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin.coroutines;

/** 编译期替身：真实签名为 {@code resumeWith(result: Result<T>)}，擦除后是 {@code (Object)V}。 */
public interface Continuation<T> {

    CoroutineContext getContext();

    void resumeWith(Object result);
}
