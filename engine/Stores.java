package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** JSON 表仓储（configs.json / history.json / keep.json，格式与 C# 版完全兼容）。 */
public final class Stores {
    public static final long HISTORY_TIME = 60L * 24 * 60 * 60 * 1000; // 保留 60 天

    private static final Object LOCK = new Object();
    private static List<JsonObject> configs, histories, keeps, tombstones;
    private static long historyRevision, keepRevision;

    public static long now() { return System.currentTimeMillis(); }
    public static long historyRevision() { return historyRevision; }
    public static long keepRevision() { return keepRevision; }

    /** 清空内存缓存（云同步还原后强制重新读盘）。 */
    public static void clearCache() {
        synchronized (LOCK) {
            configs = null;
            histories = null;
            keeps = null;
            tombstones = null;
        }
    }

    private static File configFile() { return new File(AppPaths.Root, "configs.json"); }
    private static File historyFile() { return new File(AppPaths.Root, "history.json"); }
    private static File keepFile() { return new File(AppPaths.Root, "keep.json"); }
    private static File tombFile() { return new File(AppPaths.Root, "config-deleted.json"); }

    private static List<JsonObject> loadList(File f) {
        List<JsonObject> list = new ArrayList<>();
        String text = JsonUtil.readFile(f);
        if (text == null) return list;
        JsonArray arr = JsonUtil.parseArr(text);
        if (arr == null) return list;
        for (JsonElement e : arr) {
            try { if (e.isJsonObject()) list.add(e.getAsJsonObject()); } catch (Exception ignored) { }
        }
        return list;
    }

