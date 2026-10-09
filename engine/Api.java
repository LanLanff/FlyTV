package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** /api/* 全部端点（与 C# 版契约一致）。 */
public final class Api {
    public static final CountDownLatch READY = new CountDownLatch(1);

    public static String handle(String path, HttpExchange ex) {
        Map<String, String> p = WebServer.params(ex);
        try {
            // 启动后首次访问：等待点播配置加载（最多 25 秒）
            if (!path.equals("/api/login")) {
                try { READY.await(25, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            switch (path) {
                case "/api/status": return status();
                case "/api/settings": return settings();
                case "/api/login": return login(p);
                case "/api/password": return password(p);
                case "/api/setting/set": return settingSet(p);
                case "/api/sites": return sites();
                case "/api/home": return home(p);
                case "/api/category": return category(p);
                case "/api/search": return search(p);
                case "/api/searchall": return search(p);
                case "/api/searchstream": return SearchStream.start(p.getOrDefault("wd", "")).toString();
                case "/api/searchpoll": return SearchStream.poll(p.getOrDefault("id", "")).toString();
                case "/api/suggest": return suggest(p.getOrDefault("q", ""));
                case "/api/detail": return detail(p);
                case "/api/play": return PlayService.play(p.getOrDefault("site", ""), p.getOrDefault("flag", ""), p.getOrDefault("id", "")).toString();
                case "/api/play/check": return PlayService.check(p.getOrDefault("url", "")).toString();
                case "/api/history": return history();
                case "/api/history/save": return historySave(p);
                case "/api/history/delete": return historyDelete(p);
                case "/api/history/clear": return historyClear();
                case "/api/keep": return keep();
                case "/api/keep/toggle": return keepToggle(p);
                case "/api/config/add": return configAdd(p);
                case "/api/config/select": return configSelect(p);
                case "/api/config/delete": return configDelete(p);
                case "/api/cache/clear": return cacheClear(p);
                case "/api/sync/status": return Sync.statusJson();
                case "/api/sync/save": return Sync.saveJson4(p.getOrDefault("url", ""), p.getOrDefault("pass", ""), p.getOrDefault("auto", ""), p.getOrDefault("interval", ""));
                case "/api/sync/upload": return Sync.uploadJson();
                case "/api/sync/download": return Sync.downloadJson();
                case "/api/sync/auto": return Sync.autoJson();
                case "/api/update/check": return Update.checkJson();
                case "/api/update/apply": return Update.applyJson();
                case "/api/update/status": return Update.statusJson();
                case "/api/device/scan": return deviceScan();
                case "/api/device/sync": return deviceSync(p);
                case "/api/danmaku": return Danmaku.fetch(p.getOrDefault("name", ""), p.getOrDefault("ep", ""));
                case "/api/pan/drives": return panDrives();
                case "/api/pan/logout": return panLogout(p);
                case "/api/pan/qr": return PanLogin.qrStart(p.getOrDefault("drive", "quark")).toString();
                case "/api/pan/qr/poll": return PanLogin.qrPoll(p.getOrDefault("drive", "quark"), p.getOrDefault("session", "")).toString();
                case "/api/pan/cookie": return PanLogin.saveManual(p.getOrDefault("drive", "quark"), p.getOrDefault("cookie", "")).toString();
                case "/api/pan/key/push": return PanKeys.pushJson();
                case "/api/pan/key/pull": return PanKeys.pullJson();
                case "/api/action/pan-login": return panLogin(p);
                default: return null;
            }
        } catch (Exception e) {
            Logger.e("Api", path + " -> " + e);
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    // ---------- status / settings ----------
    static String status() {
        JsonObject o = new JsonObject();
        o.add("quark", quarkStatus());
        o.addProperty("configUrl", Setting.configVod());
        o.addProperty("siteCount", VodConfig.visibleSiteCount());
        return o.toString();
    }

    static String settings() {
        JsonObject o = new JsonObject();
        o.addProperty("configUrl", Setting.configVod());
        o.addProperty("siteCount", VodConfig.visibleSiteCount());
        JsonArray cfgs = new JsonArray();
        String cur = Setting.configVod();
        for (JsonObject c : Stores.getConfigs(0)) {
            JsonObject item = new JsonObject();
            item.addProperty("url", JsonUtil.str(c, "url", ""));
            String name = JsonUtil.str(c, "name", "");
            item.addProperty("name", name.isEmpty() ? JsonUtil.str(c, "url", "") : name);
            item.addProperty("time", JsonUtil.lng(c, "time", 0));
            item.addProperty("active", cur.equals(JsonUtil.str(c, "url", "")));
            cfgs.add(item);
        }
        o.add("configs", cfgs);
        o.addProperty("pwdEnabled", AuthGate.enabled());
        o.addProperty("lanEnabled", Setting.localServerLan());
        JsonArray addrs = new JsonArray();
        addrs.add(DeviceJson.lanIp());
        o.add("addresses", addrs);
        o.addProperty("historyCount", Stores.getHistories(currentCid()).size());
        o.addProperty("keepCount", Stores.getKeeps(currentCid()).size());
        File imgDir = new File(AppPaths.Cache, "webimg");
        long bytes = 0;
        int count = 0;
        File[] files = imgDir.listFiles();
        if (files != null) for (File f : files) { bytes += f.length(); count++; }
        o.addProperty("imgCacheMB", Math.round(bytes / 1048576.0 * 10) / 10.0);
        o.addProperty("imgCacheCount", count);
        o.add("quark", quarkStatus());
        return o.toString();
    }

    static String login(Map<String, String> p) {
        String pw = p.getOrDefault("password", "").trim();
        String expect = Setting.webPassword();
        JsonObject o = new JsonObject();
        if (!expect.isEmpty() && !expect.equals(pw)) {
            o.addProperty("error", "口令错误");
            return o.toString();
        }
        o.addProperty("ok", true);
        return o.toString();
    }

    static String password(Map<String, String> p) {
        String np = p.getOrDefault("new", "").trim();
        Setting.setWebPassword(np);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("enabled", !np.isEmpty());
        return o.toString();
    }

    static String settingSet(Map<String, String> p) {
        String key = p.getOrDefault("key", "");
        String val = p.getOrDefault("value", "");
        if ("lan".equals(key)) {
            boolean enable = "1".equals(val) || "true".equalsIgnoreCase(val) || "on".equals(val);
            Setting.setLocalServerLan(enable);
            new Thread(() -> {
                try {
                    Thread.sleep(700);
                    int port = WebServer.port();
                    WebServer.stop();
                    Thread.sleep(400);
                    WebServer.start(port, enable);
                } catch (Exception e) {
                    Logger.e("Api", "lan 重启失败: " + e);
                }
            }, "lan-restart").start();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("lan", enable);
            return o.toString();
        }
        JsonObject o = new JsonObject();
        o.addProperty("error", "unknown setting: " + key);
        return o.toString();
    }

    // ---------- sites / home / category / search / detail ----------
    static String sites() {
        JsonArray arr = new JsonArray();
        for (VodConfig.Site s : VodConfig.sites()) {
            if (s.hidden) continue;
            JsonObject o = new JsonObject();
            o.addProperty("key", s.key);
            o.addProperty("name", s.name);
            o.addProperty("api", s.api);
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("sites", arr);
        o.addProperty("home", JsonUtil.str(VodConfig.rawConfig(), "home", ""));
        return o.toString();
    }

    static String home(Map<String, String> p) {
        VodConfig.Site site = PlayService.findSite(p.getOrDefault("site", ""));
        if (site == null) return SiteService.error("站点不存在").toString();
        return SiteService.home(site).toString();
    }

    static String category(Map<String, String> p) {
        VodConfig.Site site = PlayService.findSite(p.getOrDefault("site", ""));
        if (site == null) return SiteService.error("站点不存在").toString();
        return SiteService.category(site, p.getOrDefault("tid", ""), p.getOrDefault("pg", "1")).toString();
    }

    static String search(Map<String, String> p) {
        VodConfig.Site site = PlayService.findSite(p.getOrDefault("site", ""));
        if (site == null) return SiteService.error("站点不存在").toString();
        return SiteService.search(site, p.getOrDefault("wd", ""), p.getOrDefault("pg", "1")).toString();
    }

    static String suggest(String q) {
        JsonArray out = new JsonArray();
        String query = q == null ? "" : q.trim();
        // 空输入 → 热门搜索（360kan 榜单，24 小时缓存；同 F 影视"搜索发现"）
        if (query.isEmpty()) {
            try {
                JsonArray hot = JsonUtil.parseArr(hotWords());
                if (hot != null) for (JsonElement e : hot) out.add(e);
            } catch (Exception ignored) { }
            JsonObject o = new JsonObject();
            o.add("list", out);
            return o.toString();
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        // 1) 爱奇艺联想（与 F 影视同源；支持拼音输入如 doupo/lldq；纯关键词）
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            String text = HttpUtil.get("https://suggest.video.iqiyi.com/?if=mobile&key=" + enc(query), headers, 6000);
            JsonObject o = JsonUtil.parseObj(text);
            JsonArray arr = o == null ? null : o.getAsJsonArray("data");
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    String name = JsonUtil.str(e.getAsJsonObject(), "name", "").trim();
                    if (name.isEmpty() || name.matches(".*(在线观看|全集|免费|百度百科|小说|漫画|下载|预告|花絮).*")) continue;
                    if (!seen.add(name)) continue;
                    JsonObject oo = new JsonObject();
                    oo.addProperty("title", name);
                    out.add(oo);
                    if (out.size() >= 10) break;
                }
            }
        } catch (Exception ignored) { }
        // 2) 豆瓣兜底（爱奇艺不可用时；带海报，标题去后缀去重）
        if (out.size() == 0) {
            java.util.LinkedHashMap<String, JsonObject> dedup = new java.util.LinkedHashMap<>();
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://movie.douban.com/");
                String text = HttpUtil.get("https://movie.douban.com/j/subject_suggest?q=" + enc(query), headers, 8000);
                JsonArray arr = JsonUtil.parseArr(text);
                if (arr != null) {
                    for (JsonElement e : arr) {
                        if (!e.isJsonObject()) continue;
                        JsonObject item = e.getAsJsonObject();
                        String t = baseTitle(JsonUtil.str(item, "title", ""));
                        if (t.isEmpty() || dedup.containsKey(t)) continue;
                        JsonObject oo = new JsonObject();
                        oo.addProperty("title", t);
                        oo.addProperty("img", JsonUtil.str(item, "img", ""));
                        oo.addProperty("year", JsonUtil.str(item, "year", ""));
                        oo.addProperty("type", JsonUtil.str(item, "type", ""));
                        dedup.put(t, oo);
                        if (dedup.size() >= 8) break;
                    }
                }
            } catch (Exception ignored) { }
            for (JsonObject v : dedup.values()) out.add(v);
        }
        JsonObject o = new JsonObject();
        o.add("list", out);
        return o.toString();
    }

    static volatile long hotTick = 0;
    static String hotJson = "";

    /** 热门搜索词（360kan 榜单，24 小时缓存）。 */
    static synchronized String hotWords() {
        if (!hotJson.isEmpty() && System.currentTimeMillis() - hotTick < 24 * 3600 * 1000L) return hotJson;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.360kan.com/rank/general");
            String text = HttpUtil.get("https://api.web.360kan.com/v1/rank?cat=1", headers, 8000);
            JsonObject o = JsonUtil.parseObj(text);
            JsonArray arr = o == null ? null : o.getAsJsonArray("data");
            JsonArray hot = new JsonArray();
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    String t = JsonUtil.str(e.getAsJsonObject(), "title", "").trim();
                    if (t.isEmpty()) continue;
                    JsonObject oo = new JsonObject();
                    oo.addProperty("title", t);
                    hot.add(oo);
                    if (hot.size() >= 16) break;
                }
            }
            if (hot.size() > 0) { hotJson = hot.toString(); hotTick = System.currentTimeMillis(); }
        } catch (Exception ignored) { }
        return hotJson.isEmpty() ? "[]" : hotJson;
    }

