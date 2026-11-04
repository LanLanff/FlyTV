package android.webkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CookieManager 桩。
 *
 * 必须落盘：jar 爬虫拿到的登录 Cookie（夸克/UC/百度等）重启后还要用，
 * 只存内存会让用户每次开 FlyTV 都要求重新登录。
 * 存储位置：%LOCALAPPDATA%\TVBox for Windows\spider-prefs\cookies.properties
 */
public class CookieManager {

    private static final CookieManager INSTANCE = new CookieManager();
    private static final Map<String, String> COOKIES = new ConcurrentHashMap<String, String>();
    private static boolean loaded = false;

    public static CookieManager getInstance() { return INSTANCE; }

    private static File file() {
        String d = null;
        try { d = System.getProperty("flytv.data"); } catch (Throwable ignored) { }
        if (d == null || d.isEmpty()) {
            String la = System.getenv("LOCALAPPDATA");
            if (la != null && !la.isEmpty()) d = la + File.separator + "TVBox for Windows";
        }
        if (d == null || d.isEmpty()) d = System.getProperty("java.io.tmpdir", ".");
        File dir = new File(d, "spider-prefs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "cookies.properties");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (!f.isFile()) return;
            Properties p = new Properties();
            FileInputStream in = new FileInputStream(f);
            try { p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); } finally { try { in.close(); } catch (Exception ignored) { } }
            for (String k : p.stringPropertyNames()) COOKIES.put(k, p.getProperty(k));
        } catch (Throwable ignored) { }
    }

    private static synchronized void save() {
        try {
            Properties p = new Properties();
            for (Map.Entry<String, String> e : COOKIES.entrySet()) p.setProperty(e.getKey(), e.getValue());
            File f = file();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try { p.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "FlyTV spider cookies"); } finally { try { out.close(); } catch (Exception ignored) { } }
            if (!tmp.renameTo(f)) {
                try {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    public void setCookie(String url, String value) {
        if (url == null || value == null) return;
        ensureLoaded();
        try {
            String host = new URI(url).getHost();
            if (host != null) {
                COOKIES.put(host, value);
                save();
            }
        } catch (Exception ignored) { }
    }

    public String getCookie(String url) {
        if (url == null) return null;
        ensureLoaded();
        try {
            String host = new URI(url).getHost();
            return host == null ? null : COOKIES.get(host);
        } catch (Exception e) { return null; }
    }

    public boolean setAcceptCookie(boolean accept) { return accept; }

    public boolean getAcceptCookie() { return true; }

    public void removeAllCookies(Object callback) {
        ensureLoaded();
        COOKIES.clear();
        save();
    }

    public void removeSessionCookies(Object callback) { }

    public void flush() { save(); }

    /** 供宿主/引擎查询：当前已持久化的域名数量（诊断用） */
    public static int size() {
        ensureLoaded();
        return COOKIES.size();
    }
}
