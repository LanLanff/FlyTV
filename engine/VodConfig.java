package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 点播配置服务（VodConfigService 迁移）：拉取配置 → 解析 sites/parses/flags/doh/rules。 */
public final class VodConfig {
    public static final class Site {
        public String key = "", name = "", api = "", ext = "", jar = "", logo = "";
        public int type = 3;
        public boolean searchable = true, hidden = false, quickSearch = true;
        /** 归属配置的爬虫 jar（绝对 URL；普通单仓模式下与配置 spider 相同） */
        public String spider = "";
        /** 归属配置 URL（合并多仓时用于定位 jar/解析） */
        public String ownerUrl = "";
        /** 归属配置的解析线路（play 时优先用自己配置的） */
        public List<String> vipFlags = new ArrayList<String>();
        public String toString() { return name.isEmpty() ? key : name; }
    }

    private static final Object LOCK = new Object();
    private static volatile JsonObject config;              // 当前配置原始 JSON
    private static volatile String configUrl = "";
    private static volatile int configId = 0;
    private static final List<Site> sites = new ArrayList<>();
    private static final List<JsonObject> parses = new ArrayList<>();
    private static final List<String> flags = new ArrayList<>();

    public static JsonObject rawConfig() { return config; }
    public static String url() { return configUrl; }
    public static int id() { return configId; }
    public static List<Site> sites() { return sites; }
    public static List<String> flags() { return flags; }

    /** 站点列表（隐藏站点排除），与 C# siteCount 语义一致。 */
    public static int visibleSiteCount() {
        int n = 0;
        for (Site s : sites) if (!s.hidden) n++;
        return n;
    }

