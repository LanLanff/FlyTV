package dev.flytv.engine;

/** Java 引擎入口：路径初始化 → 设置/仓储加载 → 爬虫宿主 + 配置加载 + 拼音索引 → HTTP 服务 → 常驻。 */
public final class Main {
    public static void main(String[] args) {
        AppPaths.init();
        Logger.d("Main", "FlyTV 引擎启动（install=" + AppPaths.InstallRoot + "）");
        Setting.load();
        Stores.warmup();
        // 爬虫宿主预热（后台，不阻塞 Web 服务）
        new Thread(() -> {
            try { JarHost.start(); } catch (Exception e) { Logger.e("Main", "宿主启动异常: " + e); }
            try { JarHost.startWatchdog(); } catch (Exception e) { Logger.e("Main", "宿主看门狗异常: " + e); }
        }, "jar-host-start").start();
        // 点播配置加载（后台；完成前 API 首访会等待，最多 25 秒）
        new Thread(() -> {
            try {
                VodConfig.loadStartup();
                JsSpider.warmup();   // 后台预热 JS 引擎（首次打开 JS 源不用等 20 秒）
            } finally {
                Api.READY.countDown();
            }
        }, "config-load").start();
        // 拼音索引预热（搜索联想）
        PinyinIndex.warmup();
        // 网盘会话保活（每 20 分钟续期一次）
        PanService.startKeepAlive();
        // 历史/收藏自动同步（每 5 分钟，云端 WebDAV）
        Sync.startAutoSync();
        // WebSocket 实时通知（服务器一有变化立即同步；断线自动重连，长轮询兜底）
        SyncWS.start();
        // 网盘Key自动保鲜（cookie 变化就自动推送云端，防止别的设备拉到过期的）
        PanKeys.startAutoPush();
        // 清理上次自更新残留的临时目录
        Update.cleanup();

        int port = Integer.parseInt(System.getProperty("flytv.port", System.getProperty("tvbox.port", "19978")));
        boolean lan = Setting.localServerLan();
        try {
            WebServer.start(port, lan);
        } catch (Exception e) {
            Logger.e("Main", "HTTP 服务启动失败: " + e);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            JarHost.stop();
            Logger.d("Main", "引擎退出");
        }));
        Logger.d("Main", "后台引擎就绪，Web: http://127.0.0.1:" + WebServer.port() + "/web");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
    }
}
