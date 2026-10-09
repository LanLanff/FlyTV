package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** 站点内容服务（等价 C# SiteService）：home/category/detail/search/player。
 * type3（jar 爬虫）走宿主 /call；type0/1/4 走站点 HTTP API。输出按 Web 前端契约组织。 */
public final class SiteService {

    // ---------- 通用 ----------
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static JsonArray arr() { return new JsonArray(); }

    static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.add("types", arr());
        o.add("list", arr());
        o.addProperty("msg", msg == null ? "未知错误" : msg);
        return o;
    }

    /** 列表项 → 前端契约（id/name/pic/remarks/siteName/site）。 */
    static JsonObject vodOut(JsonObject v, VodConfig.Site site) {
        JsonObject o = new JsonObject();
        o.addProperty("id", JsonUtil.str(v, "vod_id", ""));
        o.addProperty("name", JsonUtil.str(v, "vod_name", ""));
        o.addProperty("pic", JsonUtil.str(v, "vod_pic", ""));
        o.addProperty("remarks", JsonUtil.str(v, "vod_remarks", ""));
        String typeName = JsonUtil.str(v, "type_name", "");
        if (!typeName.isEmpty()) o.addProperty("typeName", typeName);
        if (site != null) {
            o.addProperty("site", site.key);
            o.addProperty("siteName", site.name);
        }
        return o;
    }

    static JsonArray vodsFrom(String text, VodConfig.Site site) {
        JsonArray out = arr();
        JsonObject o = JsonUtil.parseObj(text);
        JsonArray list = o == null ? null : o.getAsJsonArray("list");
        if (list == null) return out;
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            out.add(vodOut(e.getAsJsonObject(), site));
        }
        return out;
    }

    /** vod_play_from / vod_play_url → [{flag, episodes:[{name,url}]}]。 */
    static JsonArray flagsFrom(JsonObject vod) {
        JsonArray flags = arr();
        String from = JsonUtil.str(vod, "vod_play_from", "");
        String url = JsonUtil.str(vod, "vod_play_url", "");
        if (from.isEmpty() || url.isEmpty()) return flags;
        String[] froms = from.split("\\$\\$\\$");
        String[] urls = url.split("\\$\\$\\$");
        for (int i = 0; i < froms.length && i < urls.length; i++) {
            JsonObject f = new JsonObject();
            f.addProperty("flag", froms[i]);
            JsonArray eps = arr();
            for (String ep : urls[i].split("#")) {
                if (ep.isEmpty()) continue;
                int d = ep.indexOf('$');
                JsonObject e = new JsonObject();
                if (d >= 0) {
                    e.addProperty("name", ep.substring(0, d));
                    e.addProperty("url", ep.substring(d + 1));
                } else {
                    e.addProperty("name", ep);
                    e.addProperty("url", ep);
                }
                eps.add(e);
            }
            f.add("episodes", eps);
            flags.add(f);
        }
        return flags;
    }

    // ---------- home ----------
    public static JsonObject home(VodConfig.Site site) {
        try {
            if (site.type == 3) return homeJar(site);
            return homeHttp(site);
        } catch (Exception e) {
            Logger.e("SiteService", "home(" + site.key + ") " + e.getMessage());
            return error(e.getMessage());
        }
    }

    static JsonObject homeJar(VodConfig.Site site) throws Exception {
        String h = Spiders.call(site, "homeContent", map("filter", true), 90000);
        JsonObject ho = JsonUtil.parseObj(h);
        JsonArray types = arr();
        if (ho != null && ho.get("class") != null && ho.get("class").isJsonArray()) {
            for (JsonElement e : ho.getAsJsonArray("class")) {
                if (!e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                JsonObject t = new JsonObject();
                t.addProperty("id", JsonUtil.str(c, "type_id", ""));
                t.addProperty("name", JsonUtil.str(c, "type_name", ""));
                types.add(t);
            }
        }
        JsonArray list = arr();
        try {
            String v = Spiders.call(site, "homeVideoContent", new HashMap<>(), 60000);
            list = vodsFrom(v, site);
        } catch (Exception ignored) { }
        JsonObject out = new JsonObject();
        out.add("types", types);
        out.add("list", list);
        Logger.d("SiteService", "home(" + site.key + ") types=" + types.size() + " list=" + list.size());
        return out;
    }

    /** type0（XML/苹果CMS）/type1/4（JSON）。 */
    static JsonObject homeHttp(VodConfig.Site site) throws Exception {
        String body = httpCall(site, map("filter", "true"));
        JsonArray types = arr();
        JsonArray list = arr();
        if (site.type == 0) {
            XmlResult xr = XmlResult.parse(body);
            types = xr.types;
            list = xr.list;
        } else {
            JsonObject o = JsonUtil.parseObj(body);
            if (o != null) {
                JsonArray cls = o.getAsJsonArray("class");
                if (cls != null) for (JsonElement e : cls) {
                    if (!e.isJsonObject()) continue;
                    JsonObject c = e.getAsJsonObject();
                    JsonObject t = new JsonObject();
                    t.addProperty("id", JsonUtil.str(c, "type_id", ""));
                    t.addProperty("name", JsonUtil.str(c, "type_name", ""));
                    types.add(t);
                }
                list = vodsFrom(body, site);
            }
        }
        JsonObject out = new JsonObject();
        out.add("types", types);
        out.add("list", list);
        return out;
    }

    // ---------- category ----------
    public static JsonObject category(VodConfig.Site site, String tid, String pg) {
        try {
            if (site.type == 3) {
                String text = Spiders.call(site, "categoryContent", map("tid", tid, "pg", pg, "filter", true, "extend", new JsonObject()), 90000);
                JsonArray list = vodsFrom(text, site);
                JsonObject o = JsonUtil.parseObj(text);
                JsonObject out = new JsonObject();
                out.add("list", list);
                out.addProperty("page", o != null ? JsonUtil.integer(o, "page", 1) : 1);
                out.addProperty("pagecount", o != null ? JsonUtil.integer(o, "pagecount", 1) : 1);
                out.addProperty("limit", o != null ? JsonUtil.integer(o, "limit", 20) : 20);
                out.addProperty("total", o != null ? JsonUtil.integer(o, "total", list.size()) : list.size());
                return out;
            }
            // type0/1/4 HTTP
            Map<String, Object> ps = new HashMap<>();
            ps.put("ac", site.type == 0 ? "videolist" : "detail");
            ps.put("t", tid);
            ps.put("pg", pg);
            String body = httpCall(site, ps);
            JsonObject out = new JsonObject();
            if (site.type == 0) {
                XmlResult xr = XmlResult.parse(body);
                out.add("list", xr.list);
                out.addProperty("page", parseInt(xr.page, 1));
                out.addProperty("pagecount", parseInt(xr.pagecount, 1));
                out.addProperty("limit", parseInt(xr.limit, 20));
                out.addProperty("total", parseInt(xr.total, 9999));
            } else {
                JsonArray list = vodsFrom(body, site);
                out.add("list", list);
                out.addProperty("page", 1);
                out.addProperty("pagecount", 1);
                out.addProperty("limit", 20);
                out.addProperty("total", list.size());
            }
            return out;
        } catch (Exception e) {
            Logger.e("SiteService", "category(" + site.key + ") " + e.getMessage());
            JsonObject o = error(e.getMessage());
            o.addProperty("page", 1);
            o.addProperty("pagecount", 1);
            return o;
        }
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------- detail ----------
    public static JsonObject detail(VodConfig.Site site, String id) {
        try {
            JsonObject vod = null;
            if (site.type == 3) {
                String text = Spiders.call(site, "detailContent", map("ids", singleIdArray(id)), 90000);
                JsonObject o = JsonUtil.parseObj(text);
                JsonArray list = o == null ? null : o.getAsJsonArray("list");
                if (list != null && list.size() > 0 && list.get(0).isJsonObject()) vod = list.get(0).getAsJsonObject();
            } else {
                Map<String, Object> ps = new HashMap<>();
                ps.put("ac", site.type == 0 ? "videolist" : "detail");
                ps.put("ids", id);
                String body = httpCall(site, ps);
                if (site.type == 0) {
                    XmlResult xr = XmlResult.parse(body);
                    if (xr.rawVod != null) vod = xr.rawVod;
                } else {
                    JsonObject o = JsonUtil.parseObj(body);
                    JsonArray list = o == null ? null : o.getAsJsonArray("list");
                    if (list != null && list.size() > 0 && list.get(0).isJsonObject()) vod = list.get(0).getAsJsonObject();
                }
            }
            if (vod == null) return error("详情获取失败");
            JsonObject out = new JsonObject();
            String vid = JsonUtil.str(vod, "vod_id", id);
        if (vid.isEmpty()) vid = id;   // 蜘蛛返回空 vod_id（如网盘/目录源）→ 回填请求 id，避免客户端拿不到 id
        out.addProperty("id", vid);
            out.addProperty("name", JsonUtil.str(vod, "vod_name", ""));
            out.addProperty("pic", JsonUtil.str(vod, "vod_pic", ""));
            out.addProperty("remarks", JsonUtil.str(vod, "vod_remarks", ""));
            out.addProperty("year", JsonUtil.str(vod, "vod_year", ""));
            out.addProperty("area", JsonUtil.str(vod, "vod_area", ""));
            out.addProperty("typeName", JsonUtil.str(vod, "type_name", ""));
            out.addProperty("actor", JsonUtil.str(vod, "vod_actor", ""));
            out.addProperty("director", JsonUtil.str(vod, "vod_director", ""));
            out.addProperty("lang", JsonUtil.str(vod, "vod_lang", ""));
            out.addProperty("desc", JsonUtil.str(vod, "vod_content", "").replaceAll("<[^>]+>", ""));
            out.add("flags", vibFlags(vod));
            out.addProperty("site", site.key);
            out.addProperty("siteName", site.name);
            return out;
        } catch (Exception e) {
            Logger.e("SiteService", "detail(" + site.key + ") " + e.getMessage());
            return error(e.getMessage());
        }
    }

    static JsonArray singleIdArray(String id) {
        JsonArray a = new JsonArray();
        a.add(id);
        return a;
    }

    static JsonArray vibFlags(JsonObject vod) { return flagsFrom(vod); }

    // ---------- search ----------
    public static JsonObject search(VodConfig.Site site, String wd, String pg) {
        try {
            if (!site.searchable) {
                JsonObject o = new JsonObject();
                o.add("list", arr());
                return o;
            }
            JsonArray list;
            if (site.type == 3) {
                Map<String, Object> ps = new HashMap<>();
                ps.put("key", wd);
                ps.put("quick", false);
                if (pg != null && !pg.equals("1")) ps.put("pg", pg);
                String text = Spiders.call(site, "searchContent", ps, 90000);
                list = vodsFrom(text, site);
            } else {
                Map<String, Object> ps = new HashMap<>();
                ps.put("wd", wd);
                ps.put("quick", "false");
                if (pg != null && !pg.equals("1")) ps.put("pg", pg);
                String body = httpCall(site, ps);
                list = site.type == 0 ? XmlResult.parse(body).list : vodsFrom(body, site);
            }
            JsonObject out = new JsonObject();
            out.add("list", list);
            return out;
        } catch (Exception e) {
            Logger.e("SiteService", "search(" + site.key + ") " + e.getMessage());
            return error(e.getMessage());
        }
    }

    // ---------- player ----------
    /** 返回 {url, headers:{}, format, parse}（网盘 URL 由 PlayService 后续转中继）。 */
    public static JsonObject player(VodConfig.Site site, String flag, String epUrl) {
        try {
            if (site.type == 3) {
                JsonArray vipFlags = new JsonArray();
                for (String f : VodConfig.flags()) vipFlags.add(f);
                Map<String, Object> ps = new HashMap<>();
                ps.put("flag", flag);
                ps.put("id", epUrl);
                ps.put("vipFlags", vipFlags);
                String text = Spiders.call(site, "playerContent", ps, 120000);
                JsonObject o = JsonUtil.parseObj(text);
                if (o == null) throw new Exception("播放解析失败");
                // 网盘 URL 补全（等价 C# JarSpider playerContent 处理）：
                // spider 返回的代理 URL 可能缺 fileId/shareId 参数 → 从集地址后缀
                // "__fileId+shareId+fileToken" 提取并重建为完整 /proxy?do=pan URL。
                String url = JsonUtil.str(o, "url", "");
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("__([a-f0-9]{8,32})\\+([a-z0-9]{8,32})\\+([a-z0-9]{8,64})")
                            .matcher(epUrl == null ? "" : epUrl);
                    boolean found = m.find();
                    boolean isPan = url.contains("do=pan") || (url.contains("127.0.0.1") && found);
                    if (isPan && found) {
                        String savedFid = "";
                        try { savedFid = JarHost.get("/savedfid?siteKey=" + enc(site.key), 8000).trim(); } catch (Exception ignored) { }
                        boolean broken = url.contains(":-1/") || !url.contains("siteKey=");
                        String epName = "";
                        int us = (epUrl == null ? "" : epUrl).indexOf("__");
                        if (us > 0) epName = epUrl.substring(0, us);
                        String proxyUrl = broken
                                ? "http://127.0.0.1:" + WebServer.port() + "/proxy?do=pan&site=quark"
                                  + "&shareId=" + m.group(2) + "&fileId=" + m.group(1) + "&fileToken=" + m.group(3)
                                  + "&siteKey=" + enc(site.key)
                                  + (epName.isEmpty() ? "" : "&epName=" + enc(epName))
                                : url;
                        if (!savedFid.isEmpty() && !proxyUrl.contains("savedFid=")) proxyUrl += "&savedFid=" + enc(savedFid);
                        o.addProperty("url", proxyUrl);
                        Logger.d("SiteService", "网盘URL重建[broken=" + broken + "]: " + proxyUrl.substring(0, Math.min(140, proxyUrl.length())));
                    }
                } catch (Exception ex) {
                    Logger.e("SiteService", "网盘URL重建失败: " + ex.getMessage());
                }
                JsonObject out = new JsonObject();
                out.addProperty("url", JsonUtil.str(o, "url", ""));
                out.addProperty("format", JsonUtil.str(o, "format", ""));
                out.addProperty("parse", JsonUtil.integer(o, "parse", 0));
                JsonElement h = o.get("header");
                out.add("headers", h != null && h.isJsonObject() ? h.getAsJsonObject() : new JsonObject());
                // 透传蜘蛛的提示语（如"还未登录百度账号,请前往【配置中心】登录"），别再让用户蒙在鼓里
                String pmsg = JsonUtil.str(o, "msg", "");
                if (pmsg.isEmpty()) pmsg = JsonUtil.str(o, "errMsg", "");
                if (pmsg.isEmpty()) pmsg = JsonUtil.str(o, "message", "");
                if (!pmsg.isEmpty()) out.addProperty("msg", pmsg);
                return out;
            }
            // type0/1：id 即直链/待解析 URL
            JsonObject out = new JsonObject();
            out.addProperty("url", epUrl);
            out.addProperty("format", "");
            out.addProperty("parse", snifferVideo(epUrl) ? 0 : 1);
            out.add("headers", new JsonObject());
            return out;
        } catch (Exception e) {
            Logger.e("SiteService", "player(" + site.key + ") " + e.getMessage());
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o;
        }
    }

    static boolean snifferVideo(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv") || u.contains(".mkv") || u.contains(".ts");
    }

    /** type0/1/4 通用 HTTP 调用（ext 作为 extend 参数；≤1000 GET，否则 POST）。 */
    static String httpCall(VodConfig.Site site, Map<String, Object> ps) throws Exception {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Object> e : ps.entrySet()) {
            if (e.getValue() == null) continue;
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
        }
        if (site.ext != null && !site.ext.isEmpty()) {
            if (q.length() > 0) q.append('&');
            q.append("extend=").append(URLEncoder.encode(site.ext, "UTF-8"));
        }
        String url = site.api + (q.length() > 0 ? (site.api.contains("?") ? "&" : "?") + q : "");
        return HttpUtil.get(url, null, 25000);
    }
}
