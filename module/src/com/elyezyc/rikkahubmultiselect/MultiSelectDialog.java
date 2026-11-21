/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多选面板。做成底部面板（RikkaHub 里选提供商、选模型这类操作用的也是 ModalBottomSheet）：
 * 顶部圆角、拖动条、一行标题，下面是「对话 / 提供商 / 助手」三个切换按钮、复选列表，
 * 底部是这一栏能做的批量动作。
 *
 * <p>颜色取宿主当前主题（见 {@link Palette}），字体尺寸跟宿主的 displaySetting.fontSizeRatio 走。
 * 所有宿主操作都在后台线程跑，界面只负责收集 id 和显示结果。
 */
final class MultiSelectDialog {

    private static MultiSelectDialog current;

    private static final int TAB_CONVERSATIONS = 0;
    private static final int TAB_PROVIDERS = 1;
    private static final int TAB_ASSISTANTS = 2;

    private final Activity activity;
    private final Dialog dialog;
    private final Palette palette;
    private final ExecutorService worker;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final List<Item> items = new ArrayList<>();
    private final List<SettingsApi.ProviderRow> providerRows = new ArrayList<>();
    private final List<SettingsApi.AssistantRow> assistantRows = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private final TextView[] tabs = new TextView[3];

    private int tab = TAB_CONVERSATIONS;
    private boolean busy;

    private View sheet;
    private ListView listView;
    private ProgressBar progress;
    private TextView summary;
    private TextView selectAllLabel;
    private LinearLayout actionBar;

