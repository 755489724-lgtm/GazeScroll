package android.util;

/**
 * 离线验证用的 Log 桩（v5.35 歪头通道）。
 *
 * TiltDetector 是纯逻辑，只依赖 android.util.Log。把 Log 打成标准输出之后，就能用
 * 缓存在本机的 kotlinc 直接编译并跑真实的检测器代码 —— 不必依赖单元测试框架
 * （Gradle 缓存里没有 junit，离线拉不到）。
 */
public final class Log {
    public static int i(String tag, String msg) {
        System.out.println("    [LOG " + tag + "] " + msg);
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        System.out.println("    [LOG " + tag + "] " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("    [WARN " + tag + "] " + msg);
        return 0;
    }

    public static int e(String tag, String msg) {
        System.out.println("    [ERR " + tag + "] " + msg);
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }
}
