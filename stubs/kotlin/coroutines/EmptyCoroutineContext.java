/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin.coroutines;

/** 编译期替身：Kotlin 里的 {@code object EmptyCoroutineContext}，JVM 上有一份 INSTANCE。 */
public final class EmptyCoroutineContext implements CoroutineContext {

    public static final EmptyCoroutineContext INSTANCE = new EmptyCoroutineContext();

    private EmptyCoroutineContext() {
    }
}
