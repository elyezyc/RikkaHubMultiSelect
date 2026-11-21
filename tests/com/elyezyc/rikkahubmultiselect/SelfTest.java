/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import kotlin.Result;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.coroutines.jvm.internal.ContinuationImpl;

/**
 * JVM 上就能验证的纯逻辑：SQL 占位符、UUID 拆分、copy$default 位掩码、data class 拷贝。
 * 不需要设备，也不需要 Xposed 运行时。
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Throwable {
        testPlaceholders();
        testUuidSplit();
        testMasks();
        testCopyWithDefaults();
        testCopyFallbackOrder();
        testSuspendBridge();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testPlaceholders() {
        check("空列表拼出空串", Sql.placeholders(0).equals(""));
        check("三个占位符", Sql.placeholders(3).equals("?,?,?"));
        check("IN 子句", Sql.inClause("id", 2).equals("id IN (?,?)"));
        check("负数按空处理", Sql.placeholders(-1).isEmpty());
    }

    private static void testUuidSplit() {
        long[] zero = Uuids.split("00000000-0000-0000-0000-000000000001");
        check("低 64 位为 1", zero[0] == 0L && zero[1] == 1L);
        long[] all = Uuids.split("ffffffffffffffffffffffffffffffff");
        check("无横线也认", all[0] == -1L && all[1] == -1L);
        long[] mixed = Uuids.split("12345678-9abc-def0-1122-334455667788");
        check("高低位拆对", mixed[0] == 0x123456789abcdef0L && mixed[1] == 0x1122334455667788L);
        boolean threw = false;
        try {
            Uuids.split("not-a-uuid");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("非法 UUID 会报错", threw);
    }

    private static void testMasks() {
        int[] one = Copy.masksFor(3, 0, 1);
        check("3 个属性改第 0 个", one.length == 1 && one[0] == 0b110);

        int[] two = Copy.masksFor(50, 47, 2);
        check("50 个属性两个掩码", two.length == 2);
        check("第一个掩码全是默认", two[0] == 0xFFFFFFFF);
        check("第二个掩码只清掉第 47 位", two[1] == (((1 << 18) - 1) & ~(1 << 15)));
    }

    private static void testCopyWithDefaults() throws Throwable {
        List<String> tags = new ArrayList<>(Arrays.asList("a", "b"));
        Fake fake = new Fake("原名", tags);
        List<String> replaced = new ArrayList<>(Arrays.asList("c"));

        Object copied = Copy.with(fake, "tags", replaced);
        check("返回的是新实例", copied instanceof Fake && copied != fake);
        Fake result = (Fake) copied;
        check("改的属性换了", result.getTags() == replaced);
        check("其它属性原样带过来", "原名".equals(result.getName()));
        check("原件没被动过", fake.getTags() == tags);
    }

    private static void testCopyFallbackOrder() throws Throwable {
        // 没有 componentN 的类走不了 copy$default，退回直接写字段并返回同一个对象
        Mutable mutable = new Mutable("x");
        Object result = Copy.with(mutable, "name", "y");
        check("回退时返回同一实例", result == mutable);
        check("回退时字段真的改了", "y".equals(mutable.getName()));
    }

    /**
     * 挂起函数桥：直接返回、挂起后由别的线程恢复、以及以失败恢复三种情况。
     * 宿主替身用的形参类型就是 R8 收窄后的 {@code ContinuationImpl}，和真机上一致。
     */
    private static void testSuspendBridge() throws Throwable {
        Suspend.init(SelfTest.class.getClassLoader());
        FakeHost host = new FakeHost();

        Method direct = FakeHost.class.getMethod("echo", int.class, ContinuationImpl.class);
        check("挂起函数直接返回", Integer.valueOf(42).equals(Suspend.call(host, direct, 21)));

        Method later = FakeHost.class.getMethod("echoLater", int.class, ContinuationImpl.class);
        check("挂起后由别的线程恢复", Integer.valueOf(42).equals(Suspend.call(host, later, 41)));

        Method failing = FakeHost.class.getMethod("failLater", ContinuationImpl.class);
        boolean threw = false;
        String message = null;
        try {
            Suspend.call(host, failing);
        } catch (IllegalStateException expected) {
            threw = true;
            message = expected.getMessage();
        }
        check("以失败恢复时抛出原异常", threw && "宿主动手了".equals(message));
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok   " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }

    /** 模仿 Kotlin data class 生成的方法形状。 */
    public static final class Fake {
        private final String name;
        private final List<String> tags;

        public Fake(String name, List<String> tags) {
            this.name = name;
            this.tags = tags;
        }

        public String getName() {
            return name;
        }

        public List<String> getTags() {
            return tags;
        }

        public String component1() {
            return name;
        }

        public List<String> component2() {
            return tags;
        }

        public Fake copy(String name, List<String> tags) {
            return new Fake(name, tags);
        }

        public static Fake copy$default(Fake self, String name, List<String> tags, int mask, Object marker) {
            if ((mask & 1) != 0) {
                name = self.name;
            }
            if ((mask & 2) != 0) {
                tags = self.tags;
            }
            return self.copy(name, tags);
        }
    }

    /** 宿主挂起函数的替身。 */
    public static final class FakeHost {

        public Object echo(int value, ContinuationImpl continuation) {
            return value * 2;
        }

        public Object echoLater(final int value, final ContinuationImpl continuation) {
            new Thread(() -> continuation.resumeWith(value + 1), "fake-host").start();
            return CoroutineSingletons.COROUTINE_SUSPENDED;
        }

        public Object failLater(final ContinuationImpl continuation) {
            new Thread(() -> continuation.resumeWith(
                    new Result.Failure(new IllegalStateException("宿主动手了"))), "fake-host").start();
            return CoroutineSingletons.COROUTINE_SUSPENDED;
        }
    }

    /** 不是 data class：没有 copy / componentN，字段也是 final。 */
    public static final class Mutable {
        private final String name;

        public Mutable(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
