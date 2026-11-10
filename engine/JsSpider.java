package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JS 蜘蛛宿主（drpy2 兼容）。
 *
 * 结构：drpy2.min.js（框架，ESM 引用 drpy-core-lite.min.js）+ ext 里的规则脚本。
 * 做法：把 ESM 的 import/export 换成普通脚本，用 GraalJS 依次执行 core → 框架 → 规则，
 * 然后调用框架暴露的 home/category/detail/search/play。
 */
public final class JsSpider {

    private static final int MAX_CACHE = 5;
    /** 构建串行化：预热与用户请求不会各自构建一遍（否则要等两倍时间）。 */
    private static final Object BUILD_LOCK = new Object();
    private static final Map<String, SiteJs> CACHE = new LinkedHashMap<String, SiteJs>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SiteJs> eldest) {
            if (size() > MAX_CACHE) {
                try { eldest.getValue().cx.close(true); } catch (Throwable ignored) { }
                return true;
            }
            return false;
        }
    };

    static final class SiteJs {
        Context cx;
        Value def;
        Value bindings;
        String apiUrl = "";
        String ruleUrl = "";      // 当前已 init 的规则
        String host = "";         // 规则里的站点域名（用于把相对图片地址补全）
        final Map<String, String> local = new HashMap<String, String>();
        long used;
    }

    public static boolean isJs(VodConfig.Site s) {
        if (s == null || s.type != 3 || s.api == null) return false;
        String a = s.api.trim().toLowerCase();
        int q = a.indexOf('?');
        if (q >= 0) a = a.substring(0, q);
        return a.endsWith(".js");
    }

    public static void ensure(VodConfig.Site site) throws Exception {
        get(site);
    }

    /** 按「框架地址」缓存：同一份 drpy2 的多个站点共用一个 JS 上下文，只重新 init 规则。 */
    static SiteJs get(VodConfig.Site site) throws Exception {
        String owner = site.ownerUrl == null ? "" : site.ownerUrl;
        String apiUrl = absUrl(owner, site.api);
        String ruleUrl = (site.ext == null || site.ext.isEmpty()) ? "" : absUrl(owner, site.ext);
        SiteJs hit;
        synchronized (CACHE) {
            hit = CACHE.get(apiUrl);
            if (hit != null) hit.used = System.currentTimeMillis();
        }
        if (hit == null) {
            synchronized (BUILD_LOCK) {
                synchronized (CACHE) { hit = CACHE.get(apiUrl); }
                if (hit == null) {
                    SiteJs built = build(site, apiUrl, ruleUrl);
                    synchronized (CACHE) { CACHE.put(apiUrl, built); }
                    return built;
                }
            }
        }
        if (!ruleUrl.equals(hit.ruleUrl)) {
            synchronized (hit) {
                if (!ruleUrl.equals(hit.ruleUrl)) {
                    initRule(hit, ruleUrl);
                    hit.ruleUrl = ruleUrl;
                }
            }
        }
        return hit;
    }

    private static void initRule(SiteJs js, String ruleUrl) throws Exception {
        js.cx.eval("js", "var __JS_INIT_ERR__ = ''; try { __DRPY_DEFAULT__.init(" + q(ruleUrl) + "); } catch (e) { __JS_INIT_ERR__ = String((e && e.stack) || e); }");
        Value ie = js.cx.eval("js", "__JS_INIT_ERR__");
        if (ie != null && !ie.isNull() && !ie.asString().isEmpty()) {
            Logger.e("JsSpider", "init 失败(" + ruleUrl + "): " + ie.asString());
            throw new Exception("JS 规则初始化失败: " + ie.asString());
        }
        try {
            Value hv = js.cx.eval("js", "(function(){try{var r=__DRPY_DEFAULT__.getRule?__DRPY_DEFAULT__.getRule():null;return (r&&r.host)||''}catch(e){return ''}})()");
            js.host = (hv == null || hv.isNull()) ? "" : hv.asString();
        } catch (Throwable ignored) { }
    }

    /** 后台预热：把当前配置里前几个 JS 框架先加载好（同框架的其它源共用）。 */
    public static void warmup() {
        Thread t = new Thread(() -> {
            int built = 0;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (VodConfig.Site s : VodConfig.sites()) {
                if (!isJs(s)) continue;
                String api = (s.ownerUrl == null ? "" : s.ownerUrl) + "|" + s.api;
                if (!seen.add(api)) continue;
                try {
                    long t0 = System.currentTimeMillis();
                    get(s);
                    Logger.d("JsSpider", "预热完成: " + s.key + " " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable e) {
                    Logger.d("JsSpider", "预热失败: " + e.getMessage());
                }
                if (++built >= 3) break;
            }
        }, "js-warmup");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- 构建 ----------------

    private static SiteJs build(VodConfig.Site site, String apiUrl, String ruleUrl) throws Exception {

        String apiCode = fetchText(apiUrl);
        if (apiCode == null || apiCode.isEmpty()) throw new Exception("JS 框架下载失败: " + apiUrl);

        String coreCode = null;
        String framework = null;
        if (apiCode.contains("__DRPY") || apiCode.contains("drpy-core") || apiCode.matches("(?s).*\\bimport\\b.*")) {
            // drpy2 结构：api 是框架，core 在同目录
            String coreUrl = absUrl(apiUrl, "./drpy-core-lite.min.js");
            coreCode = fetchText(coreUrl);
            if (coreCode == null || coreCode.isEmpty()) throw new Exception("drpy core 下载失败: " + coreUrl);
            framework = transformFramework(apiCode);
            coreCode = transformCore(coreCode);
        } else {
            framework = null; // 单文件 JS 蜘蛛（直接是规则/函数）
        }

        Context cx = Context.newBuilder("js")
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        SiteJs js = new SiteJs();
        js.cx = cx;
        js.apiUrl = apiUrl;
        js.bindings = cx.getBindings("js");
        installHost(js, site);

        if (coreCode != null) cx.eval("js", coreCode);
        if (framework != null) {
            cx.eval("js", framework);
            String initArg = ruleUrl.isEmpty() ? (site.ext == null ? "" : site.ext) : ruleUrl;
            initRule(js, initArg);
            js.ruleUrl = initArg;
        } else {
            String rule = ruleUrl.isEmpty() ? apiCode : fetchText(ruleUrl);
            if (rule == null || rule.isEmpty()) throw new Exception("JS 规则下载失败: " + ruleUrl);
            cx.eval("js", transformRule(rule));
            js.ruleUrl = ruleUrl;
        }

        js.def = cx.eval("js", "typeof __DRPY_DEFAULT__ !== 'undefined' ? __DRPY_DEFAULT__ : null");
        if (js.def == null || js.def.isNull()) {
            // 退化：直接使用全局函数
            js.def = cx.eval("js", "({home: (typeof home==='function'?home:null), category:(typeof category==='function'?category:null), detail:(typeof detail==='function'?detail:null), play:(typeof play==='function'?play:null), search:(typeof search==='function'?search:null), homeVod:(typeof homeVod==='function'?homeVod:null)})");
        }
        Logger.d("JsSpider", "已加载: " + site.key + " ← " + apiUrl);
        return js;
    }

    // ---------------- 调用 ----------------

    public static String call(VodConfig.Site site, String method, Map<String, Object> extra, int timeoutMs) throws Exception {
        SiteJs js = get(site);
        synchronized (js) {
            String expr;
            if ("homeContent".equals(method)) expr = "typeof __DRPY_DEFAULT__.home==='function' ? __DRPY_DEFAULT__.home() : {}";
            else if ("homeVideoContent".equals(method)) expr = "typeof __DRPY_DEFAULT__.homeVod==='function' ? __DRPY_DEFAULT__.homeVod() : {list:[]}";
            else if ("categoryContent".equals(method)) {
                String tid = str(extra, "tid");
                String pg = str(extra, "pg");
                expr = "typeof __DRPY_DEFAULT__.category==='function' ? __DRPY_DEFAULT__.category(" + q(tid) + "," + q(pg) + ",false,{}) : {list:[]}";
            } else if ("detailContent".equals(method)) {
                Object ids = extra == null ? null : extra.get("ids");
                String idOne = "";
                if (ids instanceof JsonArray && ((JsonArray) ids).size() > 0) {
                    JsonElement e0 = ((JsonArray) ids).get(0);
                    idOne = e0.isJsonNull() ? "" : e0.getAsString();
                } else if (ids != null) {
                    idOne = String.valueOf(ids);
                }
                expr = "typeof __DRPY_DEFAULT__.detail==='function' ? __DRPY_DEFAULT__.detail(" + q(idOne) + ") : {list:[]}";
            } else if ("searchContent".equals(method)) {
                String wd = str(extra, "key");
                if (wd.isEmpty()) wd = str(extra, "wd");
                String quick = extra != null && Boolean.TRUE.equals(extra.get("quick")) ? "true" : "false";
                String pg = str(extra, "pg");
                if (pg.isEmpty()) pg = "1";
                expr = "typeof __DRPY_DEFAULT__.search==='function' ? __DRPY_DEFAULT__.search(" + q(wd) + "," + quick + "," + q(pg) + ") : {list:[]}";
            } else if ("playerContent".equals(method)) {
                String flag = str(extra, "flag");
                String id = str(extra, "id");
                expr = "typeof __DRPY_DEFAULT__.play==='function' ? __DRPY_DEFAULT__.play(" + q(flag) + "," + q(id) + ",[]) : {}";
            } else {
                throw new Exception("JS 源不支持的方法: " + method);
            }
            Value v = js.cx.eval("js", "try{ (function(){ var r = (" + expr + "); if (typeof r === 'string') { try { r = JSON.parse(r) } catch(e) {} } return JSON.stringify(r); })() }catch(e){ JSON.stringify({__err:String((e&&e.stack)||e)}) }");
            if (v == null || v.isNull()) return "{}";
            String s = v.asString();
            if (s != null && js.host != null && !js.host.isEmpty()) s = fixPics(s, js.host);
            Logger.d("JsSpider", "ret[" + method + "] " + (s == null ? "null" : s.substring(0, Math.min(400, s.length()))));
            if (s != null && s.contains("\"__err\"")) throw new Exception(s.substring(0, Math.min(500, s.length())));
            return s == null ? "{}" : s;
        }
    }

    // ---------------- 宿主函数 ----------------

    private static void installHost(SiteJs js, VodConfig.Site site) {
        Context cx = js.cx;
        Value b = js.bindings;
        b.putMember("__jslog", (ProxyExecutable) args -> {
            StringBuilder sb = new StringBuilder();
            for (Value a : args) { try { sb.append(a.isNull() ? "null" : a.toString()).append(' '); } catch (Throwable ignored) { } }
            Logger.d("JsSpider", "[js] " + sb);
            return "";
        });
        cx.eval("js", "var console={log:function(){__jslog.apply(null,Array.prototype.slice.call(arguments))},warn:function(){},error:function(){__jslog.apply(null,Array.prototype.slice.call(arguments))},info:function(){},debug:function(){}};");

        b.putMember("req", (ProxyExecutable) args -> httpReq(args, "GET"));
        b.putMember("post", (ProxyExecutable) args -> httpReq(args, "POST"));
        b.putMember("pdfh", (ProxyExecutable) args -> pdfh(args));
        b.putMember("pdfa", (ProxyExecutable) args -> pdfa(args));
        b.putMember("pd", (ProxyExecutable) args -> pdfh(args));
        b.putMember("joinUrl", (ProxyExecutable) args -> joinUrl(args));
        b.putMember("md5", (ProxyExecutable) args -> md5(args.length > 0 && !args[0].isNull() ? args[0].toString() : ""));
        b.putMember("getProxyUrl", (ProxyExecutable) args -> {
            String u = args.length > 0 && !args[0].isNull() ? args[0].asString() : "";
            return "http://127.0.0.1:" + WebServer.port() + "/proxy?url=" + URLEncoder.encode(u, StandardCharsets.UTF_8);
        });

        cx.eval("js", "var base64={encode:function(s){return __b64e(String(s))},decode:function(s){return __b64d(String(s))}};");
        b.putMember("__b64e", (ProxyExecutable) args -> Base64.getEncoder().encodeToString(args[0].asString().getBytes(StandardCharsets.UTF_8)));
        b.putMember("__b64d", (ProxyExecutable) args -> new String(Base64.getDecoder().decode(args[0].asString()), StandardCharsets.UTF_8));

        // local 存储（进程内持久，跨调用可用）
        b.putMember("__localGet", (ProxyExecutable) args -> {
            String k = args[0].asString();
            String v = js.local.get(k);
            return v == null ? (args.length > 1 && !args[1].isNull() ? args[1].asString() : "") : v;
        });
        b.putMember("__localSet", (ProxyExecutable) args -> {
            js.local.put(args[0].asString(), args[1].isNull() ? "" : args[1].asString());
            return "";
        });
        cx.eval("js", "var local={get:function(k,d){return __localGet(k,d)},set:function(k,v){return __localSet(k,v)},delete:function(k){return __localSet(k,'')}};");
    }

    private static Object httpReq(Value[] args, String defMethod) {
        Map<String, Object> res = new HashMap<String, Object>();
        String body = "";
        int status = 0;
        try {
            if (args.length == 0 || args[0].isNull()) {
                res.put("content", "");
                res.put("headers", new HashMap<String, Object>());
                res.put("status", 0);
                return org.graalvm.polyglot.proxy.ProxyObject.fromMap(res);
            }
            String url = args[0].asString();
            String method = defMethod;
            Map<String, String> headers = new HashMap<String, String>();
            String reqBody = null;
            int timeout = 15000;
            if (args.length > 1 && !args[1].isNull() && args[1].hasMembers()) {
                Value o = args[1];
                if (o.hasMember("method") && !o.getMember("method").isNull()) method = o.getMember("method").asString();
                if (o.hasMember("headers") && o.getMember("headers").hasMembers()) {
                    Value h = o.getMember("headers");
                    for (String k : h.getMemberKeys()) {
                        try { headers.put(k, h.getMember(k).asString()); } catch (Throwable ignored) { }
                    }
                }
                if (o.hasMember("body") && !o.getMember("body").isNull()) reqBody = o.getMember("body").asString();
                if (o.hasMember("data") && !o.getMember("data").isNull()) reqBody = o.getMember("data").asString();
                if (o.hasMember("timeout") && !o.getMember("timeout").isNull()) {
                    try { timeout = (int) o.getMember("timeout").asDouble(); } catch (Throwable ignored) { }
                }
            }
            if ("POST".equalsIgnoreCase(method)) {
                if (reqBody == null) reqBody = "";
                String ct = "";
                for (Map.Entry<String, String> e : headers.entrySet()) if ("content-type".equalsIgnoreCase(e.getKey())) ct = e.getValue();
                body = ct.toLowerCase().contains("json") ? HttpUtil.postJson(url, reqBody, headers, timeout) : HttpUtil.postForm(url, parseForm(reqBody), headers, timeout);
            } else {
                body = HttpUtil.get(url, headers, timeout);
            }
            status = 200;
        } catch (Throwable t) {
            Logger.d("JsSpider", "req 失败: " + t.getMessage());
            body = "";
        }
        res.put("content", body == null ? "" : body);
        res.put("headers", new HashMap<String, Object>());
        res.put("status", status);
        return org.graalvm.polyglot.proxy.ProxyObject.fromMap(res);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<String, String>();
        if (body == null) return m;
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                try {
                    m.put(java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8.name()),
                            java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8.name()));
                } catch (Exception ignored) { }
            }
        }
        return m;
    }

    private static String pdfh(Value[] args) {
        try {
            if (args.length < 2) return "";
            String html = args[0].isNull() ? "" : args[0].asString();
            String rule = args[1].asString();
            String[] parts = rule.split("&&");
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html == null ? "" : html);
            org.jsoup.nodes.Element el = doc.selectFirst(parts[0].trim());
            if (el == null) return "";
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                if (p.equalsIgnoreCase("Text")) return el.text();
                if (p.equalsIgnoreCase("Html")) return el.html();
                return el.attr(p);
            }
            return el.outerHtml();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object pdfa(Value[] args) {
        List<String> out = new ArrayList<String>();
        try {
            if (args.length < 2) return out;
            String html = args[0].isNull() ? "" : args[0].asString();
            String sel = args[1].asString();
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html == null ? "" : html);
            for (org.jsoup.nodes.Element e : doc.select(sel)) out.add(e.outerHtml());
        } catch (Throwable ignored) { }
        return out;
    }

    private static String joinUrl(Value[] args) {
        try {
            if (args.length == 1) return args[0].asString();
            String base = args[0].asString();
            String rel = args[1].asString();
            if (rel == null || rel.isEmpty()) return base;
            if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
            if (rel.startsWith("//")) return "https:" + rel;
            return URI.create(base).resolve(rel).toString();
        } catch (Throwable t) {
            return args.length > 1 ? args[1].toString() : "";
        }
    }

    /** 把 JS 源返回的相对图片地址补成绝对地址（否则前端代理取不到图）。 */
    private static String fixPics(String json, String host) {
        try {
            JsonObject o = JsonUtil.parseObj(json);
            if (o == null) return json;
            JsonArray list = o.getAsJsonArray("list");
            if (list == null) return json;
            boolean changed = false;
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                if (v.has("vod_pic") && v.get("vod_pic").isJsonPrimitive()) {
                    String np = absPic(host, v.get("vod_pic").getAsString());
                    if (np != null) { v.addProperty("vod_pic", np); changed = true; }
                }
            }
            return changed ? o.toString() : json;
        } catch (Throwable t) {
            return json;
        }
    }

    private static String absPic(String host, String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.isEmpty() || s.startsWith("data:")) return null;
        if (s.startsWith("http://") || s.startsWith("https://")) return null;
        if (s.startsWith("//")) return "https:" + s;
        String h = host;
        if (!h.endsWith("/")) h = h + "/";
        // 相对站点根目录归一化（图片基本都在站点根下，去掉 ../）
        while (s.startsWith("../")) s = s.substring(3);
        while (s.startsWith("./")) s = s.substring(2);
        if (s.startsWith("/")) s = s.substring(1);
        return h + s;
    }

    // ---------------- 工具 ----------------

    private static String str(Map<String, Object> m, String k) {
        if (m == null) return "";
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String q(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")) + "'";
    }

    private static String jsStr(String s) {
        return JsonUtil.gson.toJson(s == null ? "" : s);
    }

    private static com.google.gson.Gson gson() { return JsonUtil.gson; }

    private static String absUrl(String base, String rel) {
        try {
            if (rel == null) return "";
            rel = rel.trim();
            if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
            if (base == null || base.isEmpty()) return rel;
            if (rel.startsWith("//")) return "https:" + rel;
            return URI.create(base).resolve(rel).toString();
        } catch (Throwable t) {
            return rel;
        }
    }

    /** 下载 JS（jsdelivr 会 403，自动换镜像；兼容 gh-proxy 套娃地址）。 */
    static String fetchText(String url) {
        for (String cand : mirrorCandidates(url)) {
            try {
                String t = HttpUtil.get(cand, null, 30000);
                if (t != null && t.length() > 8 && !t.trim().startsWith("<")) return t;
            } catch (Throwable ignored) { }
        }
        return "";
    }

    private static List<String> mirrorCandidates(String url) {
        List<String> out = new ArrayList<String>();
        out.add(url);
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            if (host.endsWith("jsdelivr.net") && path.startsWith("/gh/")) {
                String rest = path.substring(4);
                int i1 = rest.indexOf('/');
                if (i1 > 0) {
                    String owner = rest.substring(0, i1);
                    String rest2 = rest.substring(i1 + 1);
                    int i2 = rest2.indexOf('/');
                    if (i2 > 0) {
                        String repoVer = rest2.substring(0, i2);
                        String file = rest2.substring(i2 + 1);
                        int at = repoVer.indexOf('@');
                        String repo = at > 0 ? repoVer.substring(0, at) : repoVer;
                        String ver = at > 0 ? repoVer.substring(at + 1) : "master";
                        String raw = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + ver + "/" + file;
                        out.add("https://ghproxy.net/" + raw);
                        out.add("https://gh-proxy.com/" + raw);
                        out.add(raw);
                    }
                }
            } else if ("raw.githubusercontent.com".equals(host)) {
                out.add("https://ghproxy.net/" + url);
                out.add("https://gh-proxy.com/" + url);
            } else if ((host.equals("gh-proxy.org") || host.equals("ghproxy.net") || host.equals("gh-proxy.com")
                    || host.equals("ghfast.top") || host.equals("mirror.ghproxy.com") || host.equals("ghproxy.cc"))
                    && path.startsWith("/http")) {
                String inner = path.substring(1);
                out.add(inner);
                out.add("https://ghproxy.net/" + inner);
                out.add("https://gh-proxy.com/" + inner);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ---------------- 脚本变换 ----------------

    static String transformCore(String s) {
        int i = s.lastIndexOf("export{");
        if (i < 0) return s;
        int j = s.indexOf("};", i);
        if (j < 0) return s;
        String body = s.substring(i + 7, j);
        StringBuilder sb = new StringBuilder(s.substring(0, i));
        sb.append("var __DRPY_CORE__={");
        boolean first = true;
        for (String part : body.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] kv = part.split("\\s+as\\s+");
            String local = kv[0].trim();
            String name = kv.length > 1 ? kv[1].trim() : local;
            if (!first) sb.append(",");
            sb.append(jsStr(name)).append(":").append(local);
            first = false;
        }
        sb.append("};");
        return sb.toString();
    }

    static String transformFramework(String s) {
        String out = s;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)import\\s*\\{([^}]*)\\}\\s*from\\s*[\"'][^\"']*[\"'];?")
                .matcher(s);
        if (m.find()) {
            StringBuilder rep = new StringBuilder();
            for (String n : m.group(1).split(",")) {
                n = n.trim();
                if (n.isEmpty()) continue;
                rep.append("var ").append(n).append("=__DRPY_CORE__[").append(jsStr(n)).append("];");
            }
            out = s.substring(0, m.start()) + rep + s.substring(m.end());
        }
        out = out.replace("export default {", "var __DRPY_DEFAULT__ = {");
        // 其余 export{...} 忽略
        out = out.replaceAll("(?s)export\\s*\\{[^}]*\\};?", "");
        return out;
    }

    static String transformRule(String s) {
        String out = s;
        out = out.replaceAll("(?m)^\\s*(var|let|const)\\s+rule\\s*=", "rule =");
        out = out.replace("export default {", "var __RULE__ = {");
        return out;
    }
}