    private static void saveList(File f, List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : list) arr.add(o);
        JsonUtil.writeFile(f, JsonUtil.gson.toJson(arr));
    }

    // ---------- Config ----------
    public static List<JsonObject> configs() {
        synchronized (LOCK) {
            if (configs == null) configs = loadList(configFile());
            return configs;
        }
    }

    public static JsonObject findConfig(String url, int type) {
        synchronized (LOCK) {
            for (JsonObject c : configs()) {
                if (url.equals(JsonUtil.str(c, "url", "")) && JsonUtil.integer(c, "type", 0) == type) return c;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", configs().isEmpty() ? 1 : maxId() + 1);
            item.addProperty("type", type);
            item.addProperty("time", now());
            item.addProperty("url", url);
            item.addProperty("name", "");
            item.addProperty("logo", "");
            item.addProperty("home", "");
            item.addProperty("parse", "");
            item.addProperty("notice", "");
            item.addProperty("danmaku", "");
            configs().add(item);
            return item;
        }
    }

    private static int maxId() {
        int max = 0;
        for (JsonObject c : configs()) max = Math.max(max, JsonUtil.integer(c, "id", 0));
        return max;
    }

    public static void saveConfig(JsonObject item) {
        synchronized (LOCK) {
            item.addProperty("time", now());
            List<JsonObject> snapshot = new ArrayList<>(configs());
            snapshot.sort(Comparator.comparingLong((JsonObject c) -> JsonUtil.lng(c, "time", 0)).reversed());
            saveList(configFile(), snapshot);
        }
    }

    /** 配置列表有增删（不改动 time/顺序）时直接落盘。 */
    public static void persistConfigs() {
        synchronized (LOCK) {
            saveList(configFile(), configs());
        }
    }

    public static void deleteConfig(String url, int type) {
        synchronized (LOCK) {
            configs().removeIf(c -> url.equals(JsonUtil.str(c, "url", "")) && JsonUtil.integer(c, "type", 0) == type);
            saveList(configFile(), configs());
            addTombstoneLocked(url, type, now());
        }
    }

    // ---------- 配置删除标记（让"删除"也能跨设备同步，不会被云端旧列表加回来） ----------
    public static List<JsonObject> tombstones() {
        synchronized (LOCK) {
            if (tombstones == null) tombstones = loadList(tombFile());
            return tombstones;
        }
    }

    public static void addTombstone(String url, int type, long time) {
        synchronized (LOCK) {
            addTombstoneLocked(url, type, time);
        }
    }

    private static void addTombstoneLocked(String url, int type, long time) {
        if (url == null || url.isEmpty()) return;
        List<JsonObject> list = tombstones();
        for (JsonObject t : list) {
            if (url.equals(JsonUtil.str(t, "url", "")) && JsonUtil.integer(t, "type", 0) == type) {
                if (JsonUtil.lng(t, "time", 0) >= time) return;
                t.addProperty("time", time);
                saveList(tombFile(), list);
                return;
            }
        }
        JsonObject t = new JsonObject();
        t.addProperty("url", url);
        t.addProperty("type", type);
        t.addProperty("time", time);
        list.add(t);
        saveList(tombFile(), list);
    }

    /** 该配置是否在 time 之后被删除过。 */
    public static boolean tombstoned(String url, int type, long time) {
        synchronized (LOCK) {
            for (JsonObject t : tombstones()) {
                if (url.equals(JsonUtil.str(t, "url", "")) && JsonUtil.integer(t, "type", 0) == type
                        && JsonUtil.lng(t, "time", 0) >= time) return true;
            }
            return false;
        }
    }

    public static List<JsonObject> getConfigs(int type) {
        synchronized (LOCK) {
            List<JsonObject> out = new ArrayList<>();
            for (JsonObject c : configs()) {
                if (JsonUtil.integer(c, "type", 0) == type && !JsonUtil.str(c, "url", "").isEmpty()) out.add(c);
            }
            out.sort(Comparator.comparingLong((JsonObject c) -> JsonUtil.lng(c, "time", 0)).reversed());
            return out;
        }
    }

    // ---------- History ----------
    public static List<JsonObject> histories() {
        synchronized (LOCK) {
            if (histories == null) histories = loadList(historyFile());
            return histories;
        }
    }

    public static List<JsonObject> getHistories(int cid) {
        long deadline = now() - HISTORY_TIME;
        synchronized (LOCK) {
            List<JsonObject> out = new ArrayList<>();
            for (JsonObject h : histories()) {
                if (JsonUtil.integer(h, "cid", 0) == cid && JsonUtil.lng(h, "createTime", 0) >= deadline) out.add(h);
            }
            out.sort(Comparator.comparingLong((JsonObject h) -> JsonUtil.lng(h, "createTime", 0)).reversed());
            return out;
        }
    }

    public static JsonObject findHistory(int cid, String key) {
        synchronized (LOCK) {
            for (JsonObject h : histories())
                if (JsonUtil.integer(h, "cid", 0) == cid && key.equals(JsonUtil.str(h, "key", ""))) return h;
            return null;
        }
    }

    public static void saveHistory(JsonObject item) {
        if (Setting.incognito()) return;
        synchronized (LOCK) {
            int cid = JsonUtil.integer(item, "cid", 0);
            String key = JsonUtil.str(item, "key", "");
            histories().removeIf(h -> JsonUtil.integer(h, "cid", 0) == cid && key.equals(JsonUtil.str(h, "key", "")));
            item.addProperty("createTime", now());
            histories().add(item);
            long deadline = now() - HISTORY_TIME;
            histories().removeIf(h -> JsonUtil.lng(h, "createTime", 0) < deadline);
            saveList(historyFile(), histories());
            historyRevision++;
        }
    }

    /** 保存历史（保留记录原有 createTime，用于同步合并导入，避免排序被重置）。 */
    public static void saveHistoryKeepTime(JsonObject item) {
        if (Setting.incognito()) return;
        synchronized (LOCK) {
            int cid = JsonUtil.integer(item, "cid", 0);
            String key = JsonUtil.str(item, "key", "");
            histories().removeIf(h -> JsonUtil.integer(h, "cid", 0) == cid && key.equals(JsonUtil.str(h, "key", "")));
            long ct = JsonUtil.lng(item, "createTime", 0);
            item.addProperty("createTime", ct > 0 ? ct : now());
            histories().add(item);
            long deadline = now() - HISTORY_TIME;
            histories().removeIf(h -> JsonUtil.lng(h, "createTime", 0) < deadline);
            saveList(historyFile(), histories());
            historyRevision++;
        }
    }

    public static void deleteHistory(int cid, String key) {
        synchronized (LOCK) {
            boolean removed = histories().removeIf(h -> JsonUtil.integer(h, "cid", 0) == cid && key.equals(JsonUtil.str(h, "key", "")));
            if (!removed) return;
            saveList(historyFile(), histories());
            historyRevision++;
        }
    }

    public static void deleteHistories(int cid) {
        synchronized (LOCK) {
            boolean removed = histories().removeIf(h -> JsonUtil.integer(h, "cid", 0) == cid);
            if (!removed) return;
            saveList(historyFile(), histories());
            historyRevision++;
        }
    }

    // ---------- Keep ----------
    public static List<JsonObject> keeps() {
        synchronized (LOCK) {
            if (keeps == null) keeps = loadList(keepFile());
            return keeps;
        }
    }

    public static List<JsonObject> getKeeps(int cid) {
        synchronized (LOCK) {
            List<JsonObject> out = new ArrayList<>();
            for (JsonObject k : keeps())
                if (JsonUtil.integer(k, "type", 0) == 0 && JsonUtil.integer(k, "cid", 0) == cid) out.add(k);
            out.sort(Comparator.comparingLong((JsonObject k) -> JsonUtil.lng(k, "createTime", 0)).reversed());
            return out;
        }
    }

    public static JsonObject findKeep(int cid, String key) {
        synchronized (LOCK) {
            for (JsonObject k : keeps())
                if (JsonUtil.integer(k, "type", 0) == 0 && JsonUtil.integer(k, "cid", 0) == cid && key.equals(JsonUtil.str(k, "key", "")))
                    return k;
            return null;
        }
    }

    public static void saveKeep(JsonObject item) {
        synchronized (LOCK) {
            int cid = JsonUtil.integer(item, "cid", 0);
            String key = JsonUtil.str(item, "key", "");
            keeps().removeIf(k -> JsonUtil.integer(k, "cid", 0) == cid && key.equals(JsonUtil.str(k, "key", "")));
            item.addProperty("createTime", now());
            keeps().add(item);
            saveList(keepFile(), keeps());
            keepRevision++;
        }
    }

    public static void deleteKeep(int cid, String key) {
        synchronized (LOCK) {
            boolean removed = keeps().removeIf(k -> JsonUtil.integer(k, "cid", 0) == cid && key.equals(JsonUtil.str(k, "key", "")));
            if (!removed) return;
            saveList(keepFile(), keeps());
            keepRevision++;
        }
    }

    /** 预热（后台线程调用，避免首访卡顿）。 */
    public static void warmup() {
        configs(); histories(); keeps();
    }
}
