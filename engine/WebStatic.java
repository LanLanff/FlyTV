package dev.flytv.engine;

import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** /web 静态资源（从安装目录 web/ 提供，与 C# 版一致）。 */
public final class WebStatic {
    private static final Map<String, String> MIMES = new HashMap<>();

    static {
        MIMES.put("html", "text/html; charset=utf-8");
        MIMES.put("js", "application/javascript; charset=utf-8");
        MIMES.put("mjs", "application/javascript; charset=utf-8");
        MIMES.put("css", "text/css; charset=utf-8");
        MIMES.put("json", "application/json; charset=utf-8");
        MIMES.put("png", "image/png");
        MIMES.put("jpg", "image/jpeg");
        MIMES.put("jpeg", "image/jpeg");
        MIMES.put("gif", "image/gif");
        MIMES.put("webp", "image/webp");
        MIMES.put("svg", "image/svg+xml");
        MIMES.put("ico", "image/x-icon");
        MIMES.put("woff2", "font/woff2");
        MIMES.put("woff", "font/woff");
        MIMES.put("map", "application/json");
        MIMES.put("apk", "application/vnd.android.package-archive");
        MIMES.put("zip", "application/zip");
        MIMES.put("txt", "text/plain; charset=utf-8");
    }

    public static void serve(HttpExchange ex, String path) throws Exception {
        String rel = path.length() <= 4 ? "index.html" : path.substring(5);
        if (rel.isEmpty() || rel.equals("/")) rel = "index.html";
        rel = rel.replace('\\', '/').replace("/", File.separator);
        if (rel.contains("..")) { WebServer.text(ex, 400, "invalid path"); return; }
        File root = new File(AppPaths.webDir());
        File file = new File(root, rel);
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(root.getCanonicalPath())) { WebServer.text(ex, 400, "invalid path"); return; }
        if (!file.exists() || file.isDirectory()) { WebServer.text(ex, 404, "not found: " + rel); return; }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        ex.getResponseHeaders().set("Content-Type", MIMES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("Expires", "0");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
