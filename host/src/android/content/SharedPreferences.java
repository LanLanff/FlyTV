package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SharedPreferences 桩。
 *
 * 关键：必须落盘。很多网盘类 jar（UC/百度/夸克…）把 Cookie 存在
 * SharedPreferences 里；如果只存内存，FlyTV 重启后登录态全丢，用户每次都要重登。
 * 存储位置：%LOCALAPPDATA%\TVBox for Windows\spider-prefs\<name>.properties
 */
public interface SharedPreferences {

    SharedPreferences DUMMY = new Store(null, false);

    Map<String, ?> getAll();
    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    boolean contains(String key);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    class Store implements SharedPreferences {

        private static final Map<String, Store> CACHE = new ConcurrentHashMap<String, Store>();

        private final String name;
        private final boolean persistent;
        private final Map<String, Object> data = new HashMap<String, Object>();

        Store(String name, boolean persistent) {
            this.name = name;
            this.persistent = persistent;
            if (persistent) load();
        }

        static Store open(String name) {
            String key = (name == null || name.isEmpty()) ? "default" : name;
            Store s = CACHE.get(key);
            if (s == null) {
                synchronized (CACHE) {
                    s = CACHE.get(key);
                    if (s == null) {
                        s = new Store(key, true);
                        CACHE.put(key, s);
                    }
                }
            }
            return s;
        }

        private File file() {
            File dir = new File(baseDir(), "spider-prefs");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, safe(name) + ".properties");
        }

        private static String safe(String s) {
            return s.replaceAll("[^A-Za-z0-9._-]", "_");
        }

        private static File baseDir() {
            String d = null;
            try { d = System.getProperty("flytv.data"); } catch (Throwable ignored) { }
            if (d == null || d.isEmpty()) {
                String la = System.getenv("LOCALAPPDATA");
                if (la != null && !la.isEmpty()) d = la + File.separator + "TVBox for Windows";
            }
            if (d == null || d.isEmpty()) d = System.getProperty("java.io.tmpdir", ".");
            return new File(d);
        }

        private synchronized void load() {
            try {
                File f = file();
                if (!f.isFile()) return;
                Properties p = new Properties();
                FileInputStream in = new FileInputStream(f);
                try { p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); } finally { try { in.close(); } catch (Exception ignored) { } }
                data.clear();
                for (String k : p.stringPropertyNames()) {
                    Object v = decode(p.getProperty(k));
                    if (v != null) data.put(k, v);
                }
            } catch (Throwable ignored) { }
        }

        synchronized void merge(Map<String, Object> staged, Set<String> removed, boolean clear) {
            if (clear) data.clear();
            if (removed != null) for (String k : removed) data.remove(k);
            if (staged != null) data.putAll(staged);
            save();
        }

        private void save() {
            if (!persistent) return;
            try {
                Properties p = new Properties();
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    String s = encode(e.getValue());
                    if (s != null) p.setProperty(e.getKey(), s);
                }
                File f = file();
                File tmp = new File(f.getAbsolutePath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try { p.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "FlyTV spider prefs"); } finally { try { out.close(); } catch (Exception ignored) { } }
                try {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable e) {
                    if (!tmp.renameTo(f)) { /* 忽略：极端情况下下次再写 */ }
                }
            } catch (Throwable ignored) { }
        }

        private static String encode(Object v) {
            if (v instanceof String) return "s" + v;
            if (v instanceof Integer) return "i" + v;
            if (v instanceof Long) return "l" + v;
            if (v instanceof Float) return "f" + v;
            if (v instanceof Boolean) return "b" + v;
            if (v instanceof Set) {
                StringBuilder sb = new StringBuilder("S");
                for (Object o : (Set<?>) v) {
                    if (sb.length() > 1) sb.append('\u0001');
                    sb.append(o);
                }
                return sb.toString();
            }
            return null;
        }

        private static Object decode(String s) {
            if (s == null || s.isEmpty()) return null;
            char t = s.charAt(0);
            String v = s.substring(1);
            try {
                switch (t) {
                    case 'i': return Integer.valueOf(v);
                    case 'l': return Long.valueOf(v);
                    case 'f': return Float.valueOf(v);
                    case 'b': return Boolean.valueOf(v);
                    case 'S':
                        Set<String> set = new LinkedHashSet<String>();
                        if (v.length() > 0) {
                            String[] parts = v.split("\u0001", -1);
                            for (String p : parts) set.add(p);
                        }
                        return set;
                    default: return v;
                }
            } catch (Throwable e) {
                return v;
            }
        }

        public Map<String, ?> getAll() {
            synchronized (this) { return new HashMap<String, Object>(data); }
        }

        public String getString(String key, String defValue) {
            synchronized (this) { Object v = data.get(key); return v instanceof String ? (String) v : defValue; }
        }

        public Set<String> getStringSet(String key, Set<String> defValues) {
            synchronized (this) {
                Object v = data.get(key);
                if (v instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<String> s = (Set<String>) v;
                    return new LinkedHashSet<String>(s);
                }
                return defValues;
            }
        }

        public int getInt(String key, int defValue) {
            synchronized (this) { Object v = data.get(key); return v instanceof Integer ? (Integer) v : defValue; }
        }

        public long getLong(String key, long defValue) {
            synchronized (this) { Object v = data.get(key); return v instanceof Long ? (Long) v : defValue; }
        }

        public float getFloat(String key, float defValue) {
            synchronized (this) { Object v = data.get(key); return v instanceof Float ? (Float) v : defValue; }
        }

        public boolean getBoolean(String key, boolean defValue) {
            synchronized (this) { Object v = data.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
        }

        public boolean contains(String key) {
            synchronized (this) { return data.containsKey(key); }
        }

        public Editor edit() { return new Edit(this); }
    }

    class Edit implements Editor {

        private final Store store;
        private final Map<String, Object> staged = new HashMap<String, Object>();
        private final Set<String> removed = new LinkedHashSet<String>();
        private boolean clear = false;

        Edit(Store store) { this.store = store; }

        public Editor putString(String key, String value) { staged.put(key, value); removed.remove(key); return this; }
        public Editor putStringSet(String key, Set<String> values) { staged.put(key, values == null ? null : new LinkedHashSet<String>(values)); removed.remove(key); return this; }
        public Editor putInt(String key, int value) { staged.put(key, Integer.valueOf(value)); removed.remove(key); return this; }
        public Editor putLong(String key, long value) { staged.put(key, Long.valueOf(value)); removed.remove(key); return this; }
        public Editor putFloat(String key, float value) { staged.put(key, Float.valueOf(value)); removed.remove(key); return this; }
        public Editor putBoolean(String key, boolean value) { staged.put(key, Boolean.valueOf(value)); removed.remove(key); return this; }
        public Editor remove(String key) { staged.remove(key); removed.add(key); return this; }
        public Editor clear() { clear = true; staged.clear(); removed.clear(); return this; }
        public boolean commit() { store.merge(staged, removed, clear); return true; }
        public void apply() { store.merge(staged, removed, clear); }
    }
}