    /** 联想标题归一化：去掉"第X季 / 年番N / 特别篇N / 最终季 / 剧场版"等后缀（站点片名通常没有这些）。 */
    static String baseTitle(String t) {
        if (t == null) return "";
        String s = t.trim();
        s = s.replaceAll("[ 　]*第[一二三四五六七八九十百0-9]+季.*$", "");
        s = s.replaceAll("[ 　]*最终季.*$", "");
        s = s.replaceAll("[ 　]*完结季.*$", "");
        s = s.replaceAll("[ 　]*年番[0-9]*(开播|上线|更新|播出)?.*$", "");
        s = s.replaceAll("[ 　]*特别篇[0-9]*.*$", "");
        s = s.replaceAll("[ 　]*剧场版.*$", "");
        s = s.trim();
        return s.length() >= 2 ? s : t.trim();
    }

    static String detail(Map<String, String> p) {
        VodConfig.Site site = PlayService.findSite(p.getOrDefault("site", ""));
        if (site == null) return SiteService.error("站点不存在").toString();
        return SiteService.detail(site, p.getOrDefault("id", "")).toString();
    }

    // ---------- history / keep ----------
    static String history() {
        JsonArray arr = new JsonArray();
        for (JsonObject h : Stores.getHistories(currentCid())) {
            // 转换存储字段 → 前端契约（key/siteKey/vodId/name/pic/flag/remarks/episodeUrl/position/duration/createTime）
            JsonObject o = new JsonObject();
            String key = JsonUtil.str(h, "key", "");
            String[] parts = key.split("@", 2);
            o.addProperty("key", key);
            o.addProperty("siteKey", parts.length > 0 ? parts[0] : "");
            o.addProperty("vodId", parts.length > 1 ? parts[1] : "");
            o.addProperty("name", JsonUtil.str(h, "vodName", ""));
            o.addProperty("pic", JsonUtil.str(h, "vodPic", ""));
            o.addProperty("flag", JsonUtil.str(h, "vodFlag", ""));
            o.addProperty("remarks", JsonUtil.str(h, "vodRemarks", ""));
            o.addProperty("episodeUrl", JsonUtil.str(h, "episodeUrl", ""));
            o.addProperty("playIndex", JsonUtil.integer(h, "playIndex", -1));
            o.addProperty("position", JsonUtil.lng(h, "position", -1));
            o.addProperty("duration", JsonUtil.lng(h, "duration", -1));
            o.addProperty("createTime", JsonUtil.lng(h, "createTime", 0));
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("list", arr);
        return o.toString();
    }

    static String historySave(Map<String, String> p) {
        try {
            String siteKey = p.getOrDefault("siteKey", "").trim();
            String vodId = p.getOrDefault("vodId", "").trim();
            if (siteKey.isEmpty() || vodId.isEmpty()) {
                Logger.e("HistorySave", "缺少参数: " + p);
                JsonObject o = new JsonObject();
                o.addProperty("error", "missing siteKey/vodId");
                return o.toString();
            }
            JsonObject h = new JsonObject();
            h.addProperty("key", siteKey + "@" + vodId);
            h.addProperty("cid", currentCid());
            h.addProperty("vodName", p.getOrDefault("name", ""));
            h.addProperty("vodPic", p.getOrDefault("pic", ""));
            h.addProperty("vodFlag", p.getOrDefault("flag", ""));
            h.addProperty("vodRemarks", p.getOrDefault("remarks", ""));
            h.addProperty("episodeUrl", p.getOrDefault("episodeUrl", ""));
            h.addProperty("playIndex", (int) parseLong(p.getOrDefault("index", "-1")));
            h.addProperty("position", parseLong(p.getOrDefault("position", "-1")));
            h.addProperty("duration", parseLong(p.getOrDefault("duration", "-1")));
            h.addProperty("speed", 1);
            Stores.saveHistory(h);
            Logger.d("HistorySave", "保存 " + siteKey + "@" + vodId + " idx=" + h.get("playIndex") + " pos=" + h.get("position") + " ep=" + h.get("episodeUrl").getAsString().length() + "字");
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            return o.toString();
        } catch (Exception e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    static String historyDelete(Map<String, String> p) {
        Stores.deleteHistory(currentCid(), p.getOrDefault("key", ""));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o.toString();
    }

    static String historyClear() {
        Stores.deleteHistories(currentCid());
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o.toString();
    }

    static String keep() {
        JsonArray arr = new JsonArray();
        for (JsonObject k : Stores.getKeeps(currentCid())) arr.add(k);
        JsonObject o = new JsonObject();
        o.add("list", arr);
        return o.toString();
    }

    static String keepToggle(Map<String, String> p) {
        try {
            String key = p.getOrDefault("key", "").trim();
            if (key.isEmpty()) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "missing key");
                return o.toString();
            }
            int cid = currentCid();
            JsonObject exist = Stores.findKeep(cid, key);
            boolean kept;
            if (exist != null) {
                Stores.deleteKeep(cid, key);
                kept = false;
            } else {
                JsonObject k = new JsonObject();
                k.addProperty("key", key);
                k.addProperty("cid", cid);
                k.addProperty("type", 0);
                k.addProperty("siteKey", p.getOrDefault("siteKey", key.contains("@") ? key.substring(0, key.indexOf('@')) : ""));
                k.addProperty("vodId", p.getOrDefault("vodId", key.contains("@") ? key.substring(key.indexOf('@') + 1) : ""));
                k.addProperty("name", p.getOrDefault("name", ""));
                k.addProperty("pic", p.getOrDefault("pic", ""));
                Stores.saveKeep(k);
                kept = true;
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("kept", kept);
            return o.toString();
        } catch (Exception e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    // ---------- config 管理 ----------
    static String configAdd(Map<String, String> p) {
        String url = p.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            JsonObject o = new JsonObject();
            o.addProperty("error", "配置地址不能为空");
            return o.toString();
        }
        JsonObject rec = Stores.findConfig(url, 0);
        String name = p.getOrDefault("name", "").trim();
        if (!name.isEmpty()) rec.addProperty("name", name);
        Stores.saveConfig(rec);
        return settings();
    }

    static String configSelect(Map<String, String> p) {
        try {
            String url = p.getOrDefault("url", "").trim();
            if (url.isEmpty()) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "配置地址不能为空");
                return o.toString();
            }
            JsonObject rec = Stores.findConfig(url, 0);
            Setting.setConfigVod(url);
            Stores.saveConfig(rec);
            VodConfig.load(url);
            Logger.d("Api", "配置已切换: " + url + "（" + VodConfig.visibleSiteCount() + " 站点）");
            return settings();
        } catch (Exception e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", "切换失败: " + e.getMessage());
            return o.toString();
        }
    }

    static String configDelete(Map<String, String> p) {
        String url = p.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            JsonObject o = new JsonObject();
            o.addProperty("error", "配置地址不能为空");
            return o.toString();
        }
        Stores.deleteConfig(url, 0);
        if (url.equals(Setting.configVod())) {
            List<JsonObject> list = Stores.getConfigs(0);
            Setting.setConfigVod(list.isEmpty() ? "" : JsonUtil.str(list.get(0), "url", ""));
        }
        return settings();
    }