    private MultiSelectDialog(Activity activity, int initialTab) {
        this.activity = activity;
        this.dialog = new Dialog(activity);
        this.palette = Palette.resolve(activity);
        this.tab = initialTab;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RikkaHubMultiSelect");
            thread.setDaemon(true);
            return thread;
        });
    }

    static void show(final Activity activity) {
        show(activity, TAB_CONVERSATIONS);
    }

    static void show(final Activity activity, int tab) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (current != null) {
            current.close();
        }
        MultiSelectDialog dialog = new MultiSelectDialog(activity, tab);
        current = dialog;
        dialog.open();
    }

    static void dismissFor(Activity activity) {
        if (current != null && current.activity == activity) {
            current.close();
        }
    }

    // ---------------------------------------------------------------- 界面

    private void open() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        sheet = buildSheet();
        dialog.setContentView(sheet);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface ignored) {
                shutdown();
                if (current == MultiSelectDialog.this) {
                    current = null;
                }
            }
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.32f;
            window.setAttributes(attributes);
            window.setGravity(Gravity.BOTTOM);
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout(metrics.widthPixels, Math.round(metrics.heightPixels * 0.86f));
        }
        animateIn();

        buildActions();
        styleTabs();
        adapter.notifyDataSetChanged();
        updateSummary();
        reload();
    }

    private void animateIn() {
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.animate().translationY(0f).setDuration(240L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private View buildSheet() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.sheetBackground(activity, palette.surface, 28f));
        root.setPadding(dp(16), dp(6), dp(16), dp(20));

        View handle = new View(activity);
        handle.setBackground(Ui.rounded(activity, Ui.alpha(palette.onSurfaceVariant, 0.4f), 2f));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(8);
        root.addView(handle, handleParams);

        root.addView(buildHeader());
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = dp(10);
        root.addView(buildTabs(), tabsParams);

        FrameLayout body = new FrameLayout(activity);
        listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(6));
        listView.setAdapter(adapter);
        body.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(activity);
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER;
        body.addView(progress, progressParams);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildFooter());
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(10), 0, 0);

        TextView title = Ui.text(activity, T.s("多选", "Select"), palette.sp(22f),
                palette.onSurface, Typeface.NORMAL);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(activity, "✕", palette.sp(16f), palette.onSurfaceVariant, Typeface.NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.rounded(activity, palette.surfaceContainerHigh, 18f));
        close.setClickable(true);
        close.setFocusable(true);
        int size = dp(36);
        header.addView(close, new LinearLayout.LayoutParams(size, size));
        return header;
    }

    private View buildTabs() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = {
                T.s("对话", "Chats"),
                T.s("提供商", "Providers"),
                T.s("助手", "Assistants"),
        };
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            TextView view = Ui.text(activity, labels[i], palette.sp(14f), palette.onSurfaceVariant,
                    Typeface.NORMAL);
            view.setGravity(Gravity.CENTER);
            view.setMinHeight(dp(40));
            view.setClickable(true);
            view.setFocusable(true);
            view.setOnClickListener(v -> selectTab(target));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = dp(2);
            params.rightMargin = dp(2);
            bar.addView(view, params);
            tabs[i] = view;
        }
        return bar;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(4), 0, 0);

        LinearLayout summaryRow = new LinearLayout(activity);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        summary = Ui.text(activity, "", palette.sp(12f), palette.onSurfaceVariant, Typeface.NORMAL);
        summaryRow.addView(summary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectAllLabel = Ui.textButton(activity, palette, T.s("全选", "Select all"));
        selectAllLabel.setOnClickListener(v -> toggleAll());
        summaryRow.addView(selectAllLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        footer.addView(summaryRow);

        actionBar = new LinearLayout(activity);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(0, dp(6), 0, 0);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(actionBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        footer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return footer;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Item item = items.get(position);
            boolean active = selected.contains(item.id);

            LinearLayout outer = new LinearLayout(activity);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(0, dp(2), 0, dp(2));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(Ui.rounded(activity,
                    active ? palette.secondaryContainer : Color.TRANSPARENT, 16f));

            CheckBox check = new CheckBox(activity);
            check.setChecked(active);
            check.setClickable(false);
            check.setFocusable(false);
            check.setButtonTintList(ColorStateList.valueOf(palette.primary));
            row.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(8), 0, 0, 0);
            TextView title = Ui.itemTitle(activity, palette, item.title,
                    active ? palette.onSecondaryContainer : palette.onSurface);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView subtitle = Ui.itemSupporting(activity, palette, item.subtitle,
                    active ? Ui.alpha(palette.onSecondaryContainer, 0.75f) : palette.onSurfaceVariant);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            texts.addView(subtitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            outer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            outer.setOnClickListener(v -> {
                item.checked = !item.checked;
                if (item.checked) {
                    selected.add(item.id);
                } else {
                    selected.remove(item.id);
                }
                notifyDataSetChanged();
                updateSummary();
                updateActions();
            });
            return outer;
        }
    };

    // ---------------------------------------------------------------- 数据

    private static final class Item {
        final String id;
        final String title;
        final String subtitle;
        boolean checked;

        Item(String id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final class Loaded {
        final List<Item> items = new ArrayList<>();
        final List<SettingsApi.ProviderRow> providers = new ArrayList<>();
        final List<SettingsApi.AssistantRow> assistants = new ArrayList<>();
    }

    private Loaded load(int target) throws Throwable {
        Loaded loaded = new Loaded();
        loaded.assistants.addAll(SettingsApi.loadAssistants());
        switch (target) {
            case TAB_PROVIDERS: {
                loaded.providers.addAll(SettingsApi.loadProviders());
                for (SettingsApi.ProviderRow row : loaded.providers) {
                    StringBuilder subtitle = new StringBuilder(row.type);
                    subtitle.append(" · ").append(T.f("%d 个模型", "%d models", row.models));
                    subtitle.append(" · ").append(row.enabled
                            ? T.s("已启用", "enabled") : T.s("已停用", "disabled"));
                    if (row.builtIn) {
                        subtitle.append(" · ").append(T.s("内置", "built-in"));
                    }
                    loaded.items.add(new Item(row.id, row.name, subtitle.toString()));
                }
                break;
            }
            case TAB_ASSISTANTS: {
                for (SettingsApi.AssistantRow row : loaded.assistants) {
                    loaded.items.add(new Item(row.id, row.name, T.s("助手", "Assistant")));
                }
                break;
            }
            default: {
                for (Conversations.Row row : Conversations.load()) {
                    StringBuilder subtitle = new StringBuilder(row.assistantName);
                    if (row.updatedAt > 0) {
                        subtitle.append(" · ").append(formatTime(row.updatedAt));
                    }
                    if (row.pinned) {
                        subtitle.append(" · ").append(T.s("已置顶", "pinned"));
                    }
                    loaded.items.add(new Item(row.id, row.title, subtitle.toString()));
                }
                break;
            }
        }
        return loaded;
    }

    private void reload() {
        if (busy) {
            return;
        }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        updateActions();
        final int requested = tab;
        worker.execute(() -> {
            Loaded loaded = null;
            Throwable failure = null;
            try {
                loaded = load(requested);
            } catch (Throwable t) {
                failure = t;
                XLog.e("读取列表失败", t);
            }
            final Loaded result = loaded;
            final Throwable error = failure;
            ui(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (error != null) {
                    ToastHelper.error(activity, error);
                    updateActions();
                    return;
                }
                items.clear();
                items.addAll(result.items);
                providerRows.clear();
                providerRows.addAll(result.providers);
                assistantRows.clear();
                assistantRows.addAll(result.assistants);
                pruneSelection();
                adapter.notifyDataSetChanged();
                updateSummary();
                updateActions();
            });
        });
    }

    private void pruneSelection() {
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (Item item : items) {
            if (selected.contains(item.id)) {
                item.checked = true;
                keep.add(item.id);
            }
        }
        selected.clear();
        selected.addAll(keep);
    }

    // ---------------------------------------------------------------- 交互

    private void selectTab(int target) {
        if (target == tab) {
            return;
        }
        tab = target;
        selected.clear();
        items.clear();
        adapter.notifyDataSetChanged();
        buildActions();
        styleTabs();
        updateSummary();
        reload();
    }

    private void styleTabs() {
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) {
                continue;
            }
            boolean active = i == tab;
            tabs[i].setTextColor(active ? palette.onSecondaryContainer : palette.onSurfaceVariant);
            tabs[i].setTypeface(Typeface.create(tabs[i].getTypeface(),
                    active ? Typeface.BOLD : Typeface.NORMAL));
            tabs[i].setBackground(active
                    ? Ui.rounded(activity, palette.secondaryContainer, 20f)
                    : Ui.rounded(activity, Color.TRANSPARENT, 20f));
        }
    }

    private void toggleAll() {
        boolean all = !items.isEmpty() && selected.size() == items.size();
        selected.clear();
        for (Item item : items) {
            item.checked = !all;
            if (!all) {
                selected.add(item.id);
            }
        }
        adapter.notifyDataSetChanged();
        updateSummary();
        updateActions();
    }

    private void updateSummary() {
        summary.setText(T.f("已选 %d 项 / 共 %d 项", "%d of %d selected", selected.size(), items.size()));
        boolean all = !items.isEmpty() && selected.size() == items.size();
        selectAllLabel.setText(all ? T.s("取消全选", "Clear") : T.s("全选", "Select all"));
    }

    private void updateActions() {
        for (Action action : actions) {
            boolean enabled = !busy
                    && selected.size() >= action.minimum
                    && (action.maximum <= 0 || selected.size() <= action.maximum);
            action.view.setEnabled(enabled);
            action.view.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    // ---------------------------------------------------------------- 动作

    private static final class Action {
        final TextView view;
        final int minimum;
        final int maximum;

        Action(TextView view, int minimum, int maximum) {
            this.view = view;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }

    private void buildActions() {
        actionBar.removeAllViews();
        actions.clear();
        switch (tab) {
            case TAB_PROVIDERS:
                addAction(T.s("启用", "Enable"), false, 1, 0, v -> runOperation("启用提供商",
                        ids -> T.f("已启用 %d 个提供商", "Enabled %d providers",
                                Actions.setProvidersEnabled(ids, true))));
                addAction(T.s("停用", "Disable"), false, 1, 0, v -> runOperation("停用提供商",
                        ids -> T.f("已停用 %d 个提供商", "Disabled %d providers",
                                Actions.setProvidersEnabled(ids, false))));
                addAction(T.s("删除", "Delete"), true, 1, 0, v -> confirmDeleteProviders());
                break;
            case TAB_ASSISTANTS:
                addAction(T.s("设为当前", "Make current"), false, 1, 1, v -> runOperation("切换助手",
                        ids -> {
                            Actions.selectAssistant(ids.get(0));
                            return T.s("已切换当前助手", "Current assistant switched");
                        }));
                addAction(T.s("删除", "Delete"), true, 1, 0, v -> confirmDeleteAssistants());
                break;
            default:
                addAction(T.s("置顶", "Pin"), false, 1, 0, v -> runOperation("置顶对话",
                        ids -> T.f("已置顶 %d 个对话", "Pinned %d chats", Actions.pin(ids, true))));
                addAction(T.s("取消置顶", "Unpin"), false, 1, 0, v -> runOperation("取消置顶",
                        ids -> T.f("已取消置顶 %d 个对话", "Unpinned %d chats", Actions.pin(ids, false))));
                addAction(T.s("移到助手", "Move"), false, 1, 0, v -> pickAssistant());
                addAction(T.s("删除", "Delete"), true, 1, 0, v -> confirmDeleteConversations());
                break;
        }
        updateActions();
    }

    private void addAction(CharSequence label, boolean destructive, int minimum, int maximum,
                           View.OnClickListener listener) {
        TextView view = Ui.tonalButton(activity, palette, label, destructive);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.rightMargin = dp(8);
        actionBar.addView(view, params);
        actions.add(new Action(view, minimum, maximum));
    }

    private interface Operation {
        String run(List<String> ids) throws Throwable;
    }

    private void runOperation(String label, Operation operation) {
        if (busy || selected.isEmpty()) {
            return;
        }
        final List<String> ids = new ArrayList<>(selected);
        busy = true;
        progress.setVisibility(View.VISIBLE);
        updateActions();
        worker.execute(() -> {
            String message = null;
            Throwable failure = null;
            try {
                message = operation.run(ids);
            } catch (Throwable t) {
                failure = t;
                XLog.e(label + " 失败", t);
            }
            final String success = message;
            final Throwable error = failure;
            ui(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (error != null) {
                    ToastHelper.error(activity, error);
                    updateActions();
                    return;
                }
                ToastHelper.show(activity, success);
                selected.clear();
                reload();
            });
        });
    }

    private void pickAssistant() {
        if (assistantRows.isEmpty()) {
            ToastHelper.show(activity, T.s("没有可以移动到的助手", "No assistant to move to"));
            return;
        }
        final String[] names = new String[assistantRows.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = assistantRows.get(i).name;
        }
        new AlertDialog.Builder(activity)
                .setTitle(T.s("移动到哪个助手", "Move to assistant"))
                .setItems(names, (dialogInterface, which) -> {
                    final String assistantId = assistantRows.get(which).id;
                    runOperation("移动对话", ids -> T.f("已移动 %d 个对话", "Moved %d chats",
                            Actions.moveToAssistant(ids, assistantId)));
                })
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .show();
    }

    private void confirmDeleteConversations() {
        final int count = selected.size();
        new AlertDialog.Builder(activity)
                .setTitle(T.s("删除对话", "Delete chats"))
                .setMessage(T.f("将删除 %d 个对话，附件和搜索索引会一起清理，此操作不可撤销。",
                        "Delete %d chats? Attachments and the search index are cleaned up too. "
                                + "This cannot be undone.", count))
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialogInterface, which) ->
                        runOperation("删除对话", ids -> T.f("已删除 %d 个对话", "Deleted %d chats",
                                Actions.deleteConversations(ids))))
                .show();
    }

    private void confirmDeleteProviders() {
        int builtIn = 0;
        for (SettingsApi.ProviderRow row : providerRows) {
            if (row.builtIn && selected.contains(row.id)) {
                builtIn++;
            }
        }
        int deletable = selected.size() - builtIn;
        if (deletable <= 0) {
            ToastHelper.show(activity, T.s(
                    "选中的都是内置提供商，RikkaHub 会把它们写回来，删不掉",
                    "All selected providers are built-in; RikkaHub re-adds them, so they cannot be deleted"));
            return;
        }
        String message = builtIn > 0
                ? T.f("将删除 %d 个提供商；另有 %d 个内置提供商会跳过（RikkaHub 会自动写回）。",
                "Delete %d providers? %d built-in ones are skipped because RikkaHub re-adds them.",
                deletable, builtIn)
                : T.f("将删除 %d 个提供商，此操作不可撤销。",
                "Delete %d providers? This cannot be undone.", deletable);
        new AlertDialog.Builder(activity)
                .setTitle(T.s("删除提供商", "Delete providers"))
                .setMessage(message)
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialogInterface, which) ->
                        runOperation("删除提供商", ids -> T.f(
                                "已删除 %d 个提供商", "Deleted %d providers",
                                Actions.deleteProviders(ids))))
                .show();
    }

    private void confirmDeleteAssistants() {
        if (selected.size() >= assistantRows.size()) {
            ToastHelper.show(activity, T.s("至少要保留一个助手", "At least one assistant must remain"));
            return;
        }
        final int count = selected.size();
        new AlertDialog.Builder(activity)
                .setTitle(T.s("删除助手", "Delete assistants"))
                .setMessage(T.f("将删除 %d 个助手，它们名下的对话也会一起删除，此操作不可撤销。",
                        "Delete %d assistants? Their chats are deleted as well. This cannot be undone.",
                        count))
                .setNegativeButton(T.s("取消", "Cancel"), null)
                .setPositiveButton(T.s("删除", "Delete"), (dialogInterface, which) ->
                        runOperation("删除助手", ids -> {
                            int[] result = Actions.deleteAssistants(ids);
                            return T.f("已删除 %d 个助手，连带清理 %d 个对话",
                                    "Deleted %d assistants and %d chats", result[0], result[1]);
                        }))
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    private void ui(Runnable runnable) {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || !dialog.isShowing()) {
                return;
            }
            try {
                runnable.run();
            } catch (Throwable t) {
                XLog.e("界面更新失败", t);
            }
        });
    }

    private void close() {
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // 窗口可能已经没了
        }
        shutdown();
        if (current == this) {
            current = null;
        }
    }

    private void shutdown() {
        worker.shutdownNow();
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private static String formatTime(long millis) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(millis));
        } catch (Throwable ignored) {
            return "";
        }
    }
}
