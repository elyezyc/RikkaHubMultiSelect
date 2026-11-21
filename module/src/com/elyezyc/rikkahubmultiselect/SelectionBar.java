/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多选时浮在底部的一条操作栏，和 RikkaHub 自己选消息时那条悬浮工具栏是一个位置、一个形状：
 * 底部居中、大圆角、surfaceContainer 底色、带阴影。
 *
 * <p>选中状态本身发生在宿主列表里（点击行切换），这里只负责显示数量和批量动作。
 */
final class SelectionBar {

    private static final String TAG = "rikkahubmultiselect.bar";

    private static Activity activity;
    private static View bar;
    private static LinearLayout content;
    private static ExecutorService worker;
    private static boolean busy;

    private SelectionBar() {
    }

    // ---------------------------------------------------------------- 显示

    static void show(Activity host) {
        if (host == null || host.isFinishing()) {
            return;
        }
        activity = host;
        View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) decor;
        root.post(new Runnable() {
            @Override
            public void run() {
                if (bar != null && bar.getParent() == root && Selection.mode() != Selection.NONE) {
                    refresh();
                    return;
                }
                detach();
                build(root);
            }
        });
    }

    private static void build(ViewGroup root) {
        Palette palette = Palette.resolve(activity);
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "RikkaHubMultiSelect");
                thread.setDaemon(true);
                return thread;
            });
        }

        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8),
                Ui.dp(activity, 8), Ui.dp(activity, 8));
        content.setBackground(Ui.rounded(activity, palette.surfaceContainerHigh, 28f));

        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.TRANSPARENT);
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setTag(TAG);
        wrapper.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.setElevation(Ui.dp(activity, 6));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.leftMargin = Ui.dp(activity, 16);
        params.rightMargin = Ui.dp(activity, 16);
        params.bottomMargin = Ui.dp(activity, 24);
        root.addView(wrapper, params);
        bar = wrapper;

        refresh();
    }

    static void refresh() {
        if (bar == null || content == null || activity == null || activity.isFinishing()) {
            return;
        }
        if (Selection.mode() == Selection.NONE) {
            detach();
            return;
        }
        final Palette palette = Palette.resolve(activity);
        content.removeAllViews();

        TextView close = Ui.text(activity, "✕", palette.sp(15f), palette.onSurfaceVariant,
                Typeface.NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.rounded(activity, palette.surfaceContainerHigh, 20f));
        close.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8),
                Ui.dp(activity, 10), Ui.dp(activity, 8));
        close.setOnClickListener(v -> {
            Selection.exit();
            detach();
        });
        addChip(close, 4);

        TextView count = Ui.text(activity,
                T.f("已选 %d 项", "%d selected", Selection.size()),
                palette.sp(13f), palette.onSurface, Typeface.NORMAL);
        count.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        addChip(count, 2);
        count.setOnClickListener(null);

        switch (Selection.mode()) {
            case Selection.PROVIDERS:
                chip(palette, T.s("启用", "Enable"), () -> run("启用提供商",
                        ids -> T.f("已启用 %d 个提供商", "Enabled %d providers",
                                Actions.setProvidersEnabled(ids, true))));
                chip(palette, T.s("停用", "Disable"), () -> run("停用提供商",
                        ids -> T.f("已停用 %d 个提供商", "Disabled %d providers",
                                Actions.setProvidersEnabled(ids, false))));
                chip(palette, T.s("删除", "Delete"), () -> confirmDeleteProviders());
                break;
            case Selection.ASSISTANTS:
                chip(palette, T.s("设为当前", "Make current"), () -> makeCurrent());
                chip(palette, T.s("克隆", "Clone"), () -> run("克隆助手",
                        ids -> T.f("已克隆 %d 个助手", "Cloned %d assistants",
                                Actions.cloneAssistants(ids))));
                chip(palette, T.s("删除", "Delete"), () -> confirmDeleteAssistants());
                break;
            default:
                chip(palette, T.s("置顶", "Pin"), () -> run("置顶对话",
                        ids -> T.f("已置顶 %d 个对话", "Pinned %d chats",
                                Actions.pin(ids, true))));
                chip(palette, T.s("取消置顶", "Unpin"), () -> run("取消置顶",
                        ids -> T.f("已取消置顶 %d 个对话", "Unpinned %d chats",
                                Actions.pin(ids, false))));
                chip(palette, T.s("移助手", "Move"), () -> pickAssistant());
                chip(palette, T.s("移文件夹", "Folder"), () -> pickFolder());
                chip(palette, T.s("重生成标题", "Retitle"), () -> run("重新生成标题",
                        ids -> T.f("已请求重新生成 %d 个标题", "Requested %d title refreshes",
                                Actions.regenerateTitles(ids))));
                chip(palette, T.s("删除", "Delete"), () -> confirmDeleteConversations());
                break;
        }
    }

    private static void addChip(TextView view, float marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(activity, marginDp);
        params.rightMargin = Ui.dp(activity, marginDp);
        content.addView(view, params);
    }

    private static void chip(Palette palette, CharSequence label, Runnable action) {
        TextView view = Ui.text(activity, label, palette.sp(13f), palette.onSecondaryContainer,
                Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        view.setBackground(Ui.rounded(activity, palette.secondaryContainer, 18f));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> {
            if (!busy) {
                action.run();
            }
        });
        addChip(view, 3);
    }

    /** 进入多选前先把旧的那条收掉（重复点「多选」时不会叠两条）。 */
    static void hideIfIdle() {
        if (Selection.mode() == Selection.NONE) {
            detach();
        }
    }

    static void detach() {
        try {
            if (bar != null && bar.getParent() instanceof ViewGroup) {
                ((ViewGroup) bar.getParent()).removeView(bar);
            }
        } catch (Throwable ignored) {
            // 窗口可能已经没了
        }
        bar = null;
        content = null;
    }

    // ---------------------------------------------------------------- 动作

    private interface Task {
        String run(List<String> ids) throws Throwable;
    }

    private static void run(String label, Task task) {
        if (busy || activity == null || Selection.size() == 0) {
            return;
        }
        final List<String> ids = Selection.ids();
        busy = true;
        worker.execute(() -> {
            String message = null;
            Throwable failure = null;
            try {
                message = task.run(ids);
            } catch (Throwable t) {
                failure = t;
                XLog.e(label + " 失败", t);
            }
            final String success = message;
            final Throwable error = failure;
            activity.runOnUiThread(() -> {
                busy = false;
                if (error != null) {
                    ToastHelper.error(activity, error);
                    refresh();
                    return;
                }
                ToastHelper.show(activity, success);
                Selection.exit();
                detach();
            });
        });
    }

    private static void pickAssistant() {
        worker.execute(() -> {
            final List<SettingsApi.AssistantRow> assistants;
            try {
                assistants = SettingsApi.loadAssistants();
            } catch (Throwable t) {
                ToastHelper.error(activity, t);
                return;
            }
            // 第一个是「移出助手」之外的普通选择：直接列全部助手
            final String[] names = new String[assistants.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = assistants.get(i).name;
            }
            activity.runOnUiThread(() -> new android.app.AlertDialog.Builder(activity)
                    .setTitle(T.s("移动到哪个助手", "Move to assistant"))
                    .setItems(names, (dialog, which) -> {
                        final String target = assistants.get(which).id;
                        run("移动对话", ids -> T.f("已移动 %d 个对话", "Moved %d chats",
                                Actions.moveToAssistant(ids, target)));
                    })
                    .setNegativeButton(T.s("取消", "Cancel"), null)
                    .show());
        });
    }

    private static void pickFolder() {
        worker.execute(() -> {
            String assistantId = null;
            for (String id : Selection.ids()) {
                Object[] row = Selection.row(id);
                if (row == null || row[0] == null) {
                    continue;
                }
                String owner = String.valueOf(Reflect.property(row[0], "assistantId"));
                if (assistantId == null) {
                    assistantId = owner;
                } else if (!assistantId.equals(owner)) {
                    activity.runOnUiThread(() -> ToastHelper.show(activity, T.s(
                            "只能把同一个助手的对话移到文件夹里",
                            "Only chats of the same assistant can be moved together")));
                    return;
                }
            }
            if (assistantId == null) {
                return;
            }
            final List<Conversations.Folder> folders;
            try {
                folders = Conversations.loadFolders(assistantId);
            } catch (Throwable t) {
                ToastHelper.error(activity, t);
                return;
            }
            if (folders.isEmpty()) {
                activity.runOnUiThread(() -> ToastHelper.show(activity,
                        T.s("还没有文件夹，先在会话列表里新建一个", "No folders yet")));
                return;
            }
            final String[] names = new String[folders.size() + 1];
            names[0] = T.s("移出文件夹", "Remove from folder");
            for (int i = 0; i < folders.size(); i++) {
                names[i + 1] = folders.get(i).name;
            }
            activity.runOnUiThread(() -> new android.app.AlertDialog.Builder(activity)
                    .setTitle(T.s("移到哪个文件夹", "Move to folder"))
                    .setItems(names, (dialog, which) -> {
                        final String folderId = which == 0 ? "" : folders.get(which - 1).id;
                        run("移动到文件夹", ids -> T.f("已移动 %d 个对话", "Moved %d chats",
                                Actions.moveToFolder(ids, folderId)));
                    })
                    .setNegativeButton(T.s("取消", "Cancel"), null)
                    .show());
        });
    }

    private static void makeCurrent() {
        final List<String> ids = Selection.ids();
        if (ids.size() != 1) {
            ToastHelper.show(activity, T.s("只能选一个助手", "Pick exactly one assistant"));
            return;
        }
        run("切换助手", ignored -> {
            Actions.selectAssistant(ids.get(0));
            return T.s("已切换当前助手", "Current assistant switched");
        });
    }

    private static void confirmDeleteConversations() {
        final int count = Selection.size();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(T.s("删除对话", "Delete chats"))
                .setMessage(T.f("将删除 %d 个对话，附件和搜索索引会一起清理，此操作不可撤销。",
                        "Delete %d chats? Attachments and the search index are cleaned up too. "
                                + "This cannot be undone.", count))
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialog, which) ->
                        run("删除对话", ids -> T.f("已删除 %d 个对话", "Deleted %d chats",
                                Actions.deleteConversations(ids))))
                .show();
    }

    private static void confirmDeleteProviders() {
        int builtIn = 0;
        try {
            for (SettingsApi.ProviderRow row : SettingsApi.loadProviders()) {
                if (row.builtIn && Selection.contains(row.id)) {
                    builtIn++;
                }
            }
        } catch (Throwable t) {
            XLog.e("读取提供商失败", t);
        }
        if (builtIn == Selection.size()) {
            ToastHelper.show(activity, T.s(
                    "选中的都是内置提供商，RikkaHub 会把它们写回来，删不掉",
                    "All selected providers are built-in and cannot be deleted"));
            return;
        }
        final int deletable = Selection.size() - builtIn;
        new android.app.AlertDialog.Builder(activity)
                .setTitle(T.s("删除提供商", "Delete providers"))
                .setMessage(builtIn > 0
                        ? T.f("将删除 %d 个提供商；另有 %d 个内置提供商会跳过。",
                        "Delete %d providers? %d built-in ones are skipped.", deletable, builtIn)
                        : T.f("将删除 %d 个提供商，此操作不可撤销。",
                        "Delete %d providers? This cannot be undone.", deletable))
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialog, which) ->
                        run("删除提供商", ids -> T.f("已删除 %d 个提供商", "Deleted %d providers",
                                Actions.deleteProviders(ids))))
                .show();
    }

    private static void confirmDeleteAssistants() {
        try {
            if (Selection.size() >= SettingsApi.loadAssistants().size()) {
                ToastHelper.show(activity, T.s("至少要保留一个助手", "At least one assistant must remain"));
                return;
            }
        } catch (Throwable t) {
            XLog.e("读取助手失败", t);
        }
        final int count = Selection.size();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(T.s("删除助手", "Delete assistants"))
                .setMessage(T.f("将删除 %d 个助手，它们名下的对话也会一起删除，此操作不可撤销。",
                        "Delete %d assistants? Their chats are deleted as well. "
                                + "This cannot be undone.", count))
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialog, which) ->
                        run("删除助手", ids -> {
                            int[] result = Actions.deleteAssistants(ids);
                            return T.f("已删除 %d 个助手，连带清理 %d 个对话",
                                    "Deleted %d assistants and %d chats", result[0], result[1]);
                        }))
                .show();
    }

}
