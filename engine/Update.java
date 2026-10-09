package dev.flytv.engine;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;

/** 软件自更新：检查清单 update.json → 下载 zip → md5 校验 → 解压 → 生成替换脚本 → 退出重启。 */
public final class Update {
    public static final String VERSION = "1.0.46";
    public static final String BUILD = "2026-10-09";
    // 公开版默认从 GitHub 仓库清单更新；自建服务器可用设置项 update_url 覆盖
    private static final String DEFAULT_MANIFEST = "https://raw.githubusercontent.com/LanLanff/FlyTV/main/update.json";

    // idle / checking / downloading / verifying / extracting / restarting / error
    private static volatile String phase = "idle";
    private static volatile int percent = 0;
    private static volatile String message = "";

    private static String manifestUrl() {
        String u = Setting.getString("update_url", "");
        return u == null || u.isEmpty() ? DEFAULT_MANIFEST : u;
    }

    /** 启动时清理上次更新残留的临时目录（>1 小时）。 */
    public static void cleanup() {
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            File[] fs = tmp.listFiles((d, n) -> n.startsWith("FlyTV-update-"));
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                if (now - f.lastModified() < 3600_000L) continue;
                deleteRec(f);
            }
        } catch (Exception ignored) { }
    }

    private static void deleteRec(File f) {
        try {
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) for (File c : cs) deleteRec(c);
            }
            f.delete();
        } catch (Exception ignored) { }
    }

    /** 版本号比较：1.0.10 > 1.0.9（按数字段逐段比较）。 */
    static boolean newer(String remote, String current) {
        if (remote == null || remote.isEmpty()) return false;
        String[] a = remote.split("[^0-9]+");
        String[] b = (current == null ? "" : current).split("[^0-9]+");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = (i < a.length && !a[i].isEmpty()) ? safeInt(a[i]) : 0;
            int y = (i < b.length && !b[i].isEmpty()) ? safeInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    // ---------- API ----------

    /** 检查更新：GET 清单 → 比对版本。 */
    public static String checkJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("build", BUILD);
        try {
            String txt = HttpUtil.get(manifestUrl(), null, 8000);
            JsonObject m = JsonUtil.parseObj(txt);
            JsonObject w = m == null ? null : m.getAsJsonObject("win");
            if (w == null) {
                o.addProperty("error", "更新清单格式不正确");
                return o.toString();
            }
            String rv = JsonUtil.str(w, "version", "");
            o.addProperty("hasNew", newer(rv, VERSION));
            o.addProperty("remoteVersion", rv);
            o.addProperty("notes", JsonUtil.str(w, "notes", ""));
            o.addProperty("url", JsonUtil.str(w, "url", ""));
            o.addProperty("size", JsonUtil.lng(w, "size", 0));
            o.addProperty("md5", JsonUtil.str(w, "md5", ""));
        } catch (Exception e) {
            o.addProperty("error", "无法连接更新服务器");
        }
        return o.toString();
    }

    /** 供前端轮询的进度状态。 */
    public static String statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("build", BUILD);
        o.addProperty("phase", phase);
        o.addProperty("percent", percent);
        o.addProperty("message", message);
        return o.toString();
    }

    /** 开始更新（后台线程下载/解压/替换）。 */
    public static String applyJson() {
        if (!"idle".equals(phase) && !"error".equals(phase)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("busy", true);
            return o.toString();
        }
        phase = "checking";
        percent = 0;
        message = "正在获取更新信息…";
        Thread t = new Thread(Update::run, "flytv-update");
        t.setDaemon(true);
        t.start();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o.toString();
    }

    // ---------- 更新流程 ----------

    private static void run() {
        try {
            String txt = HttpUtil.get(manifestUrl(), null, 8000);
            JsonObject m = JsonUtil.parseObj(txt);
            JsonObject w = m == null ? null : m.getAsJsonObject("win");
            if (w == null) throw new Exception("更新清单格式不正确");
            String url = JsonUtil.str(w, "url", "");
            String md5 = JsonUtil.str(w, "md5", "");
            long size = JsonUtil.lng(w, "size", 0);
            if (url.isEmpty()) throw new Exception("更新地址为空");

            File dir = new File(System.getProperty("java.io.tmpdir"), "FlyTV-update-" + System.currentTimeMillis());
            if (!dir.mkdirs()) throw new Exception("无法创建临时目录");
            File zip = new File(dir, "pkg.zip");

            // 1. 下载（流式，带进度）
            phase = "downloading";
            message = "正在下载新版本…";
            OkHttpClient c = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            try (Response resp = c.newCall(new Request.Builder().url(url).get().build()).execute()) {
                ResponseBody body = resp.body();
                if (body == null) throw new Exception("下载失败：响应为空");
                long total = body.contentLength() > 0 ? body.contentLength() : size;
                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                    }
                }
            }
            if (!zip.exists() || zip.length() == 0) throw new Exception("下载失败：文件为空");

            // 2. md5 校验
            if (!md5.isEmpty()) {
                phase = "verifying";
                message = "正在校验文件完整性…";
                String got = md5(zip);
                if (!got.equalsIgnoreCase(md5)) throw new Exception("校验失败（md5 不一致），请重试");
            }

            // 3. 解压
            phase = "extracting";
            message = "正在解压…";
            File newDir = new File(dir, "new");
            unzip(zip, newDir);
            File root = newDir;
            File[] fs = newDir.listFiles();
            if (fs != null && fs.length == 1 && fs[0].isDirectory()) root = fs[0];

            // 4. 生成替换脚本（ASCII 内容 + 系统默认编码写盘，兼容中文路径）
            File script = new File(dir, "apply-update.cmd");
            long pid = ProcessHandle.current().pid();
            int port = WebServer.port();
            String install = AppPaths.InstallRoot;
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\r\n");
            sb.append("setlocal\r\n");
            sb.append("rem FlyTV auto-update helper (generated)\r\n");
            sb.append(":wait\r\n");
            sb.append("tasklist /FI \"PID eq ").append(pid).append("\" 2>nul | findstr /C:\"").append(pid).append("\" >nul\r\n");
            sb.append("if not errorlevel 1 ( ping -n 2 127.0.0.1 >nul & goto wait )\r\n");
            sb.append("ping -n 3 127.0.0.1 >nul\r\n");
            sb.append("robocopy \"").append(root.getAbsolutePath()).append("\" \"").append(install)
              .append("\" /E /R:5 /W:1 /NFL /NDL /NJH /NJS >nul\r\n");
            sb.append("start \"\" \"").append(install).append("\\jre\\bin\\javaw.exe\" -cp \"").append(install)
              .append("\\libs\\*;").append(install).append("\\flytv-engine.jar\" -Dflytv.port=").append(port)
              .append(" dev.flytv.engine.Main\r\n");
            sb.append("exit /b 0\r\n");
            Files.write(script.toPath(), sb.toString().getBytes(Charset.defaultCharset()));

            // 5. 启动脚本 → 退出（脚本等待本进程结束后替换文件并重启）
            phase = "restarting";
            percent = 100;
            message = "更新就绪，正在重启…";
            Logger.d("Update", "自更新：生成替换脚本，退出重启。源=" + root + " 目标=" + install);
            new ProcessBuilder("cmd", "/c", "start", "", "/min", script.getAbsolutePath()).start();
            Thread.sleep(800);
            System.exit(0);
        } catch (Exception e) {
            phase = "error";
            percent = 0;
            message = String.valueOf(e.getMessage());
            Logger.e("Update", "自更新失败: " + e);
        }
    }

    private static void unzip(File zipFile, File dest) throws Exception {
        // 兼容 Windows tar 打的包：中文文件名按 GBK 存储且无 UTF-8 标记；
        // 带 UTF-8 标记的条目仍按 UTF-8 解码（ZipFile 会按条目标记处理）。
        Charset cs;
        try { cs = Charset.forName("GBK"); } catch (Exception e) { cs = java.nio.charset.StandardCharsets.UTF_8; }
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile, cs);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (name.contains("..")) throw new Exception("非法压缩包路径");
                File f = new File(dest, name);
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                try (InputStream in = zf.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        } finally {
            zf.close();
        }
    }

    private static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
