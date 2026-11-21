package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 云端数据同步（WebDAV + AES-256-GCM）。
 * 一键把「点播配置 + 网盘Cookie + 引擎设置 + 历史 + 收藏」加密打包上传，
 * 在其它设备下载还原（配置覆盖 + .bak 备份，历史/收藏按 key 合并）。
 * 地址形如 http://user:pass@host:port/dav/backup.dat（账号密码写在 URL 里）。
 */
public final class Sync {
    private static final byte[] MAGIC = "FLYTVSYNC1".getBytes(StandardCharsets.US_ASCII);
    private static final String[] COOKIE_FILES = {"quark_cookie.txt", "uc_cookie.txt", "baidu_cookie.txt", "ali_cookie.txt"};
    private static final int MAX_BLOB = 8 * 1024 * 1024;

    /** 每个同步文件的最近一次版本/状态（用于详情页）。 */
    static final java.util.Map<String, String> REVS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<String, JsonObject> LINK_STATE = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile long lastNotifyAt = 0, lastSyncAt = 0;
    static volatile String lastSyncError = "";
    static volatile long lastSyncMs = 0;
    static volatile boolean lastPulled = false, lastPushed = false;

    // ---------- 对外接口 ----------

    public static JsonObject status() {
        JsonObject o = new JsonObject();
        String url = Setting.getString("sync_url", "");
        String pass = Setting.getString("sync_pass", "");
        o.addProperty("url", url);
        o.addProperty("hasPass", !pass.isEmpty());
        o.addProperty("auto", Setting.getBool("sync_auto", true));
        o.addProperty("interval", Setting.getInt("sync_interval", 1));
        o.addProperty("lastUp", Setting.getString("sync_last_up", ""));
        o.addProperty("lastDown", Setting.getString("sync_last_down", ""));
        // 单链接与分享码：Go 服务的 /d/ 地址 → 生成加密 flytv:// 分享码；其它地址退回 url?key= 形式
        String link = url;
        String code = "";
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("^https?://([^/]+)/d/([A-Za-z0-9_-]+)(?:\\?.*)?$").matcher(url);
        if (dm.find() && !pass.isEmpty()) {
            try {
                String payload = "{\"s\":\"" + jsonEscape(dm.group(1)) + "\",\"t\":\"" + jsonEscape(dm.group(2)) + "\",\"k\":\"" + jsonEscape(pass) + "\"}";
                code = "flytv://" + encLink(payload);
                link = code;
            } catch (Exception ignored) { }
        }
        if (code.isEmpty()) {
            if (!url.isEmpty() && !pass.isEmpty()) {
                String sep = url.contains("?") ? "&" : "?";
                try { link = url + sep + "key=" + java.net.URLEncoder.encode(pass, "UTF-8"); } catch (Exception e) { link = url + sep + "key=" + pass; }
            }
            if (!url.isEmpty() && !pass.isEmpty()) {
                try {
                    JsonObject j = new JsonObject();
                    j.addProperty("u", url);
                    j.addProperty("k", pass);
                    code = "flytv://" + Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(j.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }
        }
        o.addProperty("link", link);
        if (!code.isEmpty()) o.addProperty("code", code);
        return o;
    }

    /** 同步详情：每条同步链接的云端状态（大小/版本/时间）+ 本机游标 + WS 状态。 */
    public static JsonObject info(boolean refresh) {
        JsonObject o = new JsonObject();
        String url = Setting.getString("sync_url", "");
        String pass = Setting.getString("sync_pass", "");
        o.addProperty("url", url);
        o.addProperty("hasPass", !pass.isEmpty());
        o.addProperty("auto", Setting.getBool("sync_auto", true));
        o.addProperty("interval", Setting.getInt("sync_interval", 1));
        o.addProperty("lastUp", Setting.getString("sync_last_up", ""));
        o.addProperty("lastDown", Setting.getString("sync_last_down", ""));

        JsonObject ws = new JsonObject();
        ws.addProperty("alive", SyncWS.isAlive());
        ws.addProperty("changes", SyncWS.changes());
        ws.addProperty("lastNotifyAt", lastNotifyAt);
        ws.addProperty("lastSyncAt", lastSyncAt);
        ws.addProperty("lastSyncMs", lastSyncMs);
        ws.addProperty("pulled", lastPulled);
        ws.addProperty("pushed", lastPushed);
        ws.addProperty("error", lastSyncError == null ? "" : lastSyncError);
        o.add("ws", ws);

        JsonObject local = new JsonObject();
        local.addProperty("history", Stores.histories().size());
        local.addProperty("keep", Stores.keeps().size());
        local.addProperty("configs", Stores.configs().size());
        o.add("local", local);

        JsonArray links = new JsonArray();
        if (!url.isEmpty() && !pass.isEmpty()) {
            String auth = authUrl(url, pass);
            links.add(linkInfo("main", "完整备份（配置/设置/历史/收藏/网盘Key）", auth, refresh));
            links.add(linkInfo("history", "自动同步（历史/收藏/配置链接）", historyUrl(auth), refresh));
            links.add(linkInfo("pan", "网盘 Key", panUrl(auth), refresh));
        }
        o.add("links", links);
        return o;
    }

    private static JsonObject linkInfo(String key, String label, String url, boolean refresh) {
        JsonObject o = null;
        if (refresh) {
            o = probe(url);
            JsonObject cached = LINK_STATE.get(revKey(url));
            if (cached != null && JsonUtil.lng(o, "size", 0) <= 0) o.addProperty("size", JsonUtil.lng(cached, "size", 0));
        } else {
            JsonObject cached = LINK_STATE.get(revKey(url));
            if (cached != null) o = cached.deepCopy();
        }
        if (o == null) {
            o = new JsonObject();
            o.addProperty("state", "unknown");
        }
        o.addProperty("key", key);
        o.addProperty("label", label);
        o.addProperty("url", url);
        return o;
    }

    /** 记录一次读写的结果，详情页直接展示（不额外发请求）。 */
    static void recordState(String url, int code, long size, String rev, String modified) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("http", code);
            o.addProperty("state", (code >= 200 && code < 300) ? "ok" : (code == 404 ? "missing" : "error"));
            o.addProperty("size", size);
            o.addProperty("rev", rev == null ? "" : rev);
            o.addProperty("modified", modified == null ? "" : modified);
            o.addProperty("at", System.currentTimeMillis());
            LINK_STATE.put(revKey(url), o);
        } catch (Exception ignored) { }
    }

