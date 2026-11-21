/*
 * RikkaHubMultiSelect - 给 RikkaHub 加上对话 / 提供商 / 助手的多选批量操作
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubmultiselect;

import android.database.Cursor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话列表与批量操作。
 *
 * <ul>
 *   <li>列表：走宿主的 Room 连接读 {@code conversationentity}（本来 Room 就只留了分页接口，
 *       直接读表比把宿主的 Paging 流翻出来可靠）。</li>
 *   <li>删除：走宿主的 {@code ConversationRepository.deleteConversation}，搜索索引、收藏、附件文件
 *       都由宿主自己清理。</li>
 *   <li>置顶 / 换助手：只是改一列，包在 Room 的事务里执行，宿主那边的触发器会照常通知界面刷新。</li>
 * </ul>
 */
final class Conversations {

    static final class Row {
        final String id;
        final String title;
        final String assistantId;
        final String assistantName;
        final boolean pinned;
        final long updatedAt;

        Row(String id, String title, String assistantId, String assistantName, boolean pinned, long updatedAt) {
            this.id = id;
            this.title = title;
            this.assistantId = assistantId;
            this.assistantName = assistantName;
            this.pinned = pinned;
            this.updatedAt = updatedAt;
        }
    }

    private static final String SELECT =
            "SELECT id, title, assistant_id, is_pinned, update_at FROM conversationentity "
                    + "ORDER BY is_pinned DESC, update_at DESC";

    private static Class<?> supportDatabaseClass;
    private static Class<?> supportHelperClass;

    private Conversations() {
    }

    static void init(ClassLoader loader) {
        supportDatabaseClass = Reflect.load(loader, "androidx.sqlite.db.SupportSQLiteDatabase");
        supportHelperClass = Reflect.load(loader, "androidx.sqlite.db.SupportSQLiteOpenHelper");
        if (supportDatabaseClass == null || supportHelperClass == null) {
            XLog.i("找不到 androidx.sqlite 的接口，会话功能可能不可用");
        }
    }

    static List<Row> load() throws Throwable {
        Map<String, String> names = assistantNames();
        List<Row> rows = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = query(SELECT, null);
            int idIndex = cursor.getColumnIndexOrThrow("id");
            int titleIndex = cursor.getColumnIndexOrThrow("title");
            int assistantIndex = cursor.getColumnIndexOrThrow("assistant_id");
            int pinnedIndex = cursor.getColumnIndexOrThrow("is_pinned");
            int updateIndex = cursor.getColumnIndexOrThrow("update_at");
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                String title = cursor.getString(titleIndex);
                String assistantId = cursor.getString(assistantIndex);
                boolean pinned = cursor.getInt(pinnedIndex) != 0;
                long updatedAt = cursor.getLong(updateIndex);
                String assistantName = names.get(assistantId);
                if (assistantName == null) {
                    assistantName = T.s("未知助手", "Unknown assistant");
                }
                if (title == null || title.trim().isEmpty()) {
                    title = T.s("（无标题对话）", "(untitled chat)");
                }
                rows.add(new Row(id, title, assistantId, assistantName, pinned, updatedAt));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return rows;
    }

    /** @return 真正删掉的会话数量 */
    static int delete(List<String> ids) throws Throwable {
        Object repo = Host.repository();
        Method getById = Reflect.method(Host.repositoryClass(), "getConversationById", 2);
        Method delete = Reflect.method(Host.repositoryClass(), "deleteConversation", 2);
        if (getById == null || delete == null) {
            throw new NoSuchMethodException("ConversationRepository 的删除接口变了");
        }
        int deleted = 0;
        for (String id : ids) {
            Object conversation = Suspend.call(repo, getById, Uuids.parse(id));
            if (conversation != null) {
                Suspend.call(repo, delete, conversation);
                deleted++;
            }
        }
        return deleted;
    }

    static int pin(List<String> ids, boolean pinned) throws Throwable {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE conversationentity SET is_pinned = ? WHERE " + Sql.inClause("id", ids.size());
        Object[] args = new Object[ids.size() + 1];
        args[0] = pinned ? 1 : 0;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        write(sql, args);
        return ids.size();
    }

    static int moveToAssistant(List<String> ids, String assistantId) throws Throwable {
        if (ids.isEmpty()) {
            return 0;
        }
        // 和宿主 updateAssistantId 一致：换助手的同时把文件夹归属清掉
        String sql = "UPDATE conversationentity SET assistant_id = ?, folder_id = '' WHERE "
                + Sql.inClause("id", ids.size());
        Object[] args = new Object[ids.size() + 1];
        args[0] = assistantId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        write(sql, args);
        return ids.size();
    }

    // ---------------------------------------------------------------- 数据库

    private static Map<String, String> assistantNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (Object assistant : SettingsApi.assistants(SettingsApi.current())) {
                names.put(SettingsApi.assistantId(assistant), SettingsApi.assistantName(assistant));
            }
        } catch (Throwable t) {
            XLog.e("读取助手名称失败", t);
        }
        return names;
    }

    private static Object writableDatabase() throws Throwable {
        if (supportHelperClass == null) {
            throw new IllegalStateException("androidx.sqlite 接口不可用");
        }
        Method getHelper = Reflect.methodExact(Host.roomDatabaseClass(), "getOpenHelper");
        Object helper = Reflect.call(getHelper, Host.database());
        Method getWritable = supportHelperClass.getMethod("getWritableDatabase");
        return getWritable.invoke(helper);
    }

    private static Cursor query(String sql, Object[] args) throws Throwable {
        if (supportDatabaseClass == null) {
            throw new IllegalStateException("androidx.sqlite 接口不可用");
        }
        Object database = writableDatabase();
        Method query = args == null
                ? supportDatabaseClass.getMethod("query", String.class)
                : supportDatabaseClass.getMethod("query", String.class, Object[].class);
        return (Cursor) query.invoke(database, args == null ? new Object[]{sql} : new Object[]{sql, args});
    }

    private static void exec(String sql, Object[] args) throws Throwable {
        if (supportDatabaseClass == null) {
            throw new IllegalStateException("androidx.sqlite 接口不可用");
        }
        Object database = writableDatabase();
        Method exec = supportDatabaseClass.getMethod("execSQL", String.class, Object[].class);
        exec.invoke(database, sql, args);
    }

    /** 包在 Room 的事务里写，宿主依赖的触发器才会发出变更通知。 */
    private static void write(final String sql, final Object[] args) throws Throwable {
        Method runInTransaction = Reflect.methodExact(Host.roomDatabaseClass(), "runInTransaction",
                Reflect.load(Host.loader(), "kotlin.jvm.functions.Function0"));
        if (runInTransaction == null) {
            exec(sql, args);
            return;
        }
        Object database = Host.database();
        Reflect.call(runInTransaction, database, Host.newFunction0(new Runnable() {
            @Override
            public void run() {
                try {
                    exec(sql, args);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }
        }));
    }
}