    /** 加载配置（URL 或本地文件路径；支持 depot 嵌套，与 C# LoadCore 语义一致）。 */
    public static void load(String url) throws Exception {
        JsonObject root = loadRoot(url);
        List<String> cfgFlags = flagsFrom(root);
        String spider = spiderFrom(root, url);
        synchronized (LOCK) {
            config = root;
            configUrl = url;
            configId = resolveConfigId(url);
            sites.clear();
            parses.clear();
            flags.clear();
            JsonArray arr = root.getAsJsonArray("sites");
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    Site s = siteFrom(e.getAsJsonObject(), url, spider, cfgFlags);
                    if (s != null) sites.add(s);
                }
            }
            JsonArray pa = root.getAsJsonArray("parses");
            if (pa != null) for (JsonElement e : pa) try { if (e.isJsonObject()) parses.add(e.getAsJsonObject()); } catch (Exception ignored) { }
            flags.addAll(cfgFlags);
            Logger.d("VodConfig", "配置已加载: " + configUrl + " → " + visibleSiteCount() + " 站点, " + parses.size() + " 解析器");
        }
        refreshSpiderRuntime();
    }

    /**
     * 合并模式：把所有配置的站点并成一个列表。
     * key 冲突时自动加 #2/#3（原 key 优先保留，历史/收藏尽量不受影响）。
     * 每个站点记住自己的配置 URL 与 jar，播放时按归属走。
     */
    public static void loadMerged() {
        List<JsonObject> cfgs = Stores.getConfigs(0);
        List<Site> all = new ArrayList<Site>();
        java.util.Set<String> used = new java.util.HashSet<String>();
        JsonObject firstRoot = null;
        String firstUrl = "";
        List<String> mergedFlags = new ArrayList<String>();
        int okCount = 0;
        for (JsonObject c : cfgs) {
            String u = JsonUtil.str(c, "url", "");
            if (u.isEmpty()) continue;
            try {
                JsonObject root = loadRoot(u);
                if (root == null) continue;
                List<String> cfgFlags = flagsFrom(root);
                if (firstRoot == null) { firstRoot = root; firstUrl = u; mergedFlags = cfgFlags; }
                String spider = spiderFrom(root, u);
                JsonArray arr = root.getAsJsonArray("sites");
                if (arr != null) for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    Site s = siteFrom(e.getAsJsonObject(), u, spider, cfgFlags);
                    if (s == null) continue;
                    String base = s.key;
                    int n = 1;
                    while (used.contains(s.key)) { n++; s.key = base + "#" + n; }
                    used.add(s.key);
                    all.add(s);
                }
                okCount++;
            } catch (Exception ex) {
                Logger.e("VodConfig", "合并配置失败(" + u + "): " + ex.getMessage());
            }
        }
        if (all.isEmpty()) throw new RuntimeException("所有配置都没有可用站点");
        synchronized (LOCK) {
            config = firstRoot;
            configUrl = firstUrl;
            configId = resolveConfigId(firstUrl);
            sites.clear();
            sites.addAll(all);
            flags.clear();
            flags.addAll(mergedFlags);
            parses.clear();
            if (firstRoot != null) {
                JsonArray pa = firstRoot.getAsJsonArray("parses");
                if (pa != null) for (JsonElement e : pa) try { if (e.isJsonObject()) parses.add(e.getAsJsonObject()); } catch (Exception ignored) { }
            }
            Logger.d("VodConfig", "合并模式已加载: " + okCount + " 份配置 → " + sites.size() + " 站点");
        }
        refreshSpiderRuntime();
    }

    private static void refreshSpiderRuntime() {
        try {
            Spiders.reset();
            JarHost.stop();
            JarHost.start();
        } catch (Exception ignored) { }
    }

    static JsonObject loadRoot(String url) throws Exception {
        String text = fetch(url, 0);
        JsonObject root = JsonUtil.parseObj(text);
        if (root == null) throw new Exception("配置解析失败");
        String depot = JsonUtil.str(root, "depot", "");
        if (!depot.isEmpty()) {
            String inner = depot.replace("$name", JsonUtil.str(root, "name", ""));
            text = fetch(inner, 0);
            root = JsonUtil.parseObj(text);
            if (root == null) throw new Exception("depot 配置解析失败");
        }
        return root;
    }

    static List<String> flagsFrom(JsonObject root) {
        List<String> out = new ArrayList<String>();
        try {
            JsonArray fa = root.getAsJsonArray("flags");
            if (fa != null) for (JsonElement e : fa) try { out.add(e.getAsString()); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
        return out;
    }

    /** 配置里的 spider 可能是相对路径（./jar/x.jar），统一转成绝对 URL。 */
    static String spiderFrom(JsonObject root, String configUrl) {
        String spider = JsonUtil.str(root, "spider", "");
        if (spider.isEmpty()) return "";
        if (spider.startsWith("./") || spider.startsWith("/")) {
            int i = configUrl == null ? -1 : configUrl.lastIndexOf('/');
            if (i > 0) return configUrl.substring(0, i + 1) + spider.replaceFirst("^\\.?/+", "");
        }
        return spider;
    }

    static Site siteFrom(JsonObject o, String ownerUrl, String spider, List<String> flags) {
        String key = JsonUtil.str(o, "key", "");
        if (key.isEmpty()) return null;
        Site s = new Site();
        s.key = key;
        s.name = JsonUtil.str(o, "name", key);
        s.api = JsonUtil.str(o, "api", "");
        s.type = JsonUtil.integer(o, "type", 3);
        s.logo = JsonUtil.str(o, "logo", "");
        s.searchable = JsonUtil.integer(o, "searchable", 1) != 0;
        s.hidden = JsonUtil.bool(o, "hide", false);
        s.quickSearch = JsonUtil.integer(o, "quickSearch", 1) != 0;
        JsonElement ext = o.get("ext");
        s.ext = ext == null || ext.isJsonNull() ? "" : (ext.isJsonPrimitive() ? ext.getAsString() : JsonUtil.gson.toJson(ext));
        s.spider = spider;
        s.ownerUrl = ownerUrl;
        s.vipFlags = flags;
        return s;
    }

    /** 启动加载：优先 Setting.configVod，失败则按记录顺序逐个回退，避免单个坏配置导致站点空窗。 */
    public static void loadStartup() {
        if (Setting.mergeConfigs()) {
            try {
                loadMerged();
                Logger.d("VodConfig", "启动：合并模式已启用");
                return;
            } catch (Exception e) {
                Logger.e("VodConfig", "合并模式失败，回退单配置: " + e.getMessage());
            }
        }
        String first = "";
        try {
            first = Setting.configVod();
            if (first != null && !first.isEmpty()) {
                load(first);
                Logger.d("VodConfig", "启动配置已加载: " + first);
                return;
            }
        } catch (Exception e) {
            Logger.e("VodConfig", "启动配置加载失败(" + (first == null ? "" : first) + "): " + e.getMessage());
        }
        try {
            for (JsonObject c : Stores.getConfigs(0)) {
                String u = JsonUtil.str(c, "url", "");
                if (u.isEmpty() || u.equals(first)) continue;
                try {
                    load(u);
                    Setting.setConfigVod(u); // 记住可用的那份，下次启动直接命中
                    Logger.d("VodConfig", "已自动切换到可用配置: " + u + " → " + visibleSiteCount() + " 站点");
                    return;
                } catch (Exception e2) {
                    Logger.e("VodConfig", "配置不可用(" + u + "): " + e2.getMessage());
                }
            }
            Logger.e("VodConfig", "所有配置均加载失败");
        } catch (Exception e) {
            Logger.e("VodConfig", "启动配置加载异常: " + e);
        }
    }

    private static String fetch(String url, int depth) throws Exception {
        if (depth > 3) throw new Exception("配置嵌套过深");
        url = url.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return HttpUtil.get(url, null, 30000);
        }
        if (url.startsWith("file://")) url = url.substring(7);
        File f = new File(url);
        if (!f.isAbsolute()) f = new File(AppPaths.Local, url);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static int resolveConfigId(String url) {
        for (JsonObject c : Stores.configs())
            if (url.equals(JsonUtil.str(c, "url", ""))) return JsonUtil.integer(c, "id", 0);
        return 0;
    }
}
