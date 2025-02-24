package io.github.xanderwang.asu;

import android.util.Log;

/**
 * log 工具类，用来打印 log
 *
 * @author xanderwang
 * @date: 20220108
 */
public class aLog {

    /**
     * log d
     *
     * @param tag
     * @param msg
     * @param args
     */
    public static void d(String tag, String msg, Object... args) {
        if (aConstants.logLevel <= Log.DEBUG) {
            Log.d(aUtil.logTag(tag), aUtil.format(msg, args));
        }
    }

    /**
     * log d ，同时打印 error
     *
     * @param tag
     * @param msg
     * @param throwable
     */
    public static void d(String tag, String msg, Throwable throwable) {
        if (aConstants.logLevel <= Log.DEBUG) {
            Log.d(aUtil.logTag(tag), msg, throwable);
        }
    }

    /**
     * log w
     *
     * @param tag
     * @param msg
     * @param args
     */
    public static void w(String tag, String msg, Object... args) {
        if (aConstants.logLevel <= Log.WARN) {
            Log.w(aUtil.logTag(tag), aUtil.format(msg, args));
        }
    }

    /**
     * log w
     *
     * @param tag
     * @param msg
     * @param throwable
     */
    public static void w(String tag, String msg, Throwable throwable) {
        if (aConstants.logLevel <= Log.WARN) {
            Log.w(aUtil.logTag(tag), msg, throwable);
        }
    }

    /**
     * log e
     *
     * @param tag
     * @param msg
     * @param args
     */
    public static void e(String tag, String msg, Object... args) {
        if (aConstants.logLevel <= Log.ERROR) {
            Log.e(aUtil.logTag(tag), aUtil.format(msg, args));
        }
    }

    /**
     * log e
     *
     * @param tag
     * @param msg
     * @param throwable
     */
    public static void e(String tag, String msg, Throwable throwable) {
        if (aConstants.logLevel <= Log.ERROR) {
            Log.e(aUtil.logTag(tag), msg, throwable);
        }
    }
}
