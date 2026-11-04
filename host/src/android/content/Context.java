package android.content;

import java.io.File;

/** android.content.Context 桩：仅提供 TVBox jar 爬虫常用的目录/包名方法。 */
public class Context {

    private File cacheDir = new File(System.getProperty("java.io.tmpdir"), "spider-cache");
    private File filesDir = new File(System.getProperty("java.io.tmpdir"), "spider-files");

    public File getCacheDir() {
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    public File getFilesDir() {
        if (!filesDir.exists()) filesDir.mkdirs();
        return filesDir;
    }

    public File getDir(String name, int mode) {
        File dir = new File(filesDir, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public String getPackageName() { return "com.github.catvod"; }

    public File getExternalFilesDir(String type) { return getDir(type == null ? "external" : type, 0); }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return SharedPreferences.Store.open(name);
    }

    public Object getSystemService(String name) {
        if ("wifi".equals(name)) return new android.net.wifi.WifiManager();
        if ("connectivity".equals(name)) return new android.net.ConnectivityManager();
        if ("window".equals(name)) return new android.view.WindowManager();
        return null;
    }

    public ContentResolver getContentResolver() { return new ContentResolver(); }

    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }

    public android.content.pm.PackageInfo getPackageInfo() { return new android.content.pm.PackageInfo(); }

    public int checkCallingOrSelfPermission(String permission) { return 0; }

    public int checkSelfPermission(String permission) { return 0; }

    public int checkPermission(String permission, int pid, int uid) { return 0; }

    public Context getApplicationContext() { return this; }

    public android.os.Looper getMainLooper() { return android.os.Looper.getMainLooper(); }

    public String getOpPackageName() { return getPackageName(); }

    public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }

    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
}
