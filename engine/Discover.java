package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 发现页数据（豆瓣「选电影/选剧集」接口）。
 * 只做代理 + 10 分钟内存缓存，避免频繁打豆瓣。
 */
public final class Discover {

    private static final Map<String, String> CACHE = new HashMap<String, String>();
    private static final Map<String, Long> CACHE_AT = new HashMap<String, Long>();
    private static final long CACHE_MS = 10 * 60 * 1000L;

    public static JsonObject list(String type, String tag, String sort, String startS, String limitS) {
        if (type == null || type.isEmpty()) type = "movie";
        if (!type.equals("tv")) type = "movie";
        if (tag == null || tag.isEmpty()) tag = "热门";
        if (sort == null || sort.isEmpty()) sort = "recommend";
        int start = 0, limit = 24;
        try { start = Integer.parseInt(startS == null ? "" : startS.trim()); } catch (Exception ignored) { }
        try { limit = Integer.parseInt(limitS == null ? "" : limitS.trim()); } catch (Exception ignored) { }
        if (limit <= 0 || limit > 60) limit = 24;
        if (start < 0) start = 0;

        String key = type + "|" + tag + "|" + sort + "|" + start + "|" + limit;
        synchronized (CACHE) {
            Long at = CACHE_AT.get(key);
            if (at != null && System.currentTimeMillis() - at < CACHE_MS) {
                JsonObject hit = JsonUtil.parseObj(CACHE.get(key));
                if (hit != null) return hit;
            }
        }

        // 前端只有一套标签：这里按类型对应到豆瓣识别的标签
        String qTag = tag;
        String qSort = sort;
        if ("tv".equals(type)) {
            if ("国产".equals(tag)) qTag = "国产剧";
            else if ("欧美".equals(tag)) qTag = "美剧";
            else if ("韩国".equals(tag)) qTag = "韩剧";
            else if ("日本".equals(tag)) qTag = "日剧";
            else if ("豆瓣高分".equals(tag)) { qTag = "热门"; qSort = "rank"; }   // 剧集没有高分标签，按评分排序
            else if ("最新".equals(tag)) { qTag = "热门"; qSort = "time"; }       // 剧集没有最新标签，按时间排序
        } else {
            if ("国产".equals(tag)) qTag = "华语";
        }

        String url = "https://movie.douban.com/j/search_subjects?type=" + enc(type)
                + "&tag=" + enc(qTag) + "&sort=" + enc(qSort)
                + "&page_limit=" + limit + "&page_start=" + start;
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Referer", "https://movie.douban.com/");
            headers.put("Accept", "application/json, text/plain, */*");
            String text = HttpUtil.get(url, headers, 12000);

            JsonObject src = JsonUtil.parseObj(text);
            JsonArray out = new JsonArray();
            if (src != null && src.has("subjects") && src.get("subjects").isJsonArray()) {
                for (JsonElement e : src.getAsJsonArray("subjects")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String title = JsonUtil.str(o, "title", "");
                    if (title.isEmpty()) continue;
                    JsonObject v = new JsonObject();
                    v.addProperty("title", title);
                    v.addProperty("rate", JsonUtil.str(o, "rate", ""));
                    v.addProperty("pic", JsonUtil.str(o, "cover", ""));
                    v.addProperty("url", JsonUtil.str(o, "url", ""));
                    v.addProperty("id", JsonUtil.str(o, "id", ""));
                    out.add(v);
                }
            }
            JsonObject res = new JsonObject();
            res.add("list", out);
            res.addProperty("type", type);
            res.addProperty("tag", tag);
            res.addProperty("sort", sort);
            String js = res.toString();
            synchronized (CACHE) {
                CACHE.put(key, js);
                CACHE_AT.put(key, System.currentTimeMillis());
                if (CACHE.size() > 200) {   // 简单防涨
                    CACHE.clear(); CACHE_AT.clear();
                    CACHE.put(key, js); CACHE_AT.put(key, System.currentTimeMillis());
                }
            }
            return res;
        } catch (Exception e) {
            Logger.e("Discover", "豆瓣接口失败(" + tag + "): " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("error", "豆瓣接口暂时不可用：" + e.getMessage());
            return err;
        }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
