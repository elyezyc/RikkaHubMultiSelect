/*
 * RikkaHubMultiSelect - 编译期替身，不进 APK
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package kotlin;

/** 编译期替身：续体以失败恢复时，传的是 {@code Result.Failure} 实例。 */
public final class Result {

    private Result() {
    }

    public static final class Failure {
        public final Throwable exception;

        public Failure(Throwable exception) {
            this.exception = exception;
        }
    }
}