    /** HEAD 探测单个同步文件（服务端不支持 HEAD 时退回 GET 只读响应头）。 */
    private static JsonObject probe(String url) {
        JsonObject o = new JsonObject();
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD");
            int code = c.getResponseCode();
            if (code == 405 || code == 501) {
                c.disconnect();
                c = open(url, "GET");
                code = c.getResponseCode();
            }
            o.addProperty("http", code);
            o.addProperty("state", (code >= 200 && code < 300) ? "ok" : (code == 404 ? "missing" : "error"));
            long len = c.getContentLengthLong();
            o.addProperty("size", len > 0 ? len : 0);
            String rev = c.getHeaderField("X-Rev");
            o.addProperty("rev", rev == null ? "" : rev);
            String lm = c.getHeaderField("Last-Modified");
            o.addProperty("modified", lm == null ? "" : lm);
            o.addProperty("at", System.currentTimeMillis());
        } catch (Exception e) {
            o.addProperty("state", "error");
            o.addProperty("error", e.getMessage() == null ? "连接失败" : e.getMessage());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
        return o;
    }

    public static String infoJson() {
        try { return info(false).toString(); } catch (Exception e) { return errJson(e); }
    }

    public static String infoJson(boolean refresh) {
        try { return info(refresh).toString(); } catch (Exception e) { return errJson(e); }
    }

