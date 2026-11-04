package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 源诊断 + 外部播放器。
 *
 * 诊断：按「爬虫加载 → 首页 → 分类 → 搜索」四步跑一遍，把每一步的耗时和错误原样返回，
 * 让用户在界面上直接看到"这个源为什么不能用"，而不是只留一行日志。
 */
public final class Diag {

    // ---------------- 源诊断 ----------------

    public static JsonObject diagnose(String key) {
        JsonObject out = new JsonObject();
        VodConfig.Site site = null;
        for (VodConfig.Site s : VodConfig.sites()) {
            if (key != null && key.equals(s.key)) { site = s; break; }
        }
        if (site == null) {
            out.addProperty("error", "站点不存在（可能已切换配置，请刷新）");
            return out;
        }
        out.addProperty("site", site.key);
        out.addProperty("name", site.name);
        out.addProperty("type", site.type);
        out.addProperty("typeName", typeName(site.type));

        JsonArray steps = new JsonArray();
        boolean ok = true;

        // 1) 爬虫加载
        if (site.type == 3) {
            long t0 = System.currentTimeMillis();
            try {
                Spiders.ensureLoaded(site);
                steps.add(step("爬虫加载（jar）", true, ms(t0), "jar 已加载"));
            } catch (Exception e) {
                steps.add(step("爬虫加载（jar）", false, ms(t0), brief(e)));
                ok = false;
            }
        } else {
            steps.add(step("爬虫加载（jar）", true, 0, "HTTP 源，不需要 jar"));
        }

        // 2) 首页
        JsonArray types = new JsonArray();
        if (ok) {
            long t0 = System.currentTimeMillis();
            JsonObject h = SiteService.home(site);
            if (h.has("error")) {
                steps.add(step("首页", false, ms(t0), brief(h.get("error").getAsString())));
                ok = false;
            } else {
                if (h.has("types") && h.get("types").isJsonArray()) types = h.getAsJsonArray("types");
                int n = 0;
                if (h.has("list") && h.get("list").isJsonArray()) n = h.getAsJsonArray("list").size();
                steps.add(step("首页", true, ms(t0), "分类 " + types.size() + " 个 · 推荐 " + n + " 条"));
            }
        }

        // 3) 分类第一页
        if (ok && types.size() > 0) {
            String tid = "";
            try {
                JsonElement e0 = types.get(0);
                if (e0 != null && e0.isJsonObject()) tid = JsonUtil.str(e0.getAsJsonObject(), "id", "");
            } catch (Exception ignored) { }
            if (!tid.isEmpty()) {
                long t0 = System.currentTimeMillis();
                JsonObject c = SiteService.category(site, tid, "1");
                if (c.has("error")) {
                    steps.add(step("分类列表", false, ms(t0), brief(c.get("error").getAsString())));
                    ok = false;
                } else {
                    int n = 0;
                    if (c.has("list") && c.get("list").isJsonArray()) n = c.getAsJsonArray("list").size();
                    steps.add(step("分类列表", true, ms(t0), "第一页 " + n + " 条"));
                }
            }
        }

        // 4) 搜索
        if (ok) {
            long t0 = System.currentTimeMillis();
            JsonObject s = SiteService.search(site, "庆余年", "1");
            if (s.has("error")) {
                steps.add(step("搜索", false, ms(t0), brief(s.get("error").getAsString())));
                ok = false;
            } else {
                int n = 0;
                if (s.has("list") && s.get("list").isJsonArray()) n = s.getAsJsonArray("list").size();
                steps.add(step("搜索", true, ms(t0), "关键词「庆余年」命中 " + n + " 条"));
            }
        }

        out.add("steps", steps);
        out.addProperty("ok", ok);
        if (!ok) out.addProperty("hint", hint(site));
        return out;
    }

    private static String typeName(int t) {
        switch (t) {
            case 0: return "XML/苹果CMS";
            case 1: return "JSON 接口";
            case 3: return "JAR 爬虫";
            case 4: return "JSON 接口（type4）";
            default: return "type=" + t;
        }
    }

    private static String hint(VodConfig.Site site) {
        if (site.type != 3) return "HTTP 源排查：地址是否能直接打开、是否需要 ext/登录、站点是否已失效。";
        return "JAR 排查：①源站失效 ②jar 需要登录/Cookie ③jar 依赖了桌面端尚未实现的 Android API（可把 engine.log 里 Spiders/JarHost 的报错发到 issue）。";
    }

