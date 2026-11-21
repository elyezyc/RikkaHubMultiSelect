/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置（提供商、助手）的读写。
 *
 * <p>写设置走宿主自己的 {@code SettingsStore.update}：它内部会先规整再落盘，和用户在设置页里
 * 点按钮是同一条路径。返回值用 {@code copy$default} 造，不直接改宿主手里的那份对象。
 */
final class SettingsApi {

    static final class ProviderRow {
        final String id;
        final String name;
        final String type;
        final boolean enabled;
        final boolean builtIn;
        final int models;

        ProviderRow(String id, String name, String type, boolean enabled, boolean builtIn, int models) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.enabled = enabled;
            this.builtIn = builtIn;
            this.models = models;
        }
    }

    static final class AssistantRow {
        final String id;
        final String name;

        AssistantRow(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private SettingsApi() {
    }

    static Object current() throws Throwable {
        return Host.settings();
    }

    @SuppressWarnings("unchecked")
    static List<Object> providers(Object settings) {
        Object value = Reflect.property(settings, "providers");
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> assistants(Object settings) {
        Object value = Reflect.property(settings, "assistants");
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    static String providerId(Object provider) {
        return String.valueOf(Reflect.property(provider, "id"));
    }

    static String providerName(Object provider) {
        Object name = Reflect.property(provider, "name");
        return name == null ? "" : name.toString();
    }

    static boolean providerEnabled(Object provider) {
        return Boolean.TRUE.equals(Reflect.property(provider, "enabled"));
    }

    static boolean providerBuiltIn(Object provider) {
        return Boolean.TRUE.equals(Reflect.property(provider, "builtIn"));
    }

    static int providerModels(Object provider) {
        Object models = Reflect.property(provider, "models");
        return models instanceof List ? ((List<?>) models).size() : 0;
    }

    /** 提供商类型：OpenAI / Google / Claude / ChatGPT / Grok，取子类名。 */
    static String providerType(Object provider) {
        String simple = provider.getClass().getSimpleName();
        int dollar = simple.lastIndexOf('$');
        return dollar >= 0 ? simple.substring(dollar + 1) : simple;
    }

    /** 复制一个提供商并改 enabled；其它字段（含内置标记、描述）原样带过去。 */
    static Object copyProvider(Object provider, boolean enabled) throws Throwable {
        Method copy = Reflect.method(provider.getClass(), "copyProvider", 8);
        if (copy == null) {
            throw new NoSuchMethodException("ProviderSetting#copyProvider");
        }
        return copy.invoke(provider,
                Reflect.property(provider, "id"),
                enabled,
                Reflect.property(provider, "name"),
                Reflect.property(provider, "models"),
                Reflect.property(provider, "customHeaders"),
                Reflect.property(provider, "builtIn"),
                Reflect.property(provider, "description"),
                Reflect.property(provider, "shortDescription"));
    }

    static List<ProviderRow> loadProviders() throws Throwable {
        List<ProviderRow> rows = new ArrayList<>();
        for (Object provider : providers(current())) {
            rows.add(new ProviderRow(
                    providerId(provider),
                    providerName(provider),
                    providerType(provider),
                    providerEnabled(provider),
                    providerBuiltIn(provider),
                    providerModels(provider)));
        }
        return rows;
    }

    static List<AssistantRow> loadAssistants() throws Throwable {
        List<AssistantRow> rows = new ArrayList<>();
        for (Object assistant : assistants(current())) {
            rows.add(new AssistantRow(assistantId(assistant), assistantName(assistant)));
        }
        return rows;
    }

    static String assistantId(Object assistant) {
        return String.valueOf(Reflect.property(assistant, "id"));
    }

    static String assistantName(Object assistant) {
        Object name = Reflect.property(assistant, "name");
        String text = name == null ? "" : name.toString().trim();
        return text.isEmpty() ? T.s("（未命名助手）", "(unnamed assistant)") : text;
    }

    static void update(Host.Transform transform) throws Throwable {
        Method update = Reflect.method(Host.settingsStoreClass(), "update", 2);
        if (update == null) {
            throw new NoSuchMethodException("SettingsStore#update");
        }
        Suspend.call(Host.settingsStore(), update, Host.newFunction1(transform));
    }

    /** @return 实际改动的条数 */
    static int setProvidersEnabled(final List<String> ids, final boolean enabled) throws Throwable {
        final Set<String> wanted = new HashSet<>(ids);
        final int[] changed = {0};
        update(new Host.Transform() {
            @Override
            public Object apply(Object settings) throws Throwable {
                List<Object> providers = providers(settings);
                List<Object> out = new ArrayList<>(providers.size());
                for (Object provider : providers) {
                    if (wanted.contains(providerId(provider))) {
                        out.add(copyProvider(provider, enabled));
                        changed[0]++;
                    } else {
                        out.add(provider);
                    }
                }
                return Copy.with(settings, "providers", out);
            }
        });
        return changed[0];
    }

    /** @return 实际删掉的条数（内置的会被跳过） */
    static int deleteProviders(final List<String> ids) throws Throwable {
        final Set<String> wanted = new HashSet<>(ids);
        final int[] removed = {0};
        update(new Host.Transform() {
            @Override
            public Object apply(Object settings) throws Throwable {
                List<Object> providers = providers(settings);
                List<Object> out = new ArrayList<>(providers.size());
                for (Object provider : providers) {
                    if (wanted.contains(providerId(provider)) && !providerBuiltIn(provider)) {
                        removed[0]++;
                    } else {
                        out.add(provider);
                    }
                }
                if (removed[0] == 0) {
                    return settings;
                }
                return Copy.with(settings, "providers", out);
            }
        });
        return removed[0];
    }

    /** @return 实际删掉的助手数量 */
    static int deleteAssistants(final List<String> ids) throws Throwable {
        final Set<String> wanted = new HashSet<>(ids);
        final int[] removed = {0};
        update(new Host.Transform() {
            @Override
            public Object apply(Object settings) throws Throwable {
                List<Object> assistants = assistants(settings);
                List<Object> out = new ArrayList<>(assistants.size());
                for (Object assistant : assistants) {
                    if (wanted.contains(assistantId(assistant))) {
                        removed[0]++;
                    } else {
                        out.add(assistant);
                    }
                }
                if (removed[0] == 0) {
                    return settings;
                }
                if (out.isEmpty()) {
                    throw new IllegalStateException(T.s("至少要保留一个助手", "At least one assistant must remain"));
                }
                return Copy.with(settings, "assistants", out);
            }
        });
        return removed[0];
    }

    /** 克隆助手：和宿主自己复制助手一样，复制一份改个名字。 */
    static int cloneAssistants(final List<String> ids) throws Throwable {
        final int[] cloned = {0};
        update(new Host.Transform() {
            @Override
            public Object apply(Object settings) throws Throwable {
                List<Object> assistants = assistants(settings);
                List<Object> out = new ArrayList<>(assistants);
                for (Object assistant : assistants) {
                    if (!ids.contains(assistantId(assistant))) {
                        continue;
                    }
                    Object copy = Copy.with(assistant, "id", Uuids.random());
                    Object name = Reflect.property(assistant, "name");
                    String text = name == null ? "" : name.toString();
                    copy = Copy.with(copy, "name", text + " (Clone)");
                    out.add(copy);
                    cloned[0]++;
                }
                if (cloned[0] == 0) {
                    return settings;
                }
                return Copy.with(settings, "assistants", out);
            }
        });
        return cloned[0];
    }

    /** 把当前助手切换成指定 id。 */
    static void selectAssistant(final String id) throws Throwable {
        final Object target = findAssistant(id);
        if (target == null) {
            throw new IllegalStateException(T.s("找不到这个助手", "Assistant not found"));
        }
        update(new Host.Transform() {
            @Override
            public Object apply(Object settings) throws Throwable {
                return Copy.with(settings, "assistantId", Reflect.property(target, "id"));
            }
        });
    }

    private static Object findAssistant(String id) throws Throwable {
        for (Object assistant : assistants(current())) {
            if (id.equals(assistantId(assistant))) {
                return assistant;
            }
        }
        return null;
    }
}