    static String cacheClear(Map<String, String> p) {
        String kind = p.getOrDefault("kind", "img");
        long freed = 0;
        if ("img".equals(kind)) {
            File dir = new File(AppPaths.Cache, "webimg");
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) {
                freed += f.length();
                try { f.delete(); } catch (Exception ignored) { }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("freedMB", Math.round(freed / 1048576.0 * 10) / 10.0);
        return o.toString();
    }

    // ---------- 设备扫描 / 同步 ----------
    static String deviceScan() {
        JsonArray arr = new JsonArray();
        try {
            String myIp = DeviceJson.lanIp();
            String prefix = myIp.substring(0, myIp.lastIndexOf('.'));
            List<java.util.concurrent.Future<JsonObject>> futures = new ArrayList<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(64);
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + "." + i;
                if (ip.equals(myIp)) continue;
                futures.add(pool.submit(() -> {
                    try {
                        String text = HttpUtil.get("http://" + ip + ":9978/device", null, 900);
                        JsonObject dev = JsonUtil.parseObj(text);
                        if (dev != null && !JsonUtil.str(dev, "name", "").isEmpty() && !JsonUtil.str(dev, "uuid", "").isEmpty())
                            return dev;
                    } catch (Exception ignored) { }
                    return null;
                }));
            }
            pool.shutdown();
            pool.awaitTermination(20, TimeUnit.SECONDS);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (java.util.concurrent.Future<JsonObject> f : futures) {
                try {
                    JsonObject d = f.get();
                    if (d == null) continue;
                    String uuid = JsonUtil.str(d, "uuid", "");
                    if (!seen.add(uuid)) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("uuid", uuid);
                    o.addProperty("name", JsonUtil.str(d, "name", ""));
                    o.addProperty("ip", JsonUtil.str(d, "ip", ""));
                    o.addProperty("type", JsonUtil.integer(d, "type", 0));
                    o.addProperty("serial", JsonUtil.str(d, "serial", ""));
                    arr.add(o);
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            Logger.e("DeviceScan", String.valueOf(e));
        }
        JsonObject o = new JsonObject();
        o.add("list", arr);
        o.addProperty("myIp", "http://" + DeviceJson.lanIp() + ":" + WebServer.port());
        return o.toString();
    }

    static String deviceSync(Map<String, String> p) {
        try {
            String ip = p.getOrDefault("ip", "").trim();
            String type = p.getOrDefault("type", "history");
            String dir = p.getOrDefault("dir", "push");
            if (ip.isEmpty()) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "缺少设备地址");
                return o.toString();
            }
            String baseUrl = ip.startsWith("http") ? trimSlash(ip) : "http://" + ip;
            if ("push".equals(dir)) {
                if ("keep".equals(type)) {
                    Map<String, String> form = new HashMap<>();
                    JsonArray targets = new JsonArray();
                    for (JsonObject k : Stores.keeps()) targets.add(k);
                    form.put("targets", targets.toString());
                    form.put("configs", Stores.getConfigs(0).toString());
                    HttpUtil.postForm(baseUrl + "/action?do=sync&mode=0&type=keep", form, null, 8000);
                    JsonObject o = new JsonObject();
                    o.addProperty("ok", true);
                    o.addProperty("sent", targets.size());
                    return o.toString();
                }
                Map<String, String> form = new HashMap<>();
                form.put("config", JsonUtil.gson.toJson(configRecord()));
                form.put("targets", historiesJsonString());
                HttpUtil.postForm(baseUrl + "/action?do=sync&mode=0&type=history", form, null, 8000);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("sent", Stores.getHistories(currentCid()).size());
                return o.toString();
            } else {
                String my = DeviceJson.get();
                String url = baseUrl + "/action?do=sync&type=" + enc(type) + "&mode=2&device=" + enc(my);
                HttpUtil.get(url, null, 8000);
                Thread.sleep(2000);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("current", "keep".equals(type) ? Stores.getKeeps(currentCid()).size() : Stores.getHistories(currentCid()).size());
                return o.toString();
            }
        } catch (Exception e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    static JsonObject configRecord() {
        for (JsonObject c : Stores.getConfigs(0))
            if (Setting.configVod().equals(JsonUtil.str(c, "url", ""))) return c;
        JsonObject o = new JsonObject();
        o.addProperty("url", Setting.configVod());
        o.addProperty("type", 0);
        return o;
    }

    static String historiesJsonString() {
        JsonArray arr = new JsonArray();
        for (JsonObject h : Stores.getHistories(currentCid())) arr.add(h);
        return arr.toString();
    }

    // ---------- 网盘 ----------
    static String panDrives() {
        JsonArray arr = new JsonArray();
        arr.add(panDrive("quark", "夸克网盘", "quark_cookie"));
        arr.add(panDrive("uc", "UC网盘", "uc_cookie"));
        arr.add(panDrive("baidu", "百度网盘", "baidu"));
        arr.add(panDrive("ali", "阿里云盘", "ali_cookie"));
        arr.add(panDrive("bili", "哔哩哔哩", "bili_cookie"));
        arr.add(panDrive("xunlei", "迅雷云盘", "xunlei"));
        arr.add(panDrive("guangya", "光鸭云盘", "guangya"));
        arr.add(panDrive("cloud189", "天翼云盘", "cloud189"));
        arr.add(panDrive("cloud123", "123云盘", "cloud123"));
        arr.add(panDrive("115", "115网盘", "115"));
        arr.add(panDrive("uctoken", "UC TV Token", "uc_token"));
        JsonObject o = new JsonObject();
        o.add("drives", arr);
        return o.toString();
    }

    static JsonObject panDrive(String id, String name, String fileBase) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        JsonObject cookie = readPanCookieJson(fileBase);
        boolean login = cookie != null;
        o.addProperty("loggedIn", login);
        String nick = cookie == null ? "" : JsonUtil.str(cookie, "nickname", "");
        String member = cookie == null ? "" : JsonUtil.str(cookie, "member_type", "");
        o.addProperty("nickname", nick);
        o.addProperty("member", member);
        // status：前端 tag 展示（文案对齐 C# PanStore.StatusOf）
        String status;
        if (!login) status = "未登录";
        else if (!nick.isEmpty()) status = "已登录：" + nick + (member.isEmpty() ? "" : "（" + member + "）");
        else status = "已登录";
        o.addProperty("status", status);
        return o;
    }

    static JsonObject readPanCookieJson(String fileBase) {
        // 三处仓库：Pizazz（引擎）/ TEMP（宿主）/ lzxw（jar 自身），兼容 base 与 base_cookie 两种命名
        String[] dirs = {
                AppPaths.JarCache + "\\files\\Pizazz",
                System.getenv("TEMP") + "\\TVBox",
                AppPaths.JarCache + "\\files\\lzxw"
        };
        String[] bases = fileBase.endsWith("_cookie")
                ? new String[]{ fileBase, fileBase.replace("_cookie", "") }
                : new String[]{ fileBase, fileBase + "_cookie" };
        for (String dir : dirs) {
            for (String base : bases) {
                for (String suffix : new String[]{ ".txt", "" }) {
                    File f = new File(dir, base + suffix);
                    if (!f.exists()) continue;
                    try {
                        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                        if (text.startsWith("{")) {
                            JsonObject o = JsonUtil.parseObj(text);
                            if (o != null && !JsonUtil.str(o, "cookie", "").isEmpty()) return o;
                        } else if (!text.isEmpty()) {
                            JsonObject o = new JsonObject();
                            o.addProperty("cookie", text);
                            return o;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }

    static String panLogout(Map<String, String> p) {
        String id = p.getOrDefault("drive", "");
        String[] names = PanLogin.cookieNames(id);
        String[] dirs = {
                AppPaths.JarCache + "\\files\\Pizazz",
                System.getenv("TEMP") + "\\TVBox",
                AppPaths.JarCache + "\\files\\lzxw"
        };
        for (String dir : dirs) {
            for (String name : names) {
                File f = new File(dir, name);
                if (f.exists()) try { f.delete(); } catch (Exception ignored) { }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", "已清除");
        return o.toString();
    }

    static String panLogin(Map<String, String> p) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", "Java 版暂未内置扫码窗口：请在夸克 App/网页完成登录后，将 cookie 交由引擎保存（或使用带登录窗的版本）");
        return o.toString();
    }

    // ---------- 工具 ----------
    static JsonObject quarkStatus() {
        JsonObject cookie = readPanCookieJson("quark_cookie");
        JsonObject q = new JsonObject();
        q.addProperty("loggedIn", cookie != null);
        q.addProperty("nickname", cookie == null ? "" : JsonUtil.str(cookie, "nickname", ""));
        q.addProperty("member", cookie == null ? "" : JsonUtil.str(cookie, "member_type", ""));
        q.addProperty("updatedAt", 0);
        return q;
    }

    public static int currentCid() {
        String url = Setting.configVod();
        for (JsonObject c : Stores.configs())
            if (url.equals(JsonUtil.str(c, "url", ""))) return JsonUtil.integer(c, "id", 0);
        return 0;
    }

    static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
