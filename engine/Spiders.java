package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 爬虫调用桥（等价 C# SpiderLoader/SpiderRuntime）：保证站点 spider 已在宿主加载，然后 /call 调方法。 */
public final class Spiders {
    private static final Object LOAD_LOCK = new Object();
    private static final Map<String, Boolean> LOADED = new ConcurrentHashMap<>();

    /** 确保站点已加载（type3；幂等）。失败抛异常。 */
    /** 配置切换/合并开关后清掉已加载记录（jar-host 会按需重启）。 */
    public static void reset() {
        LOADED.clear();
    }

    public static void ensureLoaded(VodConfig.Site site) throws Exception {
        if (site == null) throw new Exception("站点为空");
        if (site.type != 3) return;
        if (JsSpider.isJs(site)) { JsSpider.ensure(site); LOADED.put(site.key, Boolean.TRUE); return; }
        if (LOADED.containsKey(site.key)) return;
        synchronized (LOAD_LOCK) {
            if (LOADED.containsKey(site.key)) return;
            String jar = (site.spider != null && !site.spider.isEmpty()) ? site.spider : resolveJar();
            if (jar.isEmpty()) throw new Exception("站点缺少 jar（配置无 spider 字段）");
            JsonObject body = new JsonObject();
            body.addProperty("siteKey", site.key);
            body.addProperty("jar", jar);
            body.addProperty("api", site.api);
            body.addProperty("ext", site.ext == null ? "" : site.ext);
            body.addProperty("proxyBase", "http://127.0.0.1:" + WebServer.port() + "/proxy?");
            body.addProperty("configBase", (site.ownerUrl != null && !site.ownerUrl.isEmpty()) ? site.ownerUrl : VodConfig.url());
            String resp = JarHost.postJson("/load", body.toString(), 150000);
            // 校验加载结果（宿主失败时返回错误文本/500 body，不能标记为已加载）
            if (resp == null || resp.isEmpty() || (!resp.contains("\"ok\"") && !resp.contains("\"class\""))) {
                throw new Exception("站点加载失败: " + (resp == null ? "" : resp.substring(0, Math.min(140, resp.length()))));
            }
            LOADED.put(site.key, Boolean.TRUE);
            Logger.d("Spiders", "spider 已加载: " + site.key + " -> " + site.api);
        }
    }

    /** 调用 spider 方法。extra 为附加字段（如 tid/pg/filter/key/ids/flag/id/vipFlags）。 */
    public static String call(VodConfig.Site site, String method, Map<String, Object> extra, int timeoutMs) throws Exception {
        if (JsSpider.isJs(site)) return JsSpider.call(site, method, extra, timeoutMs);
        ensureLoaded(site);
        JsonObject body = new JsonObject();
        body.addProperty("siteKey", site.key);
        body.addProperty("method", method);
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Boolean) body.addProperty(e.getKey(), (Boolean) v);
                else if (v instanceof Number) body.addProperty(e.getKey(), (Number) v);
                else if (v instanceof JsonArray) body.add(e.getKey(), (JsonArray) v);
                else body.addProperty(e.getKey(), String.valueOf(v));
            }
        }
        return JarHost.postJson("/call", body.toString(), timeoutMs);
    }

    public static String callSimple(VodConfig.Site site, String method, int timeoutMs) throws Exception {
        return call(site, method, new HashMap<>(), timeoutMs);
    }

    /** 全局 spider 字段解析（./9527.jar → 配置文件同目录的绝对 URL）。 */
    public static String resolveJar() {
        String spider = JsonUtil.str(VodConfig.rawConfig(), "spider", "");
        if (spider.isEmpty()) return "";
        if (spider.startsWith("./") || spider.startsWith("/")) {
            String base = VodConfig.url();
            int i = base.lastIndexOf('/');
            if (i > 0) return base.substring(0, i + 1) + spider.replaceFirst("^\\.?/+", "");
        }
        return spider;
    }
}
