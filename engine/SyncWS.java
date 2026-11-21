package dev.flytv.engine;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 变更通知（引擎端，基于 OkHttp —— 不依赖精简 JRE 的 java.net.http 模块）：
 * 与同步服务保持一条 ws://host:port/ws/&lt;token&gt;?key=… 长连接；
 * 服务器一有数据变化就推 {"type":"changed"} → 立即触发一次同步（拉取合并）。
 * WS 断开自动重连；未连接时由 Sync.startAutoSync 的长轮询兜底。
 */
public final class SyncWS {

    private static volatile boolean alive = false;
    private static volatile boolean started = false;
    private static volatile long changedCount = 0;
    private static volatile long lastTrigger = 0;

    public static boolean isAlive() { return alive; }
    public static long changes() { return changedCount; }

    /** 由同步地址推导 WS 地址：http://host:port/d/<token>?key=… → ws://host:port/ws/<token>?key=… */
    static String toWsUrl(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null) return null;
            String token = path.substring(path.lastIndexOf('/') + 1);
            if (token.isEmpty()) return null;
            String query = u.getQuery();
            String host = u.getHost();
            int port = u.getPort();
            return "ws://" + host + (port > 0 ? ":" + port : "") + "/ws/" + token + (query != null && !query.isEmpty() ? "?" + query : "");
        } catch (Exception e) {
            return null;
        }
    }

    public static void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String url = Setting.getString("sync_url", "");
                    String pass = Setting.getString("sync_pass", "");
                    if (url.isEmpty() || pass.isEmpty()) {
                        Thread.sleep(15000);
                        continue;
                    }
                    // 关键：钥匙在 authUrl 里附加（存库的是裸地址，没有 ?key=）
                    final String wsUrl = toWsUrl(Sync.authUrl(url, pass));
                    if (wsUrl == null) {
                        Thread.sleep(15000);
                        continue;
                    }
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接不读超时
                            .pingInterval(25, TimeUnit.SECONDS)      // 自动 ping 保活
                            .build();
                    alive = false;
                    client.newWebSocket(new Request.Builder().url(wsUrl).build(), new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            alive = true;
                            Logger.d("SyncWS", "WebSocket 已连接: " + wsUrl);
                            // 连上就同步一次：拉取云端新数据 + 本地有变化则回传（等价"打开程序就同步一次"）
                            new Thread(() -> {
                                try { Sync.autoSync(false); } catch (Throwable ignored) { }
                            }, "ws-open-sync").start();
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String text) {
                            try {
                                Sync.lastNotifyAt = System.currentTimeMillis();
                                if (text == null || !text.contains("\"type\":\"changed\"")) return;
                                // 忽略自己刚写入引起的通知：消息里带 rev 且与本地一致时直接跳过
                                java.util.regex.Matcher rm = java.util.regex.Pattern.compile("\"rev\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                                if (rm.find()) {
                                    try {
                                        String hurl = Sync.historyUrl(Sync.authUrl(Setting.getString("sync_url", ""), Setting.getString("sync_pass", "")));
                                        String mine = Sync.REVS.get(Sync.revKey(hurl));
                                        if (mine != null && mine.equals(rm.group(1))) return;
                                    } catch (Exception ignored) { }
                                }
                                long now = System.currentTimeMillis();
                                // 通知风暴保护：上一轮没拉到/推过东西就放慢到 12 秒，避免 2Hz 循环
                                long gap = (Sync.lastPulled || Sync.lastPushed) ? 2000 : 12000;
                                if (now - lastTrigger < gap) return;
                                lastTrigger = now;
                                changedCount++;
                                Logger.d("SyncWS", "收到变更通知 #" + changedCount + "，立即同步");
                                new Thread(() -> {
                                    try { Sync.autoSync(false); } catch (Throwable ignored) { }
                                }, "ws-sync").start();
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        public void onClosed(WebSocket webSocket, int code, String reason) {
                            alive = false;
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t2, Response response) {
                            alive = false;
                            Logger.d("SyncWS", "WebSocket 失败: " + (t2 == null ? "?" : t2.getMessage()));
                        }
                    });
                    // 等握手结果（最多 12 秒）
                    long t0 = System.currentTimeMillis();
                    while (!alive && System.currentTimeMillis() - t0 < 12000) {
                        Thread.sleep(500);
                    }
                    while (alive) {
                        Thread.sleep(3000);
                    }
                    Logger.d("SyncWS", "WebSocket 断开，5 秒后重连");
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable e) {
                    alive = false;
                    Logger.d("SyncWS", "WebSocket 异常: " + e.getMessage());
                }
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }, "sync-ws");
        t.setDaemon(true);
        t.start();
        Logger.d("SyncWS", "WebSocket 通知已启动");
    }
}
