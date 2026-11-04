package dev.flytv.engine;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;

/** 键值设置（prefs.json，格式与 C# 版完全兼容，可直接共用同一份文件）。 */
public final class Setting {
    public static final String BuiltInConfigVod = "https://8815.kstore.vip/tvbox/wmz";
    public static final String BuiltInConfigLive = "https://8815.kstore.vip/tvbox/wmz";

    private static final Object LOCK = new Object();
    private static final TreeMap<String, String> MAP = new TreeMap<>();

    private static File file() { return new File(AppPaths.Root, "prefs.json"); }

    public static void load() {
        synchronized (LOCK) {
            MAP.clear();
            String text = JsonUtil.readFile(file());
            if (text != null) {
                JsonObject obj = JsonUtil.parseObj(text);
                if (obj != null)
                    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                        try { MAP.put(e.getKey(), e.getValue().getAsString()); } catch (Exception ignored) { }
                    }
            }
            Logger.d("Setting", "设置已加载（" + MAP.size() + " 项）");
        }
    }

    public static String getString(String key, String def) {
        synchronized (LOCK) { String v = MAP.get(key); return v != null ? v : def; }
    }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(getString(key, null)); } catch (Exception ignored) { return def; }
    }

    public static boolean getBool(String key, boolean def) {
        try { return Boolean.parseBoolean(getString(key, null)); } catch (Exception ignored) { return def; }
    }

    public static double getFloat(String key, double def) {
        try { return Double.parseDouble(getString(key, null)); } catch (Exception ignored) { return def; }
    }

    public static void put(String key, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        synchronized (LOCK) {
            String cur = MAP.get(key);
            if (cur != null && cur.equals(text)) return;
            MAP.put(key, text);
            saveLocked();
        }
    }

    public static void remove(String key) {
        synchronized (LOCK) {
            if (MAP.remove(key) != null) saveLocked();
        }
    }

    private static void saveLocked() {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : MAP.entrySet()) obj.addProperty(e.getKey(), e.getValue());
        JsonUtil.writeFile(file(), JsonUtil.pretty.toJson(obj));
    }

    // ---- 常用访问器（键名与 C# 版一致） ----
    public static String configVod() { return getString("config_vod", BuiltInConfigVod); }
    public static void setConfigVod(String url) { put("config_vod", url); }
    public static boolean mergeConfigs() { return getBool("merge_configs", false); }
    public static void setMergeConfigs(boolean v) { put("merge_configs", v); }
    public static String configLive() { return getString("config_live", BuiltInConfigLive); }
    public static boolean localServerLan() { return getBool("local_server_lan", false); }
    public static void setLocalServerLan(boolean v) { put("local_server_lan", v); }
    public static String webPassword() { return getString("web_password", "").trim(); }
    public static void setWebPassword(String v) { put("web_password", v == null ? "" : v.trim()); }
    public static boolean incognito() { return getBool("incognito", false); }
    public static String deviceUuid() {
        String v = getString("device_uuid", "");
        if (v.isEmpty()) {
            v = java.util.UUID.randomUUID().toString().replace("-", "");
            put("device_uuid", v);
        }
        return v;
    }
}
