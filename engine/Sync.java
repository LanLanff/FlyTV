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

    /** 解析同步输入：支持 ①URL?key=xxx ②flytv://<AES加密分享码> ③flytv://<base64 JSON 旧版>。返回 [url, key]。 */
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
        try { return autoSync().toString(); } catch (Exception e) { return errJson(e); }
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
        // 1. 配置类：覆盖 + .bak 备份
        for (String name : new String[]{"configs.json", "prefs.json"}) {
            if (!files.has(name)) continue;
            backup(name);
            if ("prefs.json".equals(name)) {
                applyPrefs(files.get(name).getAsString());
            } else {
                writeFile(new File(AppPaths.Root, name), files.get(name).getAsString());
            }
            applied++;
        }
        // 2. 历史：按 key 合并（createTime 新的胜出）
        if (files.has("history.json")) {
            JsonArray arr = JsonUtil.parseArr(files.get("history.json").getAsString());
            if (arr != null) for (JsonElement e : arr) {
                try {
                    if (!e.isJsonObject()) continue;
                    JsonObject t = e.getAsJsonObject();
                    String key = JsonUtil.str(t, "key", "");
                    if (key.isEmpty()) continue;
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
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject t = e.getAsJsonObject();
                String key = JsonUtil.str(t, "key", "");
                if (key.isEmpty()) continue;
                int cid = JsonUtil.integer(t, "cid", Api.currentCid());
                JsonObject mine = Stores.findHistory(cid, key);
                if (mergeShouldApply(t, mine)) {
                    t.addProperty("cid", cid);   // 统一补 cid：避免下次按 cid 找不到导致重复保存/两端互推
                    Stores.saveHistoryKeepTime(t);
                }
            } catch (Exception ignored) { }
        }
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
        String hurl = historyUrl(authUrl(url, pass));
        String pullUrl = hurl;
        if (waitMode && lastGetRev != null && !lastGetRev.isEmpty()) {
            pullUrl = hurl + (hurl.contains("?") ? "&" : "?") + "wait=20&rev=" + java.net.URLEncoder.encode(lastGetRev, "UTF-8");
        }
        long histBefore = Stores.historyRevision(), keepBefore = Stores.keepRevision();
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
            }
        }
        boolean pulled = Stores.historyRevision() != histBefore || Stores.keepRevision() != keepBefore;
        long hr = Stores.historyRevision(), kr = Stores.keepRevision();
        boolean pushed = false;
        if (pulled || histHealNeeded || hr != lastHistRev || kr != lastKeepRev) {
            JsonObject files = new JsonObject();
            // 并集上传：本地 + 本轮从云端拉到的全部记录（本地保留策略/老化清掉的老记录也带上），
            // 保证云端文件只增不减，不会因为某台设备的本地精简而丢历史。
            JsonArray hist = unionByKey(readArrayFile(new File(AppPaths.Root, "history.json")), cloudHist);
            JsonArray keep = unionByKey(readArrayFile(new File(AppPaths.Root, "keep.json")), cloudKeep);
            files.addProperty("history.json", hist.toString());
            files.addProperty("keep.json", keep.toString());
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
            }
        }
        o.addProperty("ok", true);
        o.addProperty("pulled", pulled);
        o.addProperty("pushed", pushed);
        return o;
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

    static String stripDeviceUuid(String prefsJson) {
        try {
            JsonObject o = JsonUtil.parseObj(prefsJson);
            if (o == null) return prefsJson;
            o.remove("device_uuid");
            return o.toString();
        } catch (Exception e) {
            return prefsJson;
        }
    }

    /** prefs 合并：除 device_uuid 外逐项写入（保留本机设备标识）。 */
    static void applyPrefs(String remoteJson) {
        JsonObject o = JsonUtil.parseObj(remoteJson);
        if (o == null) return;
        for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if ("device_uuid".equals(k)) continue;
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
        try {
            // 记录本次写入后的版本号：避免下一次长轮询立刻又"看到自己刚写的文件"造成互推循环
            String rev = c.getHeaderField("X-Rev");
            if (rev != null && !rev.isEmpty()) lastGetRev = rev;
        } catch (Exception ignored) { }
        c.disconnect();
        return code;
    }

    static volatile String lastGetRev = null;

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url, "GET");
        int code = c.getResponseCode();
        try {
            String rev = c.getHeaderField("X-Rev");
            if (rev != null && !rev.isEmpty()) lastGetRev = rev;
        } catch (Exception ignored) { }
        if (code == 404 || code == 204) { c.disconnect(); return null; }
        if (code < 200 || code >= 300) {
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
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