    static String[] parseLink(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("flytv://")) {
            String b64 = s.substring("flytv://".length()).trim();
            // ① 新版分享码：AES-GCM 加密的 {s:服务器, t:令牌, k:key}
            try {
                byte[] blob = Base64.getUrlDecoder().decode(b64);
                String plain = new String(linkDecrypt(blob), StandardCharsets.UTF_8);
                JsonObject o = JsonUtil.parseObj(plain);
                if (o != null) {
                    String host = JsonUtil.str(o, "s", "");
                    String token = JsonUtil.str(o, "t", "");
                    String key = JsonUtil.str(o, "k", "");
                    if (!host.isEmpty() && !token.isEmpty()) {
                        String url = "http://" + host + "/d/" + token;
                        if (!key.isEmpty()) {
                            try { url += "?key=" + java.net.URLEncoder.encode(key, "UTF-8"); } catch (Exception e) { url += "?key=" + key; }
                        }
                        return new String[]{ url, key };
                    }
                }
            } catch (Exception ignored) { }
            // ② 旧版分享码：base64 JSON {u:url, k:key}
            try {
                String json = new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonObject o = JsonUtil.parseObj(json);
                if (o == null) return null;
                String u = JsonUtil.str(o, "u", "");
                String k = JsonUtil.str(o, "k", "");
                if (u.isEmpty()) return null;
                return new String[]{ u, k };
            } catch (Exception e) {
                return null;
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&#](?:key|k)=([^&#]+)").matcher(s);
        if (m.find()) {
            String key = m.group(1);
            try { key = java.net.URLDecoder.decode(key, "UTF-8"); } catch (Exception ignored) { }
            String url = s.substring(0, m.start());
            while (url.endsWith("?") || url.endsWith("&") || url.endsWith("#")) url = url.substring(0, url.length() - 1);
            return new String[]{ url, key };
        }
        return new String[]{ s, "" };
    }

    // JSON 包装（异常转 error 字段，Api 直接调用）
    public static String statusJson() { return status().toString(); }

    public static String saveJson(String url, String pass) { return save(url, pass).toString(); }

    public static String saveJson3(String url, String pass, String auto) { return save(url, pass, auto).toString(); }

    public static String saveJson4(String url, String pass, String auto, String interval) { return save(url, pass, auto, interval).toString(); }

    public static String autoJson() {
        try { return autoSync().toString(); }
        catch (Exception e) {
            lastSyncError = e.getMessage() == null ? "同步失败" : e.getMessage();
            lastSyncAt = System.currentTimeMillis();
            return errJson(e);
        }
    }

    public static String uploadJson() {
        try { return upload().toString(); } catch (Exception e) { return errJson(e); }
    }

    public static String downloadJson() {
        try { return download().toString(); } catch (Exception e) { return errJson(e); }
    }

    static String errJson(Exception e) {
        JsonObject o = new JsonObject();
        o.addProperty("error", e.getMessage() == null ? "同步失败" : e.getMessage());
        return o.toString();
    }

    public static JsonObject save(String url, String pass) { return save(url, pass, null, null); }

    public static JsonObject save(String url, String pass, String auto) { return save(url, pass, auto, null); }

    public static JsonObject save(String url, String pass, String auto, String interval) {
        JsonObject o = new JsonObject();
        // 单链接支持：输入可能是 URL?key=xxx 或 flytv:// 分享码 → 拆出真实 url 与口令
        String[] parsed = parseLink(url);
        if (parsed != null) {
            if (!parsed[1].isEmpty()) pass = parsed[1];
            url = parsed[0];
        }
        url = url == null ? "" : url.trim();
        if (!url.isEmpty() && !url.startsWith("http")) {
            o.addProperty("error", "同步链接需要以 http 开头（或使用 flytv:// 分享码）");
            return o;
        }
        Setting.put("sync_url", url);
        if (pass != null && !pass.isEmpty()) Setting.put("sync_pass", pass);
        if (auto != null && !auto.isEmpty()) Setting.put("sync_auto", "1".equals(auto) || "true".equalsIgnoreCase(auto));
        if (interval != null && !interval.isEmpty()) {
            try {
                int iv = Integer.parseInt(interval.trim());
                if (iv >= 1 && iv <= 60) Setting.put("sync_interval", iv);
            } catch (Exception ignored) { }
        }
        o.addProperty("ok", true);
        return o;
    }

    /** 给地址补上 key 参数（地址未带 key 时用已存口令补，兼容被规范化过的地址）。 */
    static String authUrl(String url, String pass) {
        if (url == null || url.isEmpty()) return url;
        try {
            if (java.util.regex.Pattern.compile("[?&#](?:key|k)=").matcher(url).find()) return url;
            if (pass == null || pass.isEmpty()) return url;
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + "key=" + java.net.URLEncoder.encode(pass, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    /** 打包并上传（加密后 PUT）。 */
    public static JsonObject upload() throws Exception {
        String url = Setting.getString("sync_url", "");
        String pass = Setting.getString("sync_pass", "");
        if (url.isEmpty()) throw new Exception("请先填写同步地址");
        if (pass.isEmpty()) throw new Exception("请先设置同步口令（加密用）");
        JsonObject files = new JsonObject();
        // 1. 根目录数据文件
        for (String name : new String[]{"configs.json", "prefs.json", "history.json", "keep.json"}) {
            File f = new File(AppPaths.Root, name);
            if (!f.exists()) continue;
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if ("prefs.json".equals(name)) text = stripDeviceUuid(text);
            files.addProperty(name, text);
        }
        // 2. 网盘 Cookie（Pizazz 目录；TEMP 副本由宿主自动维护）
        File piz = new File(AppPaths.JarCache, "files" + File.separator + "Pizazz");
        for (String name : COOKIE_FILES) {
            File f = new File(piz, name);
            if (!f.exists()) continue;
            files.addProperty("cookie:" + name, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        JsonObject bundle = new JsonObject();
        bundle.addProperty("app", "FlyTV");
        bundle.addProperty("version", 1);
        bundle.addProperty("time", System.currentTimeMillis());
        bundle.add("files", files);
        byte[] blob = encrypt(gzip(bundle.toString().getBytes(StandardCharsets.UTF_8)), pass);
        int code = put(authUrl(url, pass), blob);
        if (code < 200 || code >= 300) throw new Exception("上传失败（HTTP " + code + "），请检查地址/账号密码");
        Setting.put("sync_last_up", String.valueOf(System.currentTimeMillis()));
        JsonObject o = status();
        o.addProperty("ok", true);
        o.addProperty("size", blob.length);
        o.addProperty("count", files.size());
        Logger.d("Sync", "上传完成: " + blob.length + " 字节, " + files.size() + " 项");
        return o;
    }

    /** 下载并还原（解密 → 配置覆盖 + 历史/收藏合并）。 */
    public static JsonObject download() throws Exception {
        String url = Setting.getString("sync_url", "");
        String pass = Setting.getString("sync_pass", "");
        if (url.isEmpty()) throw new Exception("请先填写同步地址");
        if (pass.isEmpty()) throw new Exception("请先设置同步口令（加密用）");
        byte[] blob = get(authUrl(url, pass));
        if (blob == null) throw new Exception("云端还没有备份（请先在主力设备上传）");
        String json = new String(gunzip(decrypt(blob, pass)), StandardCharsets.UTF_8);
        JsonObject bundle = JsonUtil.parseObj(json);
        JsonObject files = bundle == null ? null : bundle.getAsJsonObject("files");
        if (files == null) throw new Exception("云端文件格式不对（或口令错误）");
        int applied = 0;
        // 1. 配置类：配置链接按 url 合并（不覆盖本机、不改当前选中），prefs 只应用通用设置
        for (String name : new String[]{"configs.json", "prefs.json"}) {
            if (!files.has(name)) continue;
            backup(name);
            if ("prefs.json".equals(name)) {
                applyPrefs(files.get(name).getAsString());
            } else {
                if (mergeConfigsText(files.get(name).getAsString())) Stores.clearCache();
            }
            applied++;
        }
        // 1.1 云端已删除的配置：本机也删掉
        if (files.has("config-deleted.json")) {
            if (mergeTombstonesText(files.get("config-deleted.json").getAsString())) Stores.clearCache();
        }
        if (files.has("history.json")) {
            JsonArray arr = JsonUtil.parseArr(files.get("history.json").getAsString());
            long deadline = System.currentTimeMillis() - Stores.HISTORY_TIME;
            if (arr != null) for (JsonElement e : arr) {
                try {
                    if (!e.isJsonObject()) continue;
                    JsonObject t = e.getAsJsonObject();
                    String key = JsonUtil.str(t, "key", "");
                    if (key.isEmpty()) continue;
                    long ct = JsonUtil.lng(t, "createTime", 0);
                    if (ct > 0 && ct < deadline) continue; // 超保留期的旧记录不再导入
                    int cid = JsonUtil.integer(t, "cid", Api.currentCid());
                    JsonObject mine = Stores.findHistory(cid, key);
                    if (mergeShouldApply(t, mine)) {
                        t.addProperty("cid", cid);
                        Stores.saveHistoryKeepTime(t);
                    }
                } catch (Exception ignored) { }
            }
            applied++;
        }
        // 3. 收藏：按 key 去重合并
        if (files.has("keep.json")) {
            JsonArray arr = JsonUtil.parseArr(files.get("keep.json").getAsString());
            if (arr != null) for (JsonElement e : arr) {
                try {
                    if (!e.isJsonObject()) continue;
                    JsonObject t = e.getAsJsonObject();
                    String key = JsonUtil.str(t, "key", "");
                    if (key.isEmpty()) continue;
                    int cid = JsonUtil.integer(t, "cid", Api.currentCid());
                    if (Stores.findKeep(cid, key) == null) {
                        t.addProperty("cid", cid);
                        Stores.saveKeep(t);
                    }
                } catch (Exception ignored) { }
            }
            applied++;
        }
        // 4. 网盘 Cookie：写回 Pizazz + TEMP 两份（宿主与引擎共用）
        for (String name : COOKIE_FILES) {
            String field = "cookie:" + name;
            if (!files.has(field)) continue;
            String text = files.get(field).getAsString();
            File piz = new File(AppPaths.JarCache, "files" + File.separator + "Pizazz");
            if (!piz.exists()) piz.mkdirs();
            writeFile(new File(piz, name), text);
            writeFile(new File(piz, name.replace(".txt", "")), text);
            String tmpDir = System.getenv("TEMP");
            if (tmpDir != null) {
                File tmp = new File(tmpDir, "TVBox");
                if (!tmp.exists()) tmp.mkdirs();
                writeFile(new File(tmp, name), text);
                writeFile(new File(tmp, name.replace(".txt", "")), text);
            }
            applied++;
        }
        Setting.put("sync_last_down", String.valueOf(System.currentTimeMillis()));
        // 5. 刷新内存缓存（配置/设置/站点）
        Stores.clearCache();
        Setting.load();
        try { VodConfig.loadStartup(); } catch (Exception ignored) { }
        JsonObject o = status();
        o.addProperty("ok", true);
        o.addProperty("applied", applied);
        o.addProperty("size", blob.length);
        Logger.d("Sync", "下载还原完成: " + applied + " 项");
        return o;
    }

    // ---------- 自动同步（历史/收藏，独立小文件 history.dat） ----------

    /** 由同步地址推导历史小文件地址（token-hist：每人独立，符合同步服务 token 规则）。 */
    static String historyUrl(String url) {
        try {
            int q = url.indexOf('?');
            String base = q >= 0 ? url.substring(0, q) : url;
            String qs = q >= 0 ? url.substring(q) : "";
            int slash = base.lastIndexOf('/');
            if (slash < 0) return base + "-hist" + qs;
            String token = base.substring(slash + 1);
            if (token.isEmpty()) return base + "history" + qs;
            return base.substring(0, slash + 1) + token + "-hist" + qs;
        } catch (Exception e) {
            return url + "-hist";
        }
    }

    /** 由同步地址推导网盘Key小文件地址（token-pan）。 */
    static String panUrl(String url) {
        try {
            int q = url.indexOf('?');
            String base = q >= 0 ? url.substring(0, q) : url;
            String qs = q >= 0 ? url.substring(q) : "";
            int slash = base.lastIndexOf('/');
            if (slash < 0) return base + "-pan" + qs;
            String token = base.substring(slash + 1);
            if (token.isEmpty()) return base + "pan" + qs;
            return base.substring(0, slash + 1) + token + "-pan" + qs;
        } catch (Exception e) {
            return url + "-pan";
        }
    }

    static volatile boolean histHealNeeded = false; // 云端记录比本地浅被拒收 → 需要回传把云端纠正回深进度

    /** 合并判定：进度深者优先（集数大者胜；同集位置大者胜），深记录 24 小时内有效；都不在追时按时间戳新旧。 */
    static boolean mergeShouldApply(JsonObject cloud, JsonObject mine) {
        if (mine == null) return true;
        long fresh = 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        int cloudIdx = JsonUtil.integer(cloud, "playIndex", -1);
        int mineIdx = JsonUtil.integer(mine, "playIndex", -1);
        long cloudPos = JsonUtil.lng(cloud, "position", -1);
        long minePos = JsonUtil.lng(mine, "position", -1);
        boolean cloudDeeper = cloudIdx > mineIdx || (cloudIdx == mineIdx && cloudPos > minePos);
        boolean mineDeeper = mineIdx > cloudIdx || (mineIdx == cloudIdx && minePos > cloudPos);
        long cloudCt = JsonUtil.lng(cloud, "createTime", 0);
        long mineCt = JsonUtil.lng(mine, "createTime", 0);
        if (cloudDeeper && cloudCt > now - fresh) return true;    // 云端更深且在追 → 收下（防"浅但新"的记录顶掉进度）
        if (mineDeeper && mineCt > now - fresh) {                 // 本地更深且在追 → 拒收，随后回传纠正云端
            histHealNeeded = true;
            return false;
        }
        return cloudCt > mineCt;
    }

    /** 合并历史数组（进度深者优先，规则见 mergeShouldApply）。 */
    static void mergeHistoryText(String text) {
        JsonArray arr = JsonUtil.parseArr(text);
        if (arr == null) return;
        int applied = 0;
        String first = "";
        long deadline = System.currentTimeMillis() - Stores.HISTORY_TIME;
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject t = e.getAsJsonObject();
                String key = JsonUtil.str(t, "key", "");
                if (key.isEmpty()) continue;
                long ct = JsonUtil.lng(t, "createTime", 0);
                if (ct > 0 && ct < deadline) continue; // 超过保留期（60天）的旧记录不再导入，避免每轮反复写入
                int cid = JsonUtil.integer(t, "cid", Api.currentCid());
                JsonObject mine = Stores.findHistory(cid, key);
                if (mergeShouldApply(t, mine)) {
                    t.addProperty("cid", cid);   // 统一补 cid：避免下次按 cid 找不到导致重复保存/两端互推
                    Stores.saveHistoryKeepTime(t);
                    applied++;
                    if (first.isEmpty()) first = key + "@" + cid;
                }
            } catch (Exception ignored) { }
        }
        if (applied > 0) Logger.d("Sync", "合并历史：应用 " + applied + " 条，首个 " + first);
    }

    /** 过滤掉超过保留期的历史记录（云端长期累积的旧数据不再回传）。 */
    static JsonArray freshHistory(JsonArray arr) {
        JsonArray out = new JsonArray();
        if (arr == null) return out;
        long deadline = System.currentTimeMillis() - Stores.HISTORY_TIME;
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                long ct = JsonUtil.lng(e.getAsJsonObject(), "createTime", 0);
                if (ct > 0 && ct < deadline) continue;
                out.add(e);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 合并收藏数组（key 去重）。 */
    static void mergeKeepText(String text) {
        JsonArray arr = JsonUtil.parseArr(text);
        if (arr == null) return;
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject t = e.getAsJsonObject();
                String key = JsonUtil.str(t, "key", "");
                if (key.isEmpty()) continue;
                int cid = JsonUtil.integer(t, "cid", Api.currentCid());
                if (Stores.findKeep(cid, key) == null) {
                    t.addProperty("cid", cid);
                    Stores.saveKeep(t);
                }
            } catch (Exception ignored) { }
        }
    }

    /**
     * 合并配置列表（按 url+type 去重）：只补云端独有的，不覆盖本地条目，
     * 也绝不改本机当前选中的配置（避免同步后配置被悄悄换掉）。
     */
    static boolean mergeConfigsText(String text) {
        JsonArray arr = JsonUtil.parseArr(text);
        if (arr == null || arr.size() == 0) return false;
        boolean changed = false;
        int maxId = 0;
        for (JsonObject c : Stores.configs()) maxId = Math.max(maxId, JsonUtil.integer(c, "id", 0));
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                String url = JsonUtil.str(c, "url", "");
                if (url.isEmpty()) continue;
                int type = JsonUtil.integer(c, "type", 0);
                if (Stores.tombstoned(url, type, JsonUtil.lng(c, "time", 0))) continue; // 云端已删除的配置不再加回
                boolean exists = false;
                for (JsonObject x : Stores.configs()) {
                    if (url.equals(JsonUtil.str(x, "url", "")) && JsonUtil.integer(x, "type", 0) == type) { exists = true; break; }
                }
                if (exists) continue;
                JsonObject copy = c.deepCopy();
                int id = JsonUtil.integer(copy, "id", 0);
                boolean idTaken = false;
                for (JsonObject x : Stores.configs()) if (JsonUtil.integer(x, "id", 0) == id) { idTaken = true; break; }
                if (id <= 0 || idTaken) copy.addProperty("id", ++maxId);
                else maxId = Math.max(maxId, id);
                Stores.configs().add(copy);
                changed = true;
            } catch (Exception ignored) { }
        }
        if (changed) Stores.persistConfigs();
        return changed;
    }

    /** 配置文件的轻量签名（大小 + 修改时间）。 */
    static String configSig() {
        try {
            File f = new File(AppPaths.Root, "configs.json");
            return f.exists() ? (f.length() + "@" + f.lastModified()) : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /** 合并云端"已删除配置"标记，并删掉本机对应配置。 */
    static boolean mergeTombstonesText(String text) {
        JsonArray arr = JsonUtil.parseArr(text);
        if (arr == null) return false;
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject t = e.getAsJsonObject();
                String url = JsonUtil.str(t, "url", "");
                if (url.isEmpty()) continue;
                Stores.addTombstone(url, JsonUtil.integer(t, "type", 0), JsonUtil.lng(t, "time", 0));
            } catch (Exception ignored) { }
        }
        boolean changed = false;
        try {
            java.util.List<JsonObject> list = new java.util.ArrayList<>(Stores.configs());
            for (JsonObject c : list) {
                String url = JsonUtil.str(c, "url", "");
                int type = JsonUtil.integer(c, "type", 0);
                if (Stores.tombstoned(url, type, JsonUtil.lng(c, "time", 0))) {
                    Stores.configs().remove(c);
                    changed = true;
                }
            }
            if (changed) Stores.persistConfigs();
        } catch (Exception ignored) { }
        return changed;
    }

    private static volatile long lastHistRev = -1, lastKeepRev = -1;

    /** 读取本地 JSON 数组文件（不存在/损坏时返回空数组）。 */
    static JsonArray readArrayFile(File f) {
        try {
            if (!f.exists()) return new JsonArray();
            JsonArray a = JsonUtil.parseArr(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return a == null ? new JsonArray() : a;
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /** 按 key 合并两个数组：同一 key 取"进度更深"的那份（防云端/推送内容被浅进度覆盖）。 */
    static JsonArray unionByKey(JsonArray local, JsonArray cloud) {
        java.util.LinkedHashMap<String, JsonObject> map = new java.util.LinkedHashMap<>();
        if (local != null) for (JsonElement e : local) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String k = JsonUtil.str(t, "key", "");
            if (!k.isEmpty()) map.put(k, t);
        }
        if (cloud != null) for (JsonElement e : cloud) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String k = JsonUtil.str(t, "key", "");
            if (k.isEmpty()) continue;
            JsonObject have = map.get(k);
            if (have == null || deeperThan(t, have)) map.put(k, t);
        }
        JsonArray out = new JsonArray();
        for (JsonObject o : map.values()) out.add(o);
        return out;
    }

    /** t 是否比 other 进度更深（集数大者深；同集位置大者深；同深度看创建时间新者）。 */
    static boolean deeperThan(JsonObject t, JsonObject other) {
        int ai = JsonUtil.integer(t, "playIndex", -1), bi = JsonUtil.integer(other, "playIndex", -1);
        if (ai != bi) return ai > bi;
        long ap = JsonUtil.lng(t, "position", -1), bp = JsonUtil.lng(other, "position", -1);
        if (ap != bp) return ap > bp;
        return JsonUtil.lng(t, "createTime", 0) > JsonUtil.lng(other, "createTime", 0);
    }

    /** 自动同步一次（按设置间隔轮询模式）。 */
    public static JsonObject autoSync() throws Exception { return autoSync(false); }

    private static final Object SYNC_LOCK = new Object();

    /** 自动同步一次（加锁串行：WS 通知与定时循环可能同时触发，防止并发拉取/上传交错）。 */
    public static JsonObject autoSync(boolean waitMode) throws Exception {
        synchronized (SYNC_LOCK) {
            return autoSyncLocked(waitMode);
        }
    }

    private static JsonObject autoSyncLocked(boolean waitMode) throws Exception {
        String url = Setting.getString("sync_url", "");
        String pass = Setting.getString("sync_pass", "");
        JsonObject o = new JsonObject();
        if (url.isEmpty() || pass.isEmpty()) { o.addProperty("skip", true); return o; }
        // 防止通知风暴：同一轮同步至少间隔 2 秒
        long now0 = System.currentTimeMillis();
        if (!waitMode && lastSyncAt > 0 && now0 - lastSyncAt < 2000) {
            o.addProperty("ok", true);
            o.addProperty("throttled", true);
            return o;
        }
        long t0 = now0;
        String hurl = historyUrl(authUrl(url, pass));
        String pullUrl = hurl;
        String rev = REVS.get(revKey(hurl));
        if (waitMode && rev != null && !rev.isEmpty()) {
            pullUrl = hurl + (hurl.contains("?") ? "&" : "?") + "wait=20&rev=" + java.net.URLEncoder.encode(rev, "UTF-8");
        }
        long histBefore = Stores.historyRevision(), keepBefore = Stores.keepRevision();
        String cfgBefore = configSig();
        JsonArray cloudHist = null, cloudKeep = null;
        byte[] blob = get(pullUrl);
        if (blob != null) {
            String json = new String(gunzip(decrypt(blob, pass)), StandardCharsets.UTF_8);
            JsonObject bundle = JsonUtil.parseObj(json);
            JsonObject files = bundle == null ? null : bundle.getAsJsonObject("files");
            if (files != null) {
                if (files.has("history.json")) {
                    cloudHist = JsonUtil.parseArr(files.get("history.json").getAsString());
                    mergeHistoryText(files.get("history.json").getAsString());
                }
                if (files.has("keep.json")) {
                    cloudKeep = JsonUtil.parseArr(files.get("keep.json").getAsString());
                    mergeKeepText(files.get("keep.json").getAsString());
                }
                // 配置链接：按 url+type 合并，只补云端独有的，不改本机当前配置
                if (files.has("configs.json")) mergeConfigsText(files.get("configs.json").getAsString());
                // 云端删掉的配置：本机也删掉
                if (files.has("config-deleted.json")) mergeTombstonesText(files.get("config-deleted.json").getAsString());
            }
        }
        // 网盘 Key：顺带拉一次（token-pan），内容有变化才写入
        applyCloudPan(panUrl(authUrl(url, pass)), pass);
        boolean pulled = Stores.historyRevision() != histBefore || Stores.keepRevision() != keepBefore
                || !cfgBefore.equals(configSig());
        long hr = Stores.historyRevision(), kr = Stores.keepRevision();
        String cfgSig = configSig();
        boolean pushed = false;
        if (pulled || histHealNeeded || hr != lastHistRev || kr != lastKeepRev || !cfgSig.equals(lastCfgSig)) {
            String histText = unionByKey(readArrayFile(new File(AppPaths.Root, "history.json")), freshHistory(cloudHist)).toString();
            String keepText = unionByKey(readArrayFile(new File(AppPaths.Root, "keep.json")), cloudKeep).toString();
            String cfgText = readTextFile(new File(AppPaths.Root, "configs.json"));
            String tombText = readTextFile(new File(AppPaths.Root, "config-deleted.json"));
            // 内容没变就不写：避免"PUT → 服务端广播 changed → 再同步"的自我循环
            String hash = sha256Hex(histText + "\n" + keepText + "\n" + cfgText + "\n" + tombText);
            if (!hash.equals(lastPushedHash)) {
                JsonObject files = new JsonObject();
                files.addProperty("history.json", histText);
                files.addProperty("keep.json", keepText);
                if (!cfgText.isEmpty()) files.addProperty("configs.json", cfgText);
                if (!tombText.isEmpty()) files.addProperty("config-deleted.json", tombText);
                JsonObject bundle = new JsonObject();
                bundle.addProperty("app", "FlyTV");
                bundle.addProperty("kind", "history");
                bundle.addProperty("time", System.currentTimeMillis());
                bundle.add("files", files);
                byte[] data = encrypt(gzip(bundle.toString().getBytes(StandardCharsets.UTF_8)), pass);
                int code = put(hurl, data);
                if (code >= 200 && code < 300) {
                    pushed = true;
                    histHealNeeded = false;
                    lastHistRev = hr;
                    lastKeepRev = kr;
                    lastCfgSig = cfgSig;
                    lastPushedHash = hash;
                }
            } else {
                // 内容一致：只更新本地游标，不重复上传
                lastHistRev = hr;
                lastKeepRev = kr;
                lastCfgSig = cfgSig;
            }
        }
        lastSyncAt = System.currentTimeMillis();
        lastSyncMs = lastSyncAt - t0;
        lastPulled = pulled;
        lastPushed = pushed;
        lastSyncError = "";
        o.addProperty("ok", true);
        o.addProperty("pulled", pulled);
        o.addProperty("pushed", pushed);
        o.addProperty("histChanged", Stores.historyRevision() != histBefore);
        o.addProperty("keepChanged", Stores.keepRevision() != keepBefore);
        o.addProperty("cfgChanged", !cfgBefore.equals(configSig()));
        o.addProperty("histRev", hr);
        o.addProperty("keepRev", kr);
        o.addProperty("ms", lastSyncMs);
        return o;
    }

    /** 拉取并应用云端网盘 Key（token-pan）；内容与上次一致则跳过。 */
    private static void applyCloudPan(String purl, String pass) {
        try {
            byte[] pb = get(purl);
            if (pb == null || pb.length == 0) return;
            JsonObject bundle = JsonUtil.parseObj(new String(gunzip(decrypt(pb, pass)), StandardCharsets.UTF_8));
            JsonObject kv = bundle == null ? null : bundle.getAsJsonObject("keys");
            if (kv == null || kv.size() == 0) return;
            String hash = sha256Hex(kv.toString());
            if (hash.equals(lastPanHash)) return;
            int n = PanKeys.apply(kv);
            lastPanHash = hash;
            Logger.d("Sync", "网盘Key已从云端更新: " + n + " 项");
        } catch (Exception e) {
            Logger.d("Sync", "网盘Key拉取失败: " + e.getMessage());
        }
    }

    static volatile String lastPanHash = "";
    static volatile String lastCfgSig = "";
    static volatile String lastPushedHash = "";

    private static String readTextFile(File f) {
        try {
            return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 记录版本号时去掉 wait/rev 参数，保证同一文件用同一 key。 */
    static String revKey(String url) {
        try {
            return url.replaceAll("[?&](wait|rev)=[^&]*", "").replaceAll("[?&]+$", "");
        } catch (Exception e) {
            return url;
        }
    }

    /** 自动同步线程：按设置间隔（默认 2 分钟）拉取合并 + 有变化回传；15 秒粒度检查，改设置即时生效。 */
    public static void startAutoSync() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException ignored) { return; }
            long last = 0;
            while (true) {
                try {
                    if (Setting.getBool("sync_auto", true)) {
                        // WS 长连接在线时：不做长轮询（由 WS 通知触发同步），只轻量轮转
                        if (SyncWS.isAlive()) {
                            Thread.sleep(5000);
                        } else if (lastGetRev != null) {
                            JsonObject r = autoSync(true);
                            if (JsonUtil.bool(r, "pulled", false) || JsonUtil.bool(r, "pushed", false)) {
                                Logger.d("AutoSync", "实时同步 拉取=" + JsonUtil.bool(r, "pulled", false) + " 回传=" + JsonUtil.bool(r, "pushed", false));
                            }
                        } else {
                            int iv = Setting.getInt("sync_interval", 1);
                            if (iv < 1) iv = 1;
                            if (iv > 60) iv = 60;
                            long now = System.currentTimeMillis();
                            if (now - last >= iv * 60_000L) {
                                last = now;
                                JsonObject r = autoSync(false);
                                if (JsonUtil.bool(r, "pulled", false) || JsonUtil.bool(r, "pushed", false)) {
                                    Logger.d("AutoSync", "自动同步 拉取=" + JsonUtil.bool(r, "pulled", false) + " 回传=" + JsonUtil.bool(r, "pushed", false));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.d("AutoSync", "异常: " + e.getMessage());
                    try { Thread.sleep(10000); } catch (InterruptedException ignored) { return; }
                }
                try { Thread.sleep(lastGetRev != null ? 1000 : 15 * 1000); } catch (InterruptedException ignored) { return; }
            }
        }, "auto-sync");
        t.setDaemon(true);
        t.start();
        Logger.d("AutoSync", "自动同步已启动（默认每 1 分钟，可设置 1-60）");
    }

    // ---------- 分享链接加密（AES-256-GCM；钥匙与 Go 服务端内置一致，改动需两端同步） ----------

    private static final byte[] LINK_KEY = sha256Bytes("FlyTV-Link-v1-2026");

    static byte[] sha256Bytes(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[32];
        }
    }

    /** 生成 flytv:// 分享码（IV 前置 + AES-GCM，base64url 无填充）。 */
    static String encLink(String json) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(LINK_KEY, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    static byte[] linkDecrypt(byte[] blob) throws Exception {
        if (blob.length < 12 + 16) throw new Exception("分享码不完整");
        byte[] iv = java.util.Arrays.copyOfRange(blob, 0, 12);
        byte[] ct = java.util.Arrays.copyOfRange(blob, 12, blob.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(LINK_KEY, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- 内部实现 ----------

    /** 只属于本机、不能跨设备覆盖的键（配置选择、同步凭据、设备标识）。 */
    static final String[] LOCAL_ONLY_KEYS = {
            "device_uuid", "sync_url", "sync_pass", "sync_last_up", "sync_last_down",
            "sync_auto", "sync_interval", "config_vod", "config_live", "web_password", "local_server_lan"
    };

    static String stripDeviceUuid(String prefsJson) {
        try {
            JsonObject o = JsonUtil.parseObj(prefsJson);
            if (o == null) return prefsJson;
            for (String k : LOCAL_ONLY_KEYS) o.remove(k);
            return o.toString();
        } catch (Exception e) {
            return prefsJson;
        }
    }

    /** prefs 合并：只应用通用设置，本机专属（配置选择/同步链接/设备标识）一律保留。 */
    static void applyPrefs(String remoteJson) {
        JsonObject o = JsonUtil.parseObj(remoteJson);
        if (o == null) return;
        for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            boolean skip = false;
            for (String x : LOCAL_ONLY_KEYS) if (x.equals(k)) { skip = true; break; }
            if (skip) continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonPrimitive()) {
                com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
                if (p.isBoolean()) Setting.put(k, p.getAsBoolean());
                else if (p.isNumber()) Setting.put(k, p.getAsNumber());
                else Setting.put(k, p.getAsString());
            }
        }
    }

    static void backup(String name) {
        try {
            File f = new File(AppPaths.Root, name);
            if (f.exists()) Files.copy(f.toPath(), new File(AppPaths.Root, name + ".bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Logger.e("Sync", "备份失败 " + name + ": " + e.getMessage());
        }
    }

    static void writeFile(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 加密（AES-256-GCM + PBKDF2） ----------

    static byte[] encrypt(byte[] plain, String pass) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        SecretKeySpec key = deriveKey(pass, salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(plain);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(MAGIC);
        bos.write(salt);
        bos.write(iv);
        bos.write(enc);
        return bos.toByteArray();
    }

    static byte[] decrypt(byte[] blob, String pass) throws Exception {
        if (blob.length < MAGIC.length + 28) throw new Exception("云端文件不完整");
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) throw new Exception("云端文件不是 FlyTV 同步包");
        }
        byte[] salt = java.util.Arrays.copyOfRange(blob, MAGIC.length, MAGIC.length + 16);
        byte[] iv = java.util.Arrays.copyOfRange(blob, MAGIC.length + 16, MAGIC.length + 28);
        byte[] enc = java.util.Arrays.copyOfRange(blob, MAGIC.length + 28, blob.length);
        SecretKeySpec key = deriveKey(pass, salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(enc);
        } catch (Exception e) {
            throw new Exception("解密失败：同步口令不对？");
        }
    }

    static SecretKeySpec deriveKey(String pass, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 120000, 256);
        byte[] key = f.generateSecret(spec).getEncoded();
        return new SecretKeySpec(key, "AES");
    }

    static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ---------- WebDAV（Basic 认证从 URL 解析） ----------

    static HttpURLConnection open(String urlStr, String method) throws Exception {
        URL u = new URL(urlStr);
        String userInfo = u.getUserInfo();
        String clean = urlStr;
        if (userInfo != null && !userInfo.isEmpty()) {
            clean = urlStr.replaceFirst("//" + java.util.regex.Pattern.quote(userInfo) + "@", "//");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(clean).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(false);
        if (userInfo != null && !userInfo.isEmpty()) {
            String b64 = Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + b64);
        }
        return c;
    }

    static int put(String url, byte[] body) throws Exception {
        HttpURLConnection c = open(url, "PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.getOutputStream().write(body);
        int code = c.getResponseCode();
        String rev = null;
        try {
            // 记录本次写入后的版本号：避免下一次长轮询立刻又"看到自己刚写的文件"造成互推循环
            rev = c.getHeaderField("X-Rev");
            if (rev != null && !rev.isEmpty()) recordRev(url, rev);
        } catch (Exception ignored) { }
        try { recordState(url, code, body.length, rev, c.getHeaderField("Last-Modified")); } catch (Exception ignored) { }
        c.disconnect();
        return code;
    }

    static volatile String lastGetRev = null;

    static void recordRev(String url, String rev) {
        try {
            String k = revKey(url);
            REVS.put(k, rev);
            if (k.contains("-hist")) lastGetRev = rev;
        } catch (Exception ignored) { }
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url, "GET");
        int code = c.getResponseCode();
        String rev = null;
        try {
            rev = c.getHeaderField("X-Rev");
            if (rev != null && !rev.isEmpty()) recordRev(url, rev);
        } catch (Exception ignored) { }
        String lm = c.getHeaderField("Last-Modified");
        long hlen = c.getContentLengthLong();
        if (code == 404 || code == 204) {
            try { recordState(url, code, 0, rev, lm); } catch (Exception ignored) { }
            c.disconnect();
            return null;
        }
        if (code < 200 || code >= 300) {
            try { recordState(url, code, Math.max(0, hlen), rev, lm); } catch (Exception ignored) { }
            c.disconnect();
            throw new Exception("下载失败（HTTP " + code + "），请检查地址/账号密码");
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BLOB) throw new Exception("云端文件过大（超过 8MB）");
                bos.write(buf, 0, n);
            }
            try { recordState(url, code, total, rev, lm); } catch (Exception ignored) { }
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
