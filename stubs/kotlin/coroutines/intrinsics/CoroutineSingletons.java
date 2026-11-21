/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin.coroutines.intrinsics;

/** 编译期替身：挂起函数「真的挂起了」时的哨兵值。 */
public enum CoroutineSingletons {
    COROUTINE_SUSPENDED,
    UNDECIDED,
    RESUMED,
}
