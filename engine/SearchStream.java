package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 流式搜索（等价 C# SearchStream/SearchPoll）：创建会话后台并行搜索全部站点，前端每秒轮询增量。 */
public final class SearchStream {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(24);
    private static final ExecutorService JS_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "js-search");
        t.setDaemon(true);
        return t;
    });

    static final class Session {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String wd;
        final long created = System.currentTimeMillis();
        final Map<String, JsonArray> groups = new LinkedHashMap<>(); // siteName -> list（含空结果，用于进度）
        volatile int total = 0;
        volatile boolean done = false;
        Session(String wd) { this.wd = wd; }
    }

    /** 创建搜索会话并后台执行；返回 {id}。 */
    public static JsonObject start(String wd) {
        cleanup();
        Session s = new Session(wd);
        SESSIONS.put(s.id, s);
        List<VodConfig.Site> targets = new ArrayList<>();
        for (VodConfig.Site site : VodConfig.sites()) {
            if (!site.hidden && site.searchable) targets.add(site);
        }
        int totalSites = targets.size();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (VodConfig.Site site : targets) {
            Runnable task = () -> {
                JsonArray list = new JsonArray();
                try {
                    JsonObject r = SiteService.search(site, wd, "1", 12000);
                    JsonArray l = r == null ? null : r.getAsJsonArray("list");
                    if (l != null) list = l;
                } catch (Exception ignored) { }
                synchronized (s) {
                    s.groups.put(site.name, list);
                    s.total += list.size();
                }
                if (finished.incrementAndGet() >= totalSites) s.done = true;
            };
            // JS 源共用同一个 JS 上下文（不能并发），单独排队，避免堵死 jar/HTTP 源的搜索线程
            if (JsSpider.isJs(site)) JS_POOL.submit(task); else POOL.submit(task);
        }
        if (totalSites == 0) s.done = true;
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        return o;
    }

    /** 增量轮询：{groups:[{siteName,list}], total, done}（前端覆盖式消费）。 */
    public static JsonObject poll(String id) {
        Session s = SESSIONS.get(id);
        if (s == null) {
            JsonObject o = new JsonObject();
            o.add("groups", new JsonArray());
            o.addProperty("total", 0);
            o.addProperty("done", true);
            o.addProperty("expired", true);
            return o;
        }
        JsonArray groups = new JsonArray();
        synchronized (s) {
            for (Map.Entry<String, JsonArray> e : s.groups.entrySet()) {
                JsonObject g = new JsonObject();
                g.addProperty("siteName", e.getKey());
                g.add("list", e.getValue());
                groups.add(g);
            }
        }
        JsonObject o = new JsonObject();
        o.add("groups", groups);
        o.addProperty("total", s.total);
        o.addProperty("done", s.done);
        return o;
    }

    private static void cleanup() {
        long deadline = System.currentTimeMillis() - 10 * 60 * 1000;
        SESSIONS.entrySet().removeIf(e -> e.getValue().created < deadline);
    }
}