    private static JsonObject step(String name, boolean ok, long ms, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("ok", ok);
        o.addProperty("ms", ms);
        o.addProperty("detail", detail == null ? "" : detail);
        return o;
    }

    private static long ms(long t0) { return Math.max(0, System.currentTimeMillis() - t0); }

    private static String brief(Exception e) { return brief(e == null ? "" : e.getMessage()); }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // ---------------- 外部播放器 ----------------

    public static JsonObject openExternal(String url, String pos) {
        JsonObject o = new JsonObject();
        String u = url == null ? "" : url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            o.addProperty("error", "不支持的播放地址（仅 http/https）");
            return o;
        }
        int sec = 0;
        try { sec = (int) Double.parseDouble(pos == null ? "0" : pos.trim()); } catch (Exception ignored) { }
        if (sec < 0) sec = 0;

        File exe = findPlayer();
        if (exe == null) {
            o.addProperty("error", "未检测到外部播放器：安装 mpv / VLC / PotPlayer 任一即可；也可把播放器 exe 路径写到 " + customPathFile().getAbsolutePath());
            return o;
        }
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(exe.getAbsolutePath());
            String name = exe.getName().toLowerCase();
            if (name.startsWith("mpv")) {
                cmd.add("--force-window=yes");
                if (sec > 0) cmd.add("--start=" + sec);
                cmd.add(u);
            } else if (name.startsWith("vlc")) {
                if (sec > 0) cmd.add("--start-time=" + sec);
                cmd.add(u);
            } else { // PotPlayer
                cmd.add(u);
                if (sec > 0) cmd.add("/seek=" + sec);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            o.addProperty("ok", true);
            o.addProperty("player", exe.getName());
            o.addProperty("path", exe.getAbsolutePath());
            Logger.d("Diag", "外部播放器启动: " + exe.getName() + " " + u);
        } catch (Exception e) {
            o.addProperty("error", "启动失败：" + e.getMessage());
        }
        return o;
    }

    private static File customPathFile() { return new File(AppPaths.Local, "player-path.txt"); }

    private static File findPlayer() {
        // 1) 用户自定义路径优先
        try {
            File f = customPathFile();
            if (f.isFile()) {
                String line = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    File p = new File(line);
                    if (p.isFile()) return p;
                }
            }
        } catch (Exception ignored) { }

        List<File> cands = new ArrayList<File>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String lad = System.getenv("LOCALAPPDATA");
        // mpv
        for (String base : new String[]{pf, pf86, lad}) {
            if (base == null || base.isEmpty()) continue;
            cands.add(new File(base, "mpv" + File.separator + "mpv.exe"));
            if (lad != null && base.equals(lad)) cands.add(new File(base, "Programs" + File.separator + "mpv" + File.separator + "mpv.exe"));
        }
        // VLC
        for (String base : new String[]{pf, pf86}) {
            if (base == null || base.isEmpty()) continue;
            cands.add(new File(base, "VideoLAN" + File.separator + "VLC" + File.separator + "vlc.exe"));
        }
        // PotPlayer
        for (String base : new String[]{pf, pf86}) {
            if (base == null || base.isEmpty()) continue;
            cands.add(new File(base, "DAUM" + File.separator + "PotPlayer" + File.separator + "PotPlayerMini64.exe"));
            cands.add(new File(base, "DAUM" + File.separator + "PotPlayer" + File.separator + "PotPlayerMini.exe"));
        }
        if (lad != null && !lad.isEmpty()) {
            cands.add(new File(lad, "Programs" + File.separator + "DAUM" + File.separator + "PotPlayer" + File.separator + "PotPlayerMini64.exe"));
        }
        for (File f : cands) {
            try { if (f.isFile()) return f; } catch (Exception ignored) { }
        }
        // PATH 查找
        for (String n : new String[]{"mpv", "vlc", "PotPlayerMini64"}) {
            File p = which(n);
            if (p != null) return p;
        }
        return null;
    }

    private static File which(String name) {
        try {
            ProcessBuilder pb = new ProcessBuilder("where", name);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            proc.waitFor();
            if (line != null) {
                File f = new File(line.trim());
                if (f.isFile()) return f;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
