package host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * jar-host: TVBox JAR spider bridge for desktop.
 * Flow: /load download jar -> dex2jar convert (subprocess) -> child-first ClassLoader
 * -> find Spider subclass -> init -> /call dispatch -> standard TVBox JSON.
 */
public class Host {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("JAR_HOST_PORT", "9790"));
    private static final Path DATA_DIR = Paths.get(System.getenv().getOrDefault("JAR_HOST_DATA", "data"));
    private static final String DEX_CLASSPATH = System.getenv().getOrDefault("JAR_HOST_DEXCP", "");

    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, Path> CONVERTED = new ConcurrentHashMap<>();
    static volatile String proxyBase = "";
    static volatile String configBase = "";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        ensureDefaultConfig();
        try {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            log("BouncyCastle registered");
        } catch (Throwable t) { log("BouncyCastle register failed: " + t); }
        proxyBase = System.getenv().getOrDefault("JAR_HOST_PROXY_BASE", "http://127.0.0.1:9978/proxy?");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.createContext("/config", ex -> async(ex, e -> reply(e, 200, "text/plain", "ok")));
        server.createContext("/load", ex -> async(ex, Host::load));
        server.createContext("/call", ex -> async(ex, Host::call));
        server.createContext("/destroy", ex -> async(ex, Host::destroy));
        server.createContext("/clear", ex -> async(ex, Host::clear));
        server.createContext("/proxy", ex -> async(ex, Host::proxy));
        server.createContext("/savedfid", ex -> async(ex, Host::savedFid));
        server.createContext("/transfer", ex -> async(ex, Host::transfer));
        server.createContext("/jarpost", ex -> async(ex, Host::jarPost));
        server.createContext("/jarstream", ex -> async(ex, Host::jarStream));
        server.createContext("/jarstats", ex -> async(ex, Host::jarStats));
        server.start();
        log("jar-host started on " + PORT);
        // 看门狗：父进程（TVBox.exe）退出后 stdin 管道 EOF → 本进程自杀，避免孤儿宿主堆积
        Thread stdinWatch = new Thread(() -> {
            try {
                while (System.in.read() != -1) { }
            } catch (Throwable ignored) { }
            System.exit(0);
        }, "parent-watchdog");
        stdinWatch.setDaemon(true);
        stdinWatch.start();
        Thread.currentThread().join();
    }

    interface Handler { void handle(HttpExchange ex) throws Throwable; }

    // ---- 传输速度统计（jarstream 实际下行速度，供前端"下载速度"显示） ----
    static final java.util.concurrent.atomic.AtomicLong xferBytes = new java.util.concurrent.atomic.AtomicLong();
    static volatile long xferTick = System.currentTimeMillis();
    static volatile long xferLast = 0;
    static volatile long xferRate = 0;

    /** jarstream 每写一块数据调用一次。 */
    static void addXfer(int n) { if (n > 0) xferBytes.addAndGet(n); }

    /** GET /jarstats → {"bps": 12345, "total": 67890}（每 ≥1 秒重算一次速率）。 */
    static void jarStats(HttpExchange ex) throws Throwable {
        long now = System.currentTimeMillis();
        long total = xferBytes.get();
        long dt = now - xferTick;
        if (dt >= 1000) {
            long db = total - xferLast;
            if (db < 0) db = 0;
            xferRate = (long) (db * 1000.0 / dt);
            xferTick = now;
            xferLast = total;
        }
        String json = "{\"bps\":" + xferRate + ",\"total\":" + total + "}";
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** 截断输出堆栈（深递归的 StackOverflowError 会打印几万行，撑爆日志）。 */
    static void printShortTrace(Throwable e, int cap) {
        try {
            StackTraceElement[] st = e.getStackTrace();
            int n = Math.min(cap, st.length);
            for (int i = 0; i < n; i++) System.out.println("\tat " + st[i]);
            if (st.length > cap) System.out.println("\t... （省略 " + (st.length - cap) + " 行堆栈）");
            Throwable c = e.getCause();
            if (c != null) {
                System.out.println("Caused by: " + c);
                StackTraceElement[] cs = c.getStackTrace();
                int m = Math.min(cap, cs.length);
                for (int i = 0; i < m; i++) System.out.println("\tat " + cs[i]);
                if (cs.length > cap) System.out.println("\t... （省略 " + (cs.length - cap) + " 行堆栈）");
            }
        } catch (Throwable ignored) { }
    }

    static boolean isStackOverflow(Throwable t) {
        Throwable x = t;
        for (int i = 0; x != null && i < 8; i++, x = x.getCause()) {
            if (x instanceof StackOverflowError) return true;
        }
        return false;
    }

    static void async(HttpExchange ex, Handler h) {
        Executors.newSingleThreadExecutor().submit(() -> {
            try { h.handle(ex); }
            catch (Throwable e) {
                String msg = e.getMessage();
                String text = (msg == null || msg.isEmpty()) ? e.toString() : (e.getClass().getSimpleName() + ": " + msg);
                try { sendError(ex, 500, text); } catch (Throwable ignored) { }
                System.out.println("[jar-host][error] " + text);
                printShortTrace(e, 40);
            }
            finally { try { ex.close(); } catch (Throwable ignored) { } }
        });
    }

    // ---------- /load ----------

    static void load(HttpExchange ex) throws Exception {
        JSONObject body = readBody(ex);
        String siteKey = body.optString("siteKey", "");
        String jar = body.optString("jar", "");
        if (jar.contains(";")) jar = jar.split(";")[0].trim();
        String api = body.optString("api", "");
        String ext = body.optString("ext", "");
        String base = body.optString("proxyBase", "");
        configBase = body.optString("configBase", "");
        if (siteKey.isEmpty()) throw new IllegalStateException("siteKey is empty");
        if (!base.isEmpty()) proxyBase = base;

        Session existed = SESSIONS.get(siteKey);
        if (existed != null) {
            com.github.catvod.Server.setApi(proxyBase);
            try {
                Method init = com.github.catvod.crawler.Spider.class.getMethod("init", android.content.Context.class, String.class);
                invokeWithTimeout(existed.spider, init, new Object[]{ new HostContext(), ext }, 60000, "spider.init-reused");
            } catch (Throwable t) { log("reused init error: " + t); }
            replyJson(ex, new JSONObject().put("ok", true).put("reused", true));
            return;
        }
        if (jar.isEmpty()) throw new IllegalStateException("site has no jar (csp_ spider needs jar url)");

        long tLoad = System.currentTimeMillis();
        String md5 = md5(jar);
        log("load 计时: md5=" + (System.currentTimeMillis() - tLoad) + "ms");
        Path converted = CONVERTED.get(jar);
        if (converted == null) {
            long t1 = System.currentTimeMillis();
            Path raw = resolveRawJar(jar, md5);
            log("load 计时: 下载=" + (System.currentTimeMillis() - t1) + "ms file=" + raw);
            long t2 = System.currentTimeMillis();
            converted = ensureConverted(raw, md5);
            log("load 计时: 转换=" + (System.currentTimeMillis() - t2) + "ms");
            CONVERTED.put(jar, converted);
        }
        long t3 = System.currentTimeMillis();
        Session session = loadSpider(siteKey, api, converted);
        log("load 计时: 类加载/实例化=" + (System.currentTimeMillis() - t3) + "ms class=" + session.className);
        com.github.catvod.Server.setApi(proxyBase);
        syncPanTokenFiles();
        injectProxyPort(session.loader);
        injectPanToken(session.loader);
        long t4 = System.currentTimeMillis();
        initSpiderWithRetry(session, ext);
        log("load 计时: spiderInit=" + (System.currentTimeMillis() - t4) + "ms");
        SESSIONS.put(siteKey, session);
        replyJson(ex, new JSONObject().put("ok", true).put("class", session.className));
        log("loaded " + siteKey + " -> " + session.className + " (总耗时 " + (System.currentTimeMillis() - tLoad) + "ms)");
    }

    /** init with Wogg ext auto-fix: pre-fix ext for Wogg (skip failing first attempt, halve init time). */
    static void initSpiderWithRetry(Session session, String ext) throws Exception {
        String effectiveExt = ext;
        if (session.className.endsWith(".Wogg")) {
            try {
                JSONObject e = ext == null || ext.trim().isEmpty() ? new JSONObject() : new JSONObject(ext);
                if (!e.has("site")) {
                    e.remove("Cloud-drive");
                    e.put("site", new JSONArray(WOGG_DEFAULT_SITES));
                    effectiveExt = e.toString();
                    log("Wogg ext pre-fixed (site list)");
                }
            } catch (Throwable ignored) { }
        }
        try {
            Method init = com.github.catvod.crawler.Spider.class.getMethod("init", android.content.Context.class, String.class);
            invokeWithTimeout(session.spider, init, new Object[]{ new HostContext(), effectiveExt }, 45000, "spider.init");
        } catch (Throwable first) {
            if (isStackOverflow(first)) {
                // 偶发的类初始化递归（StackOverflowError）：等 300ms 重试一次，多数情况可自愈
                log("init StackOverflow（首次），300ms 后重试一次");
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                Method init2 = com.github.catvod.crawler.Spider.class.getMethod("init", android.content.Context.class, String.class);
                invokeWithTimeout(session.spider, init2, new Object[]{ new HostContext(), effectiveExt }, 45000, "spider.init-retry");
                return;
            }
            if (!session.className.endsWith(".Wogg")) throw first;
            throw first;
        }
    }

    static final String[] WOGG_DEFAULT_SITES = {
        "https://www.wogg.live", "https://woggpan.888484.xyz", "https://wogg.xxooo.cf", "https://woggpan.xxooo.cf"
    };

    /** resolve bare relative path (tvfan/xxx.txt) against config base (URI.resolve). */
    static String resolveAgainstConfigBase(String value) {
        if (value == null || value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")) return value;
        if (configBase == null || configBase.isEmpty()) return value;
        try {
            java.net.URI base = java.net.URI.create(configBase);
            return base.resolve(value).toString();
        } catch (Throwable t) { return value; }
    }

    static Path resolveRawJar(String jar, String md5) throws Exception {
        Path target = DATA_DIR.resolve("jar-" + md5 + ".jar");
        if (Files.exists(target) && Files.size(target) > 0) return target;
        if (jar.startsWith("file://")) jar = jar.substring("file://".length());
        if (jar.startsWith("http://") || jar.startsWith("https://")) {
            log("downloading jar: " + jar);
            byte[] bytes = httpGet(jar);
            Files.write(target, bytes);
        } else {
            Path local = Paths.get(jar);
            if (!Files.exists(local)) throw new IllegalStateException("jar not found: " + jar);
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    static Path ensureConverted(Path raw, String md5) throws Exception {
        Path out = DATA_DIR.resolve("conv-" + md5 + ".jar");
        if (Files.exists(out) && Files.size(out) > 0) return out;
        List<Path> dexFiles = new ArrayList<>();
        boolean hasRawClass = false;
        try (JarFile jarFile = new JarFile(raw.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".dex")) {
                    Path dex = DATA_DIR.resolve("tmp-" + UUID.randomUUID() + ".dex");
                    try (InputStream in = jarFile.getInputStream(entry)) { Files.copy(in, dex, StandardCopyOption.REPLACE_EXISTING); }
                    dexFiles.add(dex);
                } else if (name.endsWith(".class")) hasRawClass = true;
            }
        }
        Path work = Files.createTempDirectory("d2j");
        List<Path> convertedParts = new ArrayList<>();
        int index = 0;
        for (Path dex : dexFiles) {
            Path partOut = work.resolve("part-" + (index++) + ".jar");
            convertDex(dex, partOut);
            convertedParts.add(partOut);
        }
        if (convertedParts.isEmpty()) {
            if (hasRawClass) {
                Files.copy(raw, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
            throw new IllegalStateException("no dex or class in jar (packed shell, unsupported)");
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            for (Path part : convertedParts) {
                try (JarFile partFile = new JarFile(part.toFile())) {
                    Enumeration<JarEntry> entries = partFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        zos.putNextEntry(new ZipEntry(entry.getName()));
                        try (InputStream in = partFile.getInputStream(entry)) { copy(in, zos); }
                        zos.closeEntry();
                    }
                }
            }
        }
        for (Path dex : dexFiles) Files.deleteIfExists(dex);
        try { fixSelfInvokes(out); } catch (Exception ex) { log("fixSelfInvokes: " + ex); }
        log("converted jar -> " + out.getFileName());
        return out;
    }

    static void convertDex(Path dexIn, Path jarOut) throws Exception {
        String cp = DEX_CLASSPATH;
        if (cp.isEmpty()) {
            Path host = Paths.get(Host.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            StringBuilder sb = new StringBuilder();
            java.util.stream.Stream<Path> walk = Files.walk(host);
            try {
                walk.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                    if (sb.length() > 0) sb.append(File.pathSeparatorChar);
                    sb.append(p);
                });
            } finally { walk.close(); }
            cp = sb.toString();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.add("-Xmx512m");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("com.googlecode.dex2jar.tools.Dex2jarCmd");
        cmd.add(dexIn.toAbsolutePath().toString());
        cmd.add("-f");
        cmd.add("-o");
        cmd.add(jarOut.toAbsolutePath().toString());
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder outStr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) { outStr.append(line).append('\n'); if (outStr.length() > 8000) break; }
        }
        int code = proc.waitFor();
        if (code != 0 || !Files.exists(jarOut))
            throw new IllegalStateException("dex2jar failed(exit " + code + "): " + tail(outStr.toString(), 400));
    }

    static Path resolveFallbackJar() throws Exception {
        String env = System.getenv("JAR_HOST_FALLBACK");
        Path p = env != null && !env.isEmpty() ? Paths.get(env) : null;
        if (p == null) {
            try {
                Path host = Paths.get(Host.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                p = host.resolve("fallback-spider.jar");
            } catch (Throwable t) { return null; }
        }
        if (!Files.exists(p)) return null;
        return ensureConverted(p, md5("fallback-spider"));
    }

    static URLClassLoader fallbackLoader;
    static synchronized URLClassLoader fallbackLoader(Path jar) throws Exception {
        if (fallbackLoader == null) fallbackLoader = newLoader(jar);
        return fallbackLoader;
    }

    static URLClassLoader newLoader(Path classJar) throws Exception {
        return new URLClassLoader(new URL[]{ classJar.toUri().toURL() }, Host.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        if (name.startsWith("com.github.catvod.crawler.") || name.startsWith("host.") || name.startsWith("java.")) {
                            return super.loadClass(name, resolve);
                        }
                        try { c = findClass(name); }
                        catch (ClassNotFoundException e) { c = super.loadClass(name, resolve); }
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
    }

    static Session loadSpider(String siteKey, String api, Path classJar) throws Exception {
        String suffix = api.startsWith("csp_") ? api.substring(4) : api;
        suffix = suffix.split("\\?")[0];

        if (suffix.endsWith("Guard") || suffix.endsWith("guard")) {
            String plain = suffix.substring(0, suffix.length() - 5);
            Path fallback = resolveFallbackJar();
            if (fallback != null) {
                try {
                    Session s = scanAndCreate(siteKey, fallback, plain, true, fallbackLoader(fallback));
                    if (s != null) {
                        log("Guard bypass: " + suffix + " -> " + s.className + " (plain jar)");
                        return s;
                    }
                } catch (Throwable t) { log("Guard bypass failed(" + suffix + "): " + t); }
            }
            try {
                Session s = scanAndCreate(siteKey, classJar, plain, true);
                if (s != null) return s;
            } catch (Throwable ignored) { }
            throw new IllegalStateException("Guard spider not supported on desktop (native lib; no plain class '" + plain + "' in fallback jar)");
        }

        final URLClassLoader loader = newLoader(classJar);
        Session s = scanAndCreate(siteKey, classJar, suffix, false, loader);
        if (s == null) {
            Path fallback = resolveFallbackJar();
            if (fallback != null) {
                try { s = scanAndCreate(siteKey, fallback, suffix, false, fallbackLoader(fallback)); } catch (Throwable t) { log("fallback load failed(" + suffix + "): " + t); }
            }
        }
        if (s != null) return s;
        throw new IllegalStateException("no Spider subclass found in jar (expect: " + suffix + ")");
    }

    static Session scanAndCreate(String siteKey, Path classJar, String suffix, boolean loose) throws Exception {
        return scanAndCreate(siteKey, classJar, suffix, loose, null);
    }

    static Session scanAndCreate(String siteKey, Path classJar, String suffix, boolean loose, URLClassLoader loader) throws Exception {
        final URLClassLoader ld = loader != null ? loader : newLoader(classJar);
        Class<?> found = null;
        List<Class<?>> candidates = new ArrayList<>();
        try (JarFile jarFile = new JarFile(classJar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                String cls = name.substring(0, name.length() - 6).replace('/', '.');
                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                boolean match;
                if (loose) match = simple.equalsIgnoreCase(suffix);
                else match = simple.equals(suffix) || suffix.startsWith(simple) || simple.startsWith(suffix);
                if (!match) continue;
                Class<?> c;
                try { c = ld.loadClass(cls); }
                catch (Throwable t) { continue; }
                if (com.github.catvod.crawler.Spider.class.isAssignableFrom(c) && !c.equals(com.github.catvod.crawler.Spider.class)) {
                    if (simple.equalsIgnoreCase(suffix)) { found = c; break; }
                    candidates.add(c);
                }
            }
        }
        if (found == null && candidates.size() == 1) found = candidates.get(0);
        if (found == null && !loose) {
            try (JarFile jarFile = new JarFile(classJar.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.contains("$")) continue;
                    String cls = name.substring(0, name.length() - 6).replace('/', '.');
                    Class<?> c;
                    try { c = ld.loadClass(cls); } catch (Throwable t) { continue; }
                    if (com.github.catvod.crawler.Spider.class.isAssignableFrom(c) && !c.equals(com.github.catvod.crawler.Spider.class))
                        candidates.add(c);
                }
            }
            if (candidates.size() == 1) found = candidates.get(0);
        }
        if (found == null) return null;
        initJarInit(ld);
        Constructor<?> ctor = found.getDeclaredConstructor();
        ctor.setAccessible(true);
        com.github.catvod.crawler.Spider spider;
        try { spider = (com.github.catvod.crawler.Spider) ctor.newInstance(); }
        catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            throw new IllegalStateException("class init failed " + found.getName() + ": " + (c == null ? ite.toString() : c.toString()));
        }
        return new Session(siteKey, ld, spider, found.getName());
    }

    // ---------- /call ----------

    static void call(HttpExchange ex) throws Exception {
        JSONObject body = readBody(ex);
        String siteKey = body.optString("siteKey", "");
        String method = body.optString("method", "");
        int timeoutMs = body.optInt("timeoutMs", 45000);
        if (timeoutMs < 3000) timeoutMs = 3000;
        if (timeoutMs > 240000) timeoutMs = 240000;
        Session s = SESSIONS.get(siteKey);
        if (s == null) throw new IllegalStateException("site not loaded: " + siteKey + " (/load first)");
        com.github.catvod.crawler.Spider spider = s.spider;
        java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> invoke(spider, method, body));
        Thread worker = new Thread(task, "spider-" + siteKey + "-" + method);
        worker.setDaemon(true);
        worker.start();
        String result;
        try {
            result = task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new IllegalStateException("spider call timeout (" + (timeoutMs / 1000) + "s): " + method + ", source may be dead or login required");
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
        result = result == null ? "" : result;
        reply(ex, 200, "text/plain; charset=utf-8", result);
    }

    static String invoke(com.github.catvod.crawler.Spider spider, String method, JSONObject body) throws Exception {
        String result;
        if ("homeContent".equals(method)) {
            result = spider.homeContent(body.optBoolean("filter", true));
        } else if ("homeVideoContent".equals(method)) {
            result = spider.homeVideoContent();
        } else if ("categoryContent".equals(method)) {
            result = categoryContent(spider,
                    body.optString("tid", ""), body.optString("pg", "1"),
                    body.optBoolean("filter", true), body.optJSONObject("extend"));
        } else if ("detailContent".equals(method)) {
            List<String> ids = new ArrayList<>();
            JSONArray arr = body.optJSONArray("ids");
            if (arr != null) for (int i = 0; i < arr.length(); i++) ids.add(arr.optString(i));
            result = spider.detailContent(ids);
        } else if ("searchContent".equals(method)) {
            String key = body.optString("key", "");
            boolean quick = body.optBoolean("quick", true);
            String pg = body.optString("pg", "1");
            result = "1".equals(pg) ? spider.searchContent(key, quick) : spider.searchContent(key, quick, pg);
        } else if ("playerContent".equals(method)) {
            List<String> vipFlags = new ArrayList<>();
            JSONArray arr = body.optJSONArray("vipFlags");
            if (arr != null) for (int i = 0; i < arr.length(); i++) vipFlags.add(arr.optString(i));
            result = spider.playerContent(body.optString("flag", ""), body.optString("id", ""), vipFlags);
        } else if ("liveContent".equals(method)) {
            result = spider.liveContent(body.optString("url", ""));
        } else if ("action".equals(method)) {
            result = spider.action(body.optString("action", ""));
        } else if ("isVideoFormat".equals(method)) {
            result = String.valueOf(spider.isVideoFormat(body.optString("url", "")));
        } else {
            throw new IllegalStateException("unknown method: " + method);
        }
        return result == null ? "" : result;
    }

    /** categoryContent dual-signature: (String,String,boolean,HashMap) standard; (String,String,boolean,JSONObject) legacy. */
    static String categoryContent(com.github.catvod.crawler.Spider spider, String tid, String pg, boolean filter, JSONObject extend) throws Exception {
        HashMap<String, String> map = toMap(extend);
        for (Method m : spider.getClass().getMethods()) {
            if (!"categoryContent".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 4 || !p[0].equals(String.class) || !p[1].equals(String.class) || !p[2].equals(boolean.class)) continue;
            if (p[3].equals(HashMap.class) || Map.class.isAssignableFrom(p[3]))
                return (String) m.invoke(spider, tid, pg, filter, map);
            if (p[3].equals(JSONObject.class))
                return (String) m.invoke(spider, tid, pg, filter, new JSONObject(map));
        }
        return spider.categoryContent(tid, pg, filter, map);
    }

    // ---------- /destroy /clear ----------

    static void destroy(HttpExchange ex) throws Exception {
        JSONObject body = readBody(ex);
        String siteKey = body.optString("siteKey", "");
        Session s = SESSIONS.remove(siteKey);
        if (s != null) {
            try { s.spider.destroy(); } catch (Throwable t) { log("destroy error " + t); }
            if (s.loader != fallbackLoader) {
                try { s.loader.close(); } catch (Throwable ignored) { }
                INITED.remove(s.loader);
            }
        }
        replyJson(ex, new JSONObject().put("ok", true));
    }

    static void clear(HttpExchange ex) throws Exception {
        for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
            try { e.getValue().spider.destroy(); } catch (Throwable ignored) { }
            if (e.getValue().loader != fallbackLoader) {
                try { e.getValue().loader.close(); } catch (Throwable ignored) { }
                INITED.remove(e.getValue().loader);
            }
        }
        SESSIONS.clear();
        replyJson(ex, new JSONObject().put("ok", true));
    }

    // ---------- /proxy ----------

    static void proxy(HttpExchange ex) throws Exception {
        Map<String, String> query = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                query.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                          java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        Session s = SESSIONS.get(query.getOrDefault("siteKey", ""));
        if (s == null) { sendError(ex, 500, "proxy site not loaded"); return; }
        Object[] rs = invokeProxy(s.spider, query);
        if (rs == null || rs.length < 3) { sendError(ex, 500, "proxy invalid response"); return; }
        int code = Integer.parseInt(String.valueOf(rs[0]));
        String mime = String.valueOf(rs[1]);
        Object payload = rs[2];
        // optional headers map (rs[3]): Location etc. must pass through (pan play = 302 redirect)
        if (rs.length > 3 && rs[3] instanceof Map) {
            for (Object k : ((Map<?, ?>) rs[3]).keySet()) {
                Object v = ((Map<?, ?>) rs[3]).get(k);
                if (k != null && v != null) ex.getResponseHeaders().set(String.valueOf(k), String.valueOf(v));
            }
        }
        byte[] body;
        if (payload instanceof InputStream) {
            // streaming body: chunked passthrough
            try {
                ex.getResponseHeaders().set("Content-Type", mime);
                ex.sendResponseHeaders(Math.max(100, Math.min(599, code)), 0);
                copy((InputStream) payload, ex.getResponseBody());
            } catch (IOException ignored) { }
            finally { try { ((InputStream) payload).close(); } catch (Throwable ignored) { } }
            return;
        }
        if (payload instanceof byte[]) body = (byte[]) payload;
        else if (payload instanceof String) body = ((String) payload).getBytes(StandardCharsets.UTF_8);
        else body = String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        try {
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(Math.max(100, Math.min(599, code)), body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
        } catch (IOException ignored) { }
    }

    /** both proxy method names: FongMi proxy(Map), legacy proxyLocal(Map). prefer subclass override. */
    static Object[] invokeProxy(com.github.catvod.crawler.Spider spider, Map<String, String> query) throws Exception {
        for (String name : new String[]{"proxy", "proxyLocal"}) {
            try {
                Method m = spider.getClass().getMethod(name, Map.class);
                if (m.getDeclaringClass() != com.github.catvod.crawler.Spider.class || name.equals("proxy")) {
                    Object rs = m.invoke(spider, query);
                    return rs == null ? null : (Object[]) rs;
                }
            } catch (NoSuchMethodException ignored) { }
        }
        Object rs = spider.proxy(query);
        return rs == null ? null : (Object[]) rs;
    }

    /** stream a media URL through the jar's own OkHttp (quark CDN blocks curl/HttpClient fingerprints). */
    static void jarStream(HttpExchange ex) throws Exception {
        // CORS：浏览器网页（9978）跨端口取视频流（9790）必需；Range 头触发预检需要回应 OPTIONS
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,OPTIONS");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        Map<String, String> query = parseQuery(ex);
        String siteKey = query.getOrDefault("siteKey", "");
        String url = query.getOrDefault("url", "");
        Session s = SESSIONS.get(siteKey);
        if (s == null || url.isEmpty()) { sendError(ex, 500, "jarstream: bad request"); return; }
        try {
        String cookie = readPanCookie();
        if (cookie.isEmpty()) log("jarstream: 警告：未找到夸克 Cookie（CDN 会返回 412，请先在网盘页登录）");
        else if (!cookie.contains("__puus")) log("jarstream: 警告：Cookie 缺少 __puus 字段（CDN 可能拒绝）");
        Class<?> kb = s.loader.loadClass("com.github.catvod.spider.merge.k.b");
            Method dm = kb.getMethod("d", String.class, java.util.Map.class);
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://pan.quark.cn");
            headers.put("Origin", "https://pan.quark.cn");
            if (!cookie.isEmpty()) headers.put("Cookie", cookie);
            headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.0.1 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch");
            // Range 透传：播放器可能带 Range 头
            String range = ex.getRequestHeaders().getFirst("Range");
            // 关键：bytes=0-（全文件）不透传 —— 若上游回 206+Content-Range，ffmpeg 会把连接当作
            // 可"soft-seek 排空"的窗口，拖拽时只读着前进、永不发起新区间请求（大文件必超时）。
            // 回退成 200 全量后，seek 时 ffmpeg 会正确地重连并带 Range: bytes=<目标>- 请求。
            if (range != null && range.matches("bytes=0-\\s*")) range = null;
            if (range != null && !range.isEmpty()) headers.put("Range", range);
            log("jarstream 入站 Range=" + (range == null ? "无(0-转200)" : range));
            Object resp = dm.invoke(null, url, headers);
            Object bodyObj = resp.getClass().getMethod("body").invoke(resp);
            InputStream in = (InputStream) bodyObj.getClass().getMethod("byteStream").invoke(bodyObj);
            long len = (Long) bodyObj.getClass().getMethod("contentLength").invoke(bodyObj);
            Object respCodeObj = resp.getClass().getMethod("code").invoke(resp);
            int code = Integer.parseInt(String.valueOf(respCodeObj));
            // 4XX 时抓取上游拒绝原因（诊断用）
            if (code >= 400) {
                try {
                    java.io.ByteArrayOutputStream eos = new java.io.ByteArrayOutputStream();
                    byte[] eb = new byte[512];
                    int en;
                    while ((en = in.read(eb)) > 0 && eos.size() < 512) eos.write(eb, 0, en);
                    in.close();
                    log("jarstream " + code + " 拒绝原因: " + new String(eos.toByteArray(), StandardCharsets.UTF_8).replace("\n", " "));
                    ex.getResponseHeaders().set("Content-Type", "text/plain");
                    ex.sendResponseHeaders(code, eos.size() == 0 ? -1 : eos.size());
                    if (eos.size() > 0) ex.getResponseBody().write(eos.toByteArray());
                    return;
                } catch (Throwable ignored) { }
            }
            String mime = (String) resp.getClass().getMethod("header", String.class).invoke(resp, "Content-Type");
            if (mime == null || mime.isEmpty()) mime = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp4";
            // m3u8 重写：分片/子列表 URL 换成中继地址 → 拖拽进度条可用（播放器按需经 jar HTTP 拉分片）
            if (mime.contains("mpegurl") || url.contains(".m3u8")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                copy(in, bos);
                in.close();
                String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (body.startsWith("#EXTM3U") || body.contains("#EXTINF")) {
                    String relayBase = "http://127.0.0.1:" + PORT + "/jarstream?siteKey=" + enc(siteKey) + "&url=";
                    String[] rawLines = body.split("\n");
                    log("m3u8预览: " + body.substring(0, Math.min(260, body.length())).replace("\n", " | "));
                    // 多码率主列表：只保留最高档变体（FFmpeg 默认取第一个=最低清）
                    java.util.Set<Integer> dropLines = new java.util.HashSet<>();
                    if (body.contains("#EXT-X-STREAM-INF")) {
                        int bestIdx = -1;
                        long bestScore = -1;
                        for (int i = 0; i < rawLines.length; i++) {
                            String t = rawLines[i].trim();
                            if (!t.startsWith("#EXT-X-STREAM-INF")) continue;
                            long score = 0;
                            java.util.regex.Matcher wm = java.util.regex.Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(t);
                            if (wm.find()) score = Long.parseLong(wm.group(1)) * Long.parseLong(wm.group(2));
                            else {
                                java.util.regex.Matcher bm2 = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                                if (bm2.find()) score = Long.parseLong(bm2.group(1));
                            }
                            log("m3u8变体: " + t.substring(0, Math.min(160, t.length())) + " score=" + score);
                            if (score > bestScore) { bestScore = score; bestIdx = i; }
                        }
                        for (int i = 0; i < rawLines.length; i++) {
                            if (!rawLines[i].trim().startsWith("#EXT-X-STREAM-INF") || i == bestIdx) continue;
                            dropLines.add(i);
                            for (int j = i + 1; j < rawLines.length; j++) {
                                String u = rawLines[j].trim();
                                if (u.isEmpty() || u.startsWith("#")) continue;
                                dropLines.add(j);
                                break;
                            }
                        }
                        log("m3u8 主列表过滤完成，保留 bestIdx=" + bestIdx + " score=" + bestScore);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int li = 0; li < rawLines.length; li++) {
                        if (dropLines.contains(li)) continue;
                        String line = rawLines[li];
                        String t = line.trim();
                        if (t.isEmpty()) { sb.append(line).append('\n'); continue; }
                        if (!t.startsWith("#")) {
                            sb.append(relayBase).append(enc(resolveUrl(url, t))).append('\n');
                            continue;
                        }
                        // 带 URI="..." / URI='...' / URI=xxx 属性的标签行（EXT-X-MAP/KEY/MEDIA/STREAM-INF 等）
                        if (t.toUpperCase().contains("URI=")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=(\"([^\"]*)\"|'([^']*)'|([^,\\s\"]+))").matcher(t);
                            StringBuilder r = new StringBuilder();
                            int last = 0;
                            while (m.find()) {
                                String refVal = m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : m.group(4));
                                if (refVal == null || refVal.isEmpty() || refVal.startsWith("http")) { last = m.end(); continue; }
                                String abs = resolveUrl(url, refVal);
                                String wrap;
                                if (m.group(2) != null) wrap = "URI=\"" + relayBase + enc(abs) + "\"";
                                else if (m.group(3) != null) wrap = "URI='" + relayBase + enc(abs) + "'";
                                else wrap = "URI=" + relayBase + enc(abs);
                                r.append(t, last, m.start()).append(wrap);
                                last = m.end();
                            }
                            r.append(t.substring(last));
                            sb.append(r).append('\n');
                            continue;
                        }
                        sb.append(line).append('\n');
                    }
                    byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
                    addXfer(out.length);
                    ex.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    log("jarstream m3u8 重写 " + code + " " + url.split("\\?")[0]);
                    return;
                }
                // 不是 m3u8 文本 → 当普通流回吐
                ex.getResponseHeaders().set("Content-Type", mime);
                ex.sendResponseHeaders(code >= 100 && code < 600 ? code : 200, bos.size() == 0 ? -1 : bos.size());
                if (bos.size() > 0) ex.getResponseBody().write(bos.toByteArray());
                return;
            }
            ex.getResponseHeaders().set("Content-Type", mime);
            // 转发 Range 相关响应头（拖拽进度条依赖 Content-Range/Accept-Ranges）
            for (String h : new String[]{"Content-Range", "Accept-Ranges", "Content-Length", "Last-Modified", "ETag"}) {
                try {
                    String v = (String) resp.getClass().getMethod("header", String.class).invoke(resp, h);
                    if (v != null && !v.isEmpty()) ex.getResponseHeaders().set(h, v);
                } catch (Throwable ignored) { }
            }
            // 夸克 CDN 首次 200 常缺 Accept-Ranges（实际支持 Range）→ 强制声明，否则播放器 seek 从头重下
            if (ex.getResponseHeaders().getFirst("Accept-Ranges") == null)
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.sendResponseHeaders(code >= 100 && code < 600 ? code : 200, len > 0 ? len : 0);
            long copyStart = System.currentTimeMillis();
            long copied = 0;
            try {
                byte[] cbuf = new byte[131072];
                java.io.OutputStream cos = ex.getResponseBody();
                int cn;
                while ((cn = in.read(cbuf)) > 0) { cos.write(cbuf, 0, cn); copied += cn; addXfer(cn); }
            } catch (IOException ignored) { }
            finally { try { in.close(); } catch (Throwable ignored) { } }
            long copyMs = Math.max(1, System.currentTimeMillis() - copyStart);
            log("jarstream " + code + " " + url.split("\\?")[0] + " len=" + len
                + " 传输=" + String.format("%.1f", copied / 1048576.0) + "MB/" + String.format("%.1f", copyMs / 1000.0) + "s="
                + String.format("%.1f", copied / 1048576.0 * 1000.0 / copyMs) + "MB/s url=" + (url.length() > 320 ? url.substring(0, 320) : url));
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            log("jarstream error: " + c);
            try { sendError(ex, 502, "jarstream error: " + c); } catch (Throwable ignored) { }
        }
    }

    /** 夸克播放响应：video_list=[{accessable,resolution,video_info:{url}}]；打印各档并把最高可用档排到最前。 */
    static String forceBestQuarkStream(String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return text;
            Object vl = data.opt("video_list");
            if (!(vl instanceof org.json.JSONArray)) return text;
            org.json.JSONArray arr = (org.json.JSONArray) vl;
            String[] order = {"4k", "2k", "super", "high", "normal", "low"};
            int bestIdx = -1, bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                String res = it.optString("resolution", "");
                boolean acc = it.optBoolean("accessable", false);
                JSONObject info = it.optJSONObject("video_info");
                String u = info == null ? "" : info.optString("url", "");
                String infoDump = info == null ? "无" : info.toString();
                log("quark 档位: res=" + res + " accessable=" + acc + " info=" + (infoDump.length() > 1400 ? infoDump.substring(0, 1400) : infoDump));
                if (acc) {
                    int r = rankOf(order, res);
                    if (r < bestRank) { bestRank = r; bestIdx = i; }
                }
            }
            if (bestIdx > 0) {
                org.json.JSONArray reordered = new org.json.JSONArray();
                reordered.put(arr.getJSONObject(bestIdx));
                for (int i = 0; i < arr.length(); i++) if (i != bestIdx) reordered.put(arr.getJSONObject(i));
                data.put("video_list", reordered);
                log("quark 最高可用档已排到第一位: " + arr.getJSONObject(bestIdx).optString("resolution", ""));
            } else if (bestIdx == 0) {
                log("quark 第一位已是最高可用档");
            } else {
                log("quark 警告: 无 accessable 档!");
            }
            return root.toString();
        } catch (Throwable t) { log("quark 清晰度处理失败: " + t); }
        return text;
    }

    static int rankOf(String[] order, String name) {
        for (int i = 0; i < order.length; i++) if (order[i].equalsIgnoreCase(name)) return i;
        return 900;
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String resolveUrl(String base, String ref) {
        try {
            return java.net.URI.create(base).resolve(ref).toString();
        } catch (Throwable ignored) { }
        if (ref.startsWith("http")) return ref;
        try {
            String dir = base.contains("?") ? base.substring(0, base.indexOf('?')) : base;
            dir = dir.substring(0, dir.lastIndexOf('/') + 1);
            while (ref.startsWith("./")) ref = ref.substring(2);
            if (ref.startsWith("/")) {
                int i = dir.indexOf('/', dir.indexOf("//") + 2);
                return dir.substring(0, i) + ref;
            }
            return dir + ref;
        } catch (Throwable ignored) { }
        return ref;
    }

    static Map<String, String> parseQuery(HttpExchange ex) {
        Map<String, String> query = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                query.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                          java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return query;
    }

    /** pan cookie: prefer the temp file the jar actually reads (it rotates the session there). */
    static String readPanCookie() {
        // 多来源、多格式兜底：JSON(cookie 字段) / 纯 cookie 文本 / 带 BOM，任何一个可用即返回
        Path tmpDir = java.nio.file.Paths.get(android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), "TVBox");
        Path[] candidates = {
                tmpDir.resolve("quark_cookie.txt"),
                tmpDir.resolve("quark_cookie"),
                DATA_DIR.resolve("files").resolve("Pizazz").resolve("quark_cookie.txt"),
                DATA_DIR.resolve("files").resolve("Pizazz").resolve("quark_cookie")
        };
        for (Path p : candidates) {
            try {
                if (!Files.exists(p) || Files.size(p) <= 10) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
                if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
                if (text.startsWith("{")) {
                    String c = new JSONObject(text).optString("cookie", "");
                    if (!c.isEmpty()) return c;
                } else if (text.contains("=") && !text.contains("\n") && !text.contains("{")) {
                    return text; // 纯 cookie 文本兜底
                }
            } catch (Throwable ignored) { }
        }
        log("readPanCookie: no valid cookie found in any candidate path");
        return "";
    }

    /** relay an HTTP POST through the jar's own OkHttp stack (k.b.f) - quark anti-bot allows it, blocks curl/HttpClient. */
    static void jarPost(HttpExchange ex) throws Exception {
        JSONObject body = readBody(ex);
        String siteKey = body.optString("siteKey", "");
        String url = body.optString("url", "");
        String payload = body.optString("body", "");
        // 夸克 file/v2/play：分辨率偏好改为最高优先（原顺序 normal 在前会拿到低清流）
        if (url.contains("file/v2/play") && payload.contains("\"resolutions\"")) {
            payload = payload.replaceAll("\"resolutions\"\\s*:\\s*\"[^\"]*\"", "\"resolutions\":\"4k,2k,super,high,normal,low\"");
            log("quark play 已改分辨率偏好=4k,2k,super,high,normal,low");
        }
        Session s = SESSIONS.get(siteKey);
        if (s == null) { sendError(ex, 500, "site not loaded"); return; }
        HashMap<String, String> headers = new HashMap<>();
        JSONObject hj = body.optJSONObject("headers");
        if (hj != null) for (String k : hj.keySet()) headers.put(k, hj.optString(k, ""));
        // 始终用 jar 当前会话的 Cookie（它在 %TEMP%\TVBox 滚动刷新，调用方可能持有旧副本）
        String freshCookie = readPanCookie();
        if (!freshCookie.isEmpty()) headers.put("Cookie", freshCookie);
        try {
            Class<?> kb = s.loader.loadClass("com.github.catvod.spider.merge.k.b");
            String method = body.optString("method", "post");
            String text;
            Object result = null;
            if ("get".equals(method)) {
                Method dm = kb.getMethod("d", String.class, java.util.Map.class);
                Object resp = dm.invoke(null, url, headers);
                result = resp;
                Object bodyObj = resp.getClass().getMethod("body").invoke(resp);
                text = (String) bodyObj.getClass().getMethod("string").invoke(bodyObj);
            } else {
                Method f = kb.getMethod("f", String.class, String.class, java.util.Map.class);
                Object d = f.invoke(null, url, payload, headers);
                result = d;
                text = (String) d.getClass().getMethod("a").invoke(d);
            }
            // 官方蜘蛛同款：从响应 Set-Cookie 收割新的 __puus 并持久化（会话滚动续期）
            try { harvestPuus(result); } catch (Throwable ignored) { }
            reply(ex, 200, "text/plain; charset=utf-8", text == null ? "" : text);
            log("jarpost[" + method + "] " + url.split("\\?")[0] + " -> " + (text == null ? 0 : text.length()) + " chars"
                + (text != null && text.length() > 0 && text.length() < 600 ? " body=" + text.replace("\n", " ") : ""));
            if (text != null && url.contains("file/v2/play")) {
                java.util.regex.Matcher rm = java.util.regex.Pattern.compile("\"resolutions?\"\\s*:\\s*\\[[^\\]]*\\]|\"resolution\"\\s*:\\s*\"[^\"]*\"|\"video_list\"\\s*:\\s*\\{[^\\{]{0,200}").matcher(text);
                while (rm.find()) log("quark play resp: " + rm.group());
                text = forceBestQuarkStream(text);
            }
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            sendError(ex, 500, "jarpost error: " + c);
        }
    }

    /** reflect the pan engine's saved file fid (w.fid) for the site's last transfer. */
    static void savedFid(HttpExchange ex) throws Exception {
        Map<String, String> query = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                query.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                          java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        Session s = SESSIONS.get(query.getOrDefault("siteKey", ""));
        if (s == null) { sendError(ex, 500, "site not loaded"); return; }
        try {
            Class<?> wc = s.loader.loadClass("com.github.catvod.spider.merge.b.w");
            Class<?> inner = s.loader.loadClass("com.github.catvod.spider.merge.b.w$a");
            Object inst = null;
            for (java.lang.reflect.Field f : inner.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == wc) { inst = f.get(null); break; }
            }
            if (inst == null) { sendError(ex, 500, "no w instance"); return; }
            java.lang.reflect.Field ff = wc.getDeclaredField("fid");
            ff.setAccessible(true);
            Object fid = ff.get(inst);
            reply(ex, 200, "text/plain", fid == null ? "" : String.valueOf(fid));
        } catch (Throwable t) {
            sendError(ex, 500, "savedfid error: " + t);
        }
    }

    /** invoke the pan engine's own transfer (b.w.c(shareId, fileId, fileToken, true)): handles the
     * Quarktemp folder, file-token refresh, 41013 retry; success also updates w.fid (see /savedfid). */
    static void transfer(HttpExchange ex) throws Exception {
        Map<String, String> query = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                query.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                          java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        Session s = SESSIONS.get(query.getOrDefault("siteKey", ""));
        if (s == null) { sendError(ex, 500, "site not loaded"); return; }
        try {
            Class<?> wc = s.loader.loadClass("com.github.catvod.spider.merge.b.w");
            Class<?> inner = s.loader.loadClass("com.github.catvod.spider.merge.b.w$a");
            Object inst = null;
            for (java.lang.reflect.Field f : inner.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == wc) { inst = f.get(null); break; }
            }
            if (inst == null) { sendError(ex, 500, "no w instance"); return; }
            Method m = wc.getDeclaredMethod("c", String.class, String.class, String.class, boolean.class);
            m.setAccessible(true);
            Object fid = m.invoke(inst, query.getOrDefault("shareId", ""), query.getOrDefault("fileId", ""),
                    query.getOrDefault("fileToken", ""), Boolean.TRUE);
            reply(ex, 200, "text/plain", fid == null ? "" : String.valueOf(fid));
        } catch (Throwable t) {
            sendError(ex, 500, "transfer error: " + t);
        }
    }

    // ---------- token injection ----------
    // ---------- 会话续期：收割响应 Set-Cookie 中的新 __puus ----------
    static void harvestPuus(Object result) {
        if (result == null) return;
        try {
            Object resp = null;
            try { resp = result.getClass().getMethod("getResp").invoke(result); } catch (Throwable ignored) { }
            Object sc = null;
            if (resp instanceof java.util.Map) sc = ((java.util.Map<?, ?>) resp).get("Set-Cookie");
            if (sc == null) {
                try { sc = result.getClass().getMethod("header", String.class).invoke(result, "Set-Cookie"); } catch (Throwable ignored) { }
            }
            if (sc != null) {
                String names = headerNames(sc);
                if (!names.isEmpty()) log("响应 Set-Cookie: " + names);
                harvestPuusFromHeaders(sc);
            }
        } catch (Throwable ignored) { }
    }

    /** 只取 Cookie 名（不记录值，避免泄露令牌）。 */
    static String headerNames(Object v) {
        try {
            java.util.List<?> list = (v instanceof java.util.List) ? (java.util.List<?>) v : java.util.Collections.singletonList(v);
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                String s = String.valueOf(o);
                int eq = s.indexOf('=');
                String n = eq > 0 ? s.substring(0, eq).trim() : s.trim();
                if (n.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(n);
            }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    static void harvestPuusFromHeaders(Object v) {
        if (v == null) return;
        String joined;
        if (v instanceof java.util.List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (java.util.List<?>) v) sb.append(o).append(";;;");
            joined = sb.toString();
        } else {
            joined = String.valueOf(v);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("__puus=([^;]+)").matcher(joined);
        if (m.find()) mergePuus(m.group(1));
    }

    /** 把新 __puus 合并进持久化 Cookie（%TEMP% + Pizazz 两处，保留 JSON 元数据）。 */
    static synchronized void mergePuus(String val) {
        try {
            if (val == null || val.isEmpty()) return;
            Path tmp = java.nio.file.Paths.get(android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), "TVBox", "quark_cookie.txt");
            if (!Files.exists(tmp)) return;
            String text = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8);
            String merged = replacePuus(text, val);
            if (merged == null || merged.equals(text)) return;
            Files.write(tmp, merged.getBytes(StandardCharsets.UTF_8));
            for (String dirName : new String[]{"Pizazz", "lzxw"}) {
                for (String name : new String[]{"quark_cookie.txt", "quark_cookie"}) {
                    Path p = DATA_DIR.resolve("files").resolve(dirName).resolve(name);
                    try {
                        if (Files.exists(p)) {
                            String t2 = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            String m2 = replacePuus(t2, val);
                            if (m2 != null && !m2.equals(t2)) Files.write(p, m2.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Throwable ignored) { }
                }
            }
            log("harvested new __puus (" + val.length() + " chars)");
        } catch (Throwable t) { /* ignore */ }
    }

    static String replacePuus(String text, String val) {
        try {
            if (text.trim().startsWith("{")) {
                org.json.JSONObject o = new org.json.JSONObject(text);
                String c = o.optString("cookie", "");
                if (c.isEmpty()) return null;
                o.put("cookie", doReplacePuus(c, val));
                return o.toString();
            }
            return doReplacePuus(text.trim(), val);
        } catch (Throwable t) {
            return null;
        }
    }

    static String doReplacePuus(String c, String val) {
        if (c.contains("__puus=")) {
            return c.replaceAll("__puus=[^;\\s]+", "__puus=" + java.util.regex.Matcher.quoteReplacement(val));
        }
        return c + ";__puus=" + val;
    }

    /** self-heal: Windows temp cleanup wipes %TEMP%\TVBox; re-sync pan token files from jarcache. */
    static void syncPanTokenFiles() {        try {
            Path srcDir = DATA_DIR.resolve("files").resolve("Pizazz");
            if (!Files.exists(srcDir)) return;
            Path tmpDir = java.nio.file.Paths.get(android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), "TVBox");
            Files.createDirectories(tmpDir);
            String[] names = { "quark_cookie.txt", "quark_cookie" };
            for (String name : names) {
                Path src = srcDir.resolve(name);
                if (!Files.exists(src)) continue;
                Path dst = tmpDir.resolve(name);
                if (!Files.exists(dst) || Files.size(dst) != Files.size(src)) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    log("synced " + name + " -> " + tmpDir);
                }
            }
        } catch (Throwable t) { log("sync token files failed: " + t); }
    }

    /** set the jar's bundled Proxy port field directly (probe-based detection is unreliable on desktop). */
    static void injectProxyPort(URLClassLoader ld) {
        try {
            int port = URI.create(proxyBase).getPort();
            if (port <= 0) return;
            Class<?> pc = ld.loadClass("com.github.catvod.spider.Proxy");
            for (java.lang.reflect.Field f : pc.getDeclaredFields()) {
                if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    f.set(null, port);
                    log("Proxy port injected: " + port);
                    return;
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { log("Proxy port inject failed: " + t); }
    }

    /** inject quark cookie into pan engine (merge.b.w) token channel: equivalent of Android set-cookie dialog. */
    static void injectPanToken(URLClassLoader ld) {
        try {
            Path p = java.nio.file.Paths.get(android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), "TVBox", "quark_cookie.txt");
            if (!Files.exists(p)) return;
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String cookie = new org.json.JSONObject(json).optString("cookie", "");
            if (cookie.isEmpty()) return;
            Class<?> wc = ld.loadClass("com.github.catvod.spider.merge.b.w");
            Class<?> inner = ld.loadClass("com.github.catvod.spider.merge.b.w$a");
            Object inst = null;
            for (java.lang.reflect.Field f : inner.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == wc) { inst = f.get(null); break; }
            }
            if (inst == null) return;
            for (java.lang.reflect.Method m : wc.getDeclaredMethods()) {
                if ("a".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == wc && m.getParameterTypes()[1] == String.class) {
                    m.setAccessible(true);
                    m.invoke(null, inst, cookie);
                    log("pan token injected (" + cookie.length() + " chars)");
                    return;
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { log("pan token inject failed: " + t); }
    }

    // ---------- Init ----------

    static final java.util.Set<URLClassLoader> INITED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void initJarInit(URLClassLoader ld) {
        if (ld == null || !INITED.add(ld)) return;
        try {
            Class<?> c = ld.loadClass("com.github.catvod.spider.Init");
            // 兜底：直接反射设置 Init 单例的 Application 字段。
            // 部分加固包（缺 Init$UP 等内部类）调用 init() 会抛错，导致 k.d()/Init.context() 为 null，
            // 进而所有站点 detail 崩 NPE。这里先保证 context 可用（不依赖 init 成功）。
            try {
                Class<?> loader = ld.loadClass("com.github.catvod.spider.Init$Loader");
                Object inst = null;
                java.lang.reflect.Field loaderField = null;
                for (java.lang.reflect.Field f : loader.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                        f.setAccessible(true);
                        loaderField = f;
                        inst = f.get(null);
                        break;
                    }
                }
                if (inst == null) {
                    inst = c.getDeclaredConstructor().newInstance();
                    if (loaderField != null) loaderField.set(null, inst);
                }
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == android.app.Application.class || f.getType() == android.content.Context.class) {
                        f.setAccessible(true);
                        if (f.get(inst) == null) f.set(inst, new HostContext());
                    }
                }
                log("Init context 兜底设置完成");
            } catch (Throwable t) {
                log("Init context 兜底失败: " + t);
            }
            for (Method m : c.getMethods()) {
                if (!"init".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!android.content.Context.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                try {
                    invokeWithTimeout(null, m, new Object[]{ new HostContext() }, 8000, "Init.init");
                } catch (Throwable t) {
                    log("Init.init 调用失败（context 已兜底，忽略）: " + t);
                }
                return;
            }
            log("Init: no matching init(Context)");
        } catch (ClassNotFoundException e) {
            INITED.remove(ld);
        } catch (Throwable t) {
            log("Init.init error: " + t);
        }
    }

    /** invoke with timeout: desktop Init/Go proxy may block forever, give up on timeout, keep partial state. Exceptions re-thrown. */
    static void invokeWithTimeout(Object target, Method m, Object[] args, long timeoutMs, String tag) throws Exception {
        java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(() -> m.invoke(target, args));
        Thread worker = new Thread(task, "init-" + tag);
        worker.setDaemon(true);
        worker.start();
        try {
            task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            log(tag + " timeout (" + (timeoutMs / 1000) + "s), skip and continue");
        } catch (Exception e) {
            Throwable c = e.getCause();
            if (c == null) c = e;
            if (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
            throw new Exception(tag + ": " + c.toString(), c);
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
    }

    // ---------- utils ----------

    static HashMap<String, String> toMap(JSONObject obj) {
        HashMap<String, String> map = new HashMap<>();
        if (obj != null) for (String key : obj.keySet()) map.put(key, obj.optString(key, ""));
        return map;
    }

    static JSONObject readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) { copy(in, bos); }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    static byte[] httpGet(String url) throws Exception {
        HttpURLConnection conn;
        String proxy = System.getenv("HTTP_PROXY");
        if (proxy != null && !proxy.isEmpty() && !url.contains("127.0.0.1")) {
            try {
                URI p = URI.create(proxy);
                conn = (HttpURLConnection) new URL(url).openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(p.getHost(), p.getPort())));
            } catch (Exception e) { conn = (HttpURLConnection) new URL(url).openConnection(); }
        } else conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "okhttp/3.15");
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("jar download failed HTTP " + code + ": " + url);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) { copy(in, bos); }
        return bos.toByteArray();
    }

    static String md5(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String tail(String s, int max) {
        s = s.replaceAll("\r", "");
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void reply(HttpExchange ex, int code, String mime, String body) throws IOException {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
        } catch (IOException e) {
            // client gone (upstream timeout), silent
        }
    }

    static void replyJson(HttpExchange ex, JSONObject json) throws IOException {
        reply(ex, 200, "application/json; charset=utf-8", json.toString());
    }

    static void sendError(HttpExchange ex, int code, String message) throws IOException {
        reply(ex, code, "application/json; charset=utf-8", new JSONObject().put("message", message).toString());
    }

    /* ---------- dex2jar 缺陷修复：invoke-super 被翻成 invokespecial 调用自己（无限递归/SOE） ----------
       规则：Methodref 指向本类、非 <init>、非本类私有方法、且父类链上确有同名同描述符的【非静态】方法
             → 把常量池里该 Methodref 的 class_index 改成本类父类（只动 2 字节，不碰任何代码）。 */
    static void fixSelfInvokes(Path jar) throws Exception {
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                byte[] b;
                try (InputStream in = jf.getInputStream(e)) { b = readAll(in); }
                entries.put(e.getName(), b);
            }
        }
        int fixedMethods = 0, fixedClasses = 0;
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            if (!en.getKey().endsWith(".class")) continue;
            int[] c = new int[1];
            byte[] nb = fixClassBytes(en.getValue(), entries, c);
            if (nb != null) { en.setValue(nb); fixedClasses++; fixedMethods += c[0]; }
        }
        if (fixedMethods == 0) return;
        Path tmp = jar.resolveSibling(jar.getFileName().toString() + ".fixing");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(en.getKey()));
                zos.write(en.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        log("修复 dex2jar 自我调用: " + fixedClasses + " 类 / " + fixedMethods + " 方法");
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static int h1(byte[] d, int p) { return d[p] & 0xFF; }
    static int h2(byte[] d, int p) { return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); }
    static int h4(byte[] d, int p) { return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF); }
    static int skipAttrs(byte[] d, int p) { int ac = h2(d, p); p += 2; for (int i = 0; i < ac; i++) { int len = h4(d, p + 2); p += 6 + len; } return p; }

    /** 父类链上是否有该"非静态"方法（从本 jar 或宿主自身资源取类字节解析，不加载类） */
    static boolean superHasInstanceMethod(Map<String, byte[]> entries, String className, String name, String desc) {
        String cur = className;
        int guard = 0;
        while (cur != null && guard++ < 40) {
            byte[] bytes = entries.get(cur + ".class");
            if (bytes == null) {
                try (InputStream in = Host.class.getResourceAsStream("/" + cur + ".class")) {
                    if (in != null) bytes = readAll(in);
                } catch (Exception ex) { }
            }
            if (bytes == null) return false;
            Map<String, Integer> methods = new HashMap<>();
            String[] meta = parseMeta(bytes, methods);
            if (meta == null) return false;
            Integer acc = methods.get(name + desc);
            if (acc != null) return (acc & 0x0008) == 0;
            cur = meta[1];
        }
        return false;
    }

    /** 解析类文件的方法表与父类名：返回 [thisName, superName] */
    static String[] parseMeta(byte[] d, Map<String, Integer> methods) {
        try {
            if (h4(d, 0) != 0xCAFEBABE) return null;
            int p = 8;
            int cpCount = h2(d, p); p += 2;
            int[] tag = new int[cpCount];
            String[] utf8 = new String[cpCount];
            int[] a = new int[cpCount], b = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int t = h1(d, p); tag[i] = t; p++;
                switch (t) {
                    case 1: { int len = h2(d, p); p += 2; utf8[i] = new String(d, p, len, java.nio.charset.StandardCharsets.UTF_8); p += len; break; }
                    case 3: case 4: p += 4; break;
                    case 5: case 6: p += 8; i++; break;
                    case 7: case 8: case 16: case 19: case 20: a[i] = h2(d, p); p += 2; break;
                    case 15: p += 3; break;
                    case 9: case 10: case 11: case 12: case 17: case 18: a[i] = h2(d, p); b[i] = h2(d, p + 2); p += 4; break;
                    default: return null;
                }
            }
            int thisIdx = h2(d, p + 2), superIdx = h2(d, p + 4);
            String thisName = utf8[a[thisIdx]];
            String superName = superIdx == 0 ? null : utf8[a[superIdx]];
            p += 6;
            int ic = h2(d, p); p += 2 + ic * 2;
            int fc = h2(d, p); p += 2;
            for (int i = 0; i < fc; i++) { p += 6; p = skipAttrs(d, p); }
            int mc = h2(d, p); p += 2;
            for (int i = 0; i < mc; i++) {
                int acc = h2(d, p), ni = h2(d, p + 2), di = h2(d, p + 4);
                p += 6;
                p = skipAttrs(d, p);
                if (utf8[ni] != null && utf8[di] != null) methods.put(utf8[ni] + utf8[di], acc);
            }
            return new String[]{thisName, superName};
        } catch (Exception e) { return null; }
    }

    /** 修补单个类的字节；返回新字节或 null；count[0] 累加修复的方法数 */
    static byte[] fixClassBytes(byte[] d, Map<String, byte[]> entries, int[] count) {
        try {
            if (h4(d, 0) != 0xCAFEBABE) return null;
            int p = 8;
            int cpCount = h2(d, p); p += 2;
            int[] tag = new int[cpCount];
            String[] utf8 = new String[cpCount];
            int[] nameIdx = new int[cpCount], descIdx = new int[cpCount];
            int[] refClass = new int[cpCount], refNat = new int[cpCount], refOff = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int t = h1(d, p); tag[i] = t; p++;
                switch (t) {
                    case 1: { int len = h2(d, p); p += 2; utf8[i] = new String(d, p, len, java.nio.charset.StandardCharsets.UTF_8); p += len; break; }
                    case 3: case 4: p += 4; break;
                    case 5: case 6: p += 8; i++; break;
                    case 7: nameIdx[i] = h2(d, p); p += 2; break;
                    case 8: case 16: case 19: case 20: p += 2; break;
                    case 15: p += 3; break;
                    case 9: case 10: case 11: case 12: case 17: case 18: {
                        int x = h2(d, p), y = h2(d, p + 2);
                        if (t == 10) { refClass[i] = x; refNat[i] = y; refOff[i] = p; }
                        else { nameIdx[i] = x; descIdx[i] = y; }
                        p += 4; break;
                    }
                    default: return null;
                }
            }
            int thisIdx = h2(d, p + 2), superIdx = h2(d, p + 4);
            if (superIdx == 0) return null;
            String thisName = utf8[nameIdx[thisIdx]], superName = utf8[nameIdx[superIdx]];
            p += 6;
            int ic = h2(d, p); p += 2 + ic * 2;
            int fc = h2(d, p); p += 2;
            for (int i = 0; i < fc; i++) { p += 6; p = skipAttrs(d, p); }
            int mc = h2(d, p); p += 2;
            java.util.Set<String> privates = new java.util.HashSet<>();
            for (int i = 0; i < mc; i++) {
                int acc = h2(d, p), ni = h2(d, p + 2), di = h2(d, p + 4);
                p += 6;
                p = skipAttrs(d, p);
                if ((acc & 0x0002) != 0 && utf8[ni] != null && utf8[di] != null) privates.add(utf8[ni] + utf8[di]);
            }
            boolean changed = false;
            for (int i = 1; i < cpCount; i++) {
                if (tag[i] != 10 || refClass[i] != thisIdx) continue;
                int nt = refNat[i];
                if (nt <= 0 || nt >= cpCount) continue;
                int ni = nameIdx[nt], di = descIdx[nt];
                if (ni <= 0 || di <= 0 || utf8[ni] == null || utf8[di] == null) continue;
                String nm = utf8[ni], ds = utf8[di];
                if (nm.equals("<init>") || privates.contains(nm + ds)) continue;
                if (!superHasInstanceMethod(entries, superName, nm, ds)) continue;
                d[refOff[i]] = (byte) ((superIdx >> 8) & 0xFF);
                d[refOff[i] + 1] = (byte) (superIdx & 0xFF);
                count[0]++;
                changed = true;
            }
            return changed ? d : null;
        } catch (Exception e) { return null; }
    }

    static void log(String msg) { System.out.println("[jar-host] " + msg); }

    static class Session {
        final String key;
        final URLClassLoader loader;
        final com.github.catvod.crawler.Spider spider;
        final String className;
        Session(String key, URLClassLoader loader, com.github.catvod.crawler.Spider spider, String className) {
            this.key = key; this.loader = loader; this.spider = spider; this.className = className;
        }
    }

    /** Context stub for Spider.init (Guard/Init may checkcast Application). package name must be in whitelist. */
    public static class HostContext extends android.app.Application {
        public File getCacheDir() { return DATA_DIR.resolve("cache").toFile(); }
        public File getFilesDir() { return DATA_DIR.resolve("files").toFile(); }
        public File getDir(String name, int mode) { return DATA_DIR.resolve(name).toFile(); }
        public String getPackageName() { return "com.fongmi.android.tv"; }
    }

    // ---------- default config ----------

    static final String DEFAULT_CONFIG_JSON = "{\"panBlock\":\"\",\"quarkQuality\":\"\",\"quarkThread\":\"5\",\"quarktip\":\"\",\"quark_cookie\":\"\"," +
            "\"ucQuality\":\"\",\"ucThread\":\"5\",\"uctip\":\"\",\"uc_cookie\":\"\"," +
            "\"aliQuality\":\"\",\"aliThread\":\"5\",\"alitip\":\"\"," +
            "\"baiduQuality\":\"\",\"baiduThread\":\"5\",\"123Quality\":\"\",\"123Thread\":\"5\"," +
            "\"xunleiThread\":\"5\",\"guangyaThread\":\"5\",\"update\":\"\"}";

    /** merge framework reads files/Pizazz/config.json (Gson get() NPE on missing key) -> fill missing keys, keep login state. */
    static void ensureDefaultConfig() {
        try {
            Path dir = DATA_DIR.resolve("files").resolve("Pizazz");
            Files.createDirectories(dir);
            Path cfg = dir.resolve("config.json");
            org.json.JSONObject current;
            try {
                String existing = Files.exists(cfg) ? new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8) : "{}";
                current = new org.json.JSONObject(existing.isEmpty() ? "{}" : existing);
            } catch (Throwable t) { current = new org.json.JSONObject(); }
            org.json.JSONObject defaults = new org.json.JSONObject(DEFAULT_CONFIG_JSON);
            boolean changed = !Files.exists(cfg);
            for (String key : defaults.keySet()) {
                if (!current.has(key)) { current.put(key, defaults.get(key)); changed = true; }
            }
            if (changed) {
                Files.write(cfg, current.toString().getBytes(StandardCharsets.UTF_8));
                log("default config ensured");
            }
        } catch (Throwable t) { log("ensure config failed: " + t); }
    }
}
