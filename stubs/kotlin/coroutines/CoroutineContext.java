/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin.coroutines;

/**
 * 只为了编译期能写出 {@code Continuation} / {@code ContinuationImpl} 的子类。
 *
 * <p>运行时会链接到宿主 APK 里真实的 kotlin-stdlib，这里的替身不会被 dex 打包
 * （构建脚本只把 {@code com/elyezyc/...} 下的 class 交给 d8）。
 */
public interface CoroutineContext {
}
