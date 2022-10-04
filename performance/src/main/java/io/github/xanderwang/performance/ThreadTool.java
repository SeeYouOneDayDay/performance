package io.github.xanderwang.performance;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import io.github.xanderwang.asu.aLog;
import io.github.xanderwang.hook.HookBridge;
import io.github.xanderwang.hook.core.MethodHook;
import io.github.xanderwang.hook.core.MethodParam;

import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @ProjectName: performance
 * @Package: com.xander.performance
 * @ClassName: ThreadTool
 * @Description: 通过 hook 类的方法来监听线程的创建、启动，线程池的创建和执行任务
 * <p>
 * 监听线程的创建、启动：主要原理就是通过 hook ，在调用线程构造方法和 start 方法的时候，保存调用栈。
 * 监听线程池的创建: 主要原理就是通过 hook，在线程池构造方法被调用的时候，保存调用栈。
 * 建立线程池和线程池创建的线程的关联：查阅源码，线程池创建线程，实际是通过其内部类 Worker 来创建的，
 * 由于是内部类，所以实际 Worker 类实例和线程池的实例可以建立一个关联，Worker 创建线程的时候，也有一个关联，
 * 最后通过 Worker 这个纽带，就可以把线程池和线程池创建的线程关联起来监控。
 * @Author: Xander
 * @CreateDate: 2020/4/13 22:30
 * @Version: 1.0
 */
class ThreadTool {

    private static final String TAG = "ThreadTool";

    static class ThreadIssue extends Issue {
        String key; // 线程 key ，线程实例的 hashCode
        String threadPoolKey; // 与之对应的线程池的 key ,如果不是线程池创建的线程，取值为 null
        boolean lostCreateTrace = false; // 是否丢失创建实例调用栈，如果库初始化比较晚，可能会出现没有创建实例调用栈
        String threadName;
        List<String> createTrace; // 创建实例调用栈
        List<String> startTrace;  // 启动线程调用栈

        public ThreadIssue(String msg) {
            super(Issue.TYPE_THREAD, msg, null);
        }

        @Override
        protected void formatExtraInfo(StringBuilder sb) {
            sb.append("thread name: ").append(threadName).append("\n");
            if (!lostCreateTrace) {
                sb.append("thread create trace:\n");
                formatList(sb, createTrace);
            }
            sb.append("thread start trace:\n");
            formatList(sb, startTrace);
        }
    }

    static class ThreadPoolIssue extends Issue {
        String key; // 线程池 key ，线程池实例的 hashCode
        boolean lostCreateTrace = false; // 是否丢失创建线程池实例的调用栈，如果库初始化比较晚，可能会出现没有创建实例调用栈
        List<String> createTrace; // 创建线程池实例的调用栈
        List<ThreadIssue> childThreadList = new ArrayList<>(); // 创建的线程，如果线程销毁后，会自动删除

        public ThreadPoolIssue(String msg) {
            super(Issue.TYPE_THREAD, msg, null);
        }

        @Override
        protected void formatExtraInfo(StringBuilder sb) {
            if (!lostCreateTrace) {
                sb.append("thread pool create trace:\n");
            } else {
                // 这种情况下，用某个线程的创建栈来代替，尽量输出一些信息
                sb.append("one thread create trace:\n");
            }
            formatList(sb, createTrace);
        }

        void removeThreadInfo(ThreadIssue threadIssues) {
            synchronized (this) {
                childThreadList.remove(threadIssues);
            }
        }

        void addThreadInfo(ThreadIssue threadIssues) {
            synchronized (this) {
                childThreadList.add(threadIssues);
            }
        }

        boolean isEmpty() {
            return childThreadList.isEmpty();
        }
    }

    static class ThreadTraceIssue extends Issue {
        String threadName;

        public ThreadTraceIssue(String msg, Object data) {
            super(Issue.TYPE_THREAD, msg, data);
        }

        @Override
        protected void formatExtraInfo(StringBuilder sb) {
            sb.append("thread name: ").append(threadName).append("\n");
        }
    }

    /**
     * thread 的信息，包括线程池里面创建的，可以通过 ThreadInfo.threadPoolInfoKey 字段判断是否为线程池创建
     */
    static ConcurrentHashMap<String, ThreadIssue> threadMap = new ConcurrentHashMap<>(64);

    /**
     * 线程池信息
     */
    static ConcurrentHashMap<String, ThreadPoolIssue> threadPoolMap = new ConcurrentHashMap<>(32);

    /**
     * worker 和 thread pool 的关联，用于绑定 thread 和 thread pool 的关联
     */
    static ConcurrentHashMap<String, String> workerThreadPoolMap = new ConcurrentHashMap<>(32);

    static Field THREAD_TARGET = null;

    static Field WORKER_THREAD = null;

    static Handler threadTraceHandler; // 主线程阻塞的话，可能会导致实际执行时刻和期望执行时刻不同步

    static Map<String, DumpThreadTraceTask> dumpTaskMap = new HashMap<>();

    /**
     * 保存线程池创建信息
     *
     * @param threadPoolKey
     * @param msg
     * @param createTrace
     */
    private static void saveThreadPoolCreateInfo(String threadPoolKey, String msg, StackTraceElement[] createTrace) {
        if (threadPoolMap.containsKey(threadPoolKey)) {
            return;
        }
        // 开始记录信息
        ThreadPoolIssue threadPoolIssues = new ThreadPoolIssue(msg);
        threadPoolIssues.key = threadPoolKey;
        threadPoolIssues.createTrace = StackTraceUtils.list(createTrace);
        threadPoolMap.put(threadPoolKey, threadPoolIssues);
        threadPoolIssues.print();
    }

    /**
     * 关联线程池和 worker
     *
     * @param threadPoolKey
     * @param workerKey
     */
    private static void linkThreadPoolAndWorker(String threadPoolKey, String workerKey) {
        workerThreadPoolMap.put(workerKey, threadPoolKey);
    }

    /**
     * 线程的创建信息，这个暂时可以不用关系，因为后续会关心 start
     *
     * @param threadKey
     * @param workerKey
     * @param createTrace
     */
    @Deprecated
    private static void saveThreadCreateInfo(String threadKey, String workerKey, StackTraceElement[] createTrace) {
        ThreadIssue threadInfo = new ThreadIssue("THREAD CREATE");
        threadInfo.key = threadKey;
        threadInfo.createTrace = StackTraceUtils.list(createTrace);
        boolean isInThreadPool = workerThreadPoolMap.containsKey(workerKey);
        aLog.w(TAG, "saveThreadCreateInfo: is in thread pool:%s", isInThreadPool);
        // aLog.d(TAG, "saveThreadCreateInfo: createTrace:%s", StackTraceUtils.list(createTrace));
        if (isInThreadPool) {
            // 线程池创建的线程
            String threadPoolKey = workerThreadPoolMap.get(workerKey);
            ThreadPoolIssue threadPoolInfo = threadPoolMap.get(threadPoolKey);
            if (threadPoolInfo == null) {
                // 部分情况下，比如库初始化的比较晚，部分线程池已经创建了，就会出现线程创建时没有对应的线程池
                // 这里用线程的创建调用链来代替线程池的创建调用链，多少有些参考价值
                threadPoolInfo = new ThreadPoolIssue("THREAD POOL LOST CREATE INFO!!!");
                threadPoolInfo.key = threadPoolKey;
                threadPoolInfo.lostCreateTrace = true;
                threadPoolInfo.createTrace = StackTraceUtils.list(createTrace);
                threadPoolMap.put(threadPoolKey, threadPoolInfo);
                threadPoolInfo.print();
            }
            threadInfo.threadPoolKey = threadPoolKey;
            threadPoolInfo.addThreadInfo(threadInfo);
            // 建立 thread 和 thread pool 的关联后，断开 worker 和 thread pool 的关联
            // 因为，Worker 只创建一个 Thread
            workerThreadPoolMap.remove(workerKey);
        }
        // 需要？
        threadMap.put(threadKey, threadInfo);
    }

    /**
     * 关联线程池和线程池创建的线程
     *
     * @param threadKey
     * @param workerKey
     * @param createTrace
     */
    private static void linkThreadAndThreadPool(String threadKey, String workerKey, StackTraceElement[] createTrace) {
        ThreadIssue threadInfo = new ThreadIssue("THREAD CREATE");
        threadInfo.lostCreateTrace = true;
        threadInfo.key = threadKey;
        boolean isInThreadPool = workerThreadPoolMap.containsKey(workerKey);
        aLog.w(TAG, "linkThreadAndThreadPool: thread is in thread pool:%s", isInThreadPool);
        if (isInThreadPool) {
            // 线程池创建的线程
            String threadPoolKey = workerThreadPoolMap.get(workerKey);
            ThreadPoolIssue threadPoolInfo = threadPoolMap.get(threadPoolKey);
            if (threadPoolInfo == null) {
                // 部分情况下，比如库初始化的比较晚，部分线程池已经创建了，就会出现线程创建时没有对应的线程池
                // 这里用线程的创建调用链来代替线程池的创建调用链，多少有些参考价值
                threadPoolInfo = new ThreadPoolIssue("THREAD POOL LOST CREATE INFO!!!");
                threadPoolInfo.key = threadPoolKey;
                threadPoolInfo.lostCreateTrace = true;
                if (null != createTrace) {
                    threadPoolInfo.createTrace = StackTraceUtils.list(createTrace);
                }
                threadPoolMap.put(threadPoolKey, threadPoolInfo);
                threadPoolInfo.print();
            }
            threadInfo.threadPoolKey = threadPoolKey;
            threadPoolInfo.addThreadInfo(threadInfo);
            // 建立 thread 和 thread pool 的关联后，断开 worker 和 thread pool 的关联
            // 因为，Worker 只创建一个 Thread
            workerThreadPoolMap.remove(workerKey);
        }
        threadMap.put(threadKey, threadInfo);
    }

    /**
     * 线程启动了
     *
     * @param threadKey
     * @param threadName
     * @param startTrace
     */
    private static void saveStartThreadInfo(String threadKey, String threadName, StackTraceElement[] startTrace) {
        ThreadIssue threadInfo = threadMap.get(threadKey);
        if (null == threadInfo) {
            aLog.e(TAG, "can not find thread info when thread start !!!!!!");
            threadInfo = new ThreadIssue("THREAD CREATE LOST CREATE INFO");
            threadInfo.key = threadKey;
            threadInfo.lostCreateTrace = true;
            threadMap.put(threadKey, threadInfo);
        }
        threadInfo.threadName = threadName;
        if (TextUtils.isEmpty(threadInfo.threadPoolKey)) {
            // 非线程池创建的线程才打印启动堆栈
            threadInfo.startTrace = StackTraceUtils.list(startTrace);
            threadInfo.print();
        } else {
            // 线程池创建的线程，这里暂时不做任何事情
            aLog.e(TAG, "a thread pool created thread start !!!!!!");
        }
    }

    /**
     * 清除一些信息
     *
     * @param threadKey
     */
    private static void clearInfoWhenExitThread(String threadKey) {
        aLog.d(TAG, "clear info when exit thread, threadKey:%s", threadKey);
        ThreadIssue threadInfo = threadMap.remove(threadKey);
        if (null == threadInfo) {
            aLog.e(TAG, "can not find thread info when exit thread!!!");
            return;
        }
        aLog.e(TAG, "clear info when exit thread, thread name:%s", threadInfo.threadName);
        aLog.e(TAG, "clear info when exit thread, running thread count:%s", threadMap.size());
        if (TextUtils.isEmpty(threadInfo.threadPoolKey)) {
            return;
        }
        ThreadPoolIssue threadPool = threadPoolMap.get(threadInfo.threadPoolKey);
        if (null == threadPool) {
            aLog.e(TAG, "can not find thread pool info when exit thread!!!");
            return;
        }
        threadPool.removeThreadInfo(threadInfo);
        if (threadPool.isEmpty()) {
            threadPoolMap.remove(threadInfo.threadPoolKey);
        }
        aLog.e(TAG, "clear info when exit thread, thread pool count:%s", threadPoolMap.size());
    }

    /**
     * 线程开始执行
     *
     * @param thread
     */
    private static void threadRunStart(Thread thread) {
        if (null == thread) {
            return;
        }
        String threadKey = Integer.toHexString(thread.hashCode());
        if (dumpTaskMap.containsKey(threadKey)) {
            // 这种情况会在 kt 里面创建线程的时候发生，目前发现到的是 kt 代码创建的线程貌似会缓存起来。
            // kt 创建的线程的 `run 的方法块`执行完后，不会立即结束 thread 的 run 方法。 kt 会缓存线程下来。
            // 所以如果是 kt 的线程`run 的方法块`执行了，就移除之前的信息，保证准确性。
            DumpThreadTraceTask oldTask = dumpTaskMap.remove(threadKey);
            threadTraceHandler.removeCallbacks(oldTask);
        }
        DumpThreadTraceTask dumpTask = new DumpThreadTraceTask(Thread.currentThread());
        dumpTaskMap.put(threadKey, dumpTask);
        threadTraceHandler.postDelayed(dumpTask, Config.THREAD_BLOCK_TIME);
        aLog.e(TAG, "threadRunStart:dumpTaskMap size:%s,delayTime:%s", dumpTaskMap.size(), Config.THREAD_BLOCK_TIME);
    }

    /**
     * 线程执行结束
     *
     * @param thread
     */
    private static void threadRunEnd(Thread thread) {
        if (null == thread) {
            return;
        }
        String threadKey = Integer.toHexString(thread.hashCode());
        DumpThreadTraceTask dumpTask = dumpTaskMap.remove(threadKey);
        threadTraceHandler.removeCallbacks(dumpTask);
        if (null == dumpTask) {
            aLog.e(TAG, "RunnableRunHook afterHookedMethod null task!!!", new Throwable());
        }
    }

    /**
     * 线程优先级改变
     */
    private static void threadPriorityChanged() {
        //Priority
        ThreadTraceIssue traceIssue = new ThreadTraceIssue("THREAD PRIORITY CHANGED TRACE",
            StackTraceUtils.list(Thread.currentThread()));
        traceIssue.threadName = Thread.currentThread().getName();
        traceIssue.print();
    }

    static void init() {
        aLog.e(TAG, "init");
        threadTraceHandler = new Handler(AppHelper.getPerfLooper());
        hookThread();
    }

    public static void hookThread() {
        // hook 7 个参数的构造方法好像会报错，故 hook 指定参数数目的构造方法
        ThreadPoolExecutorConstructorHook constructorHook = new ThreadPoolExecutorConstructorHook();
        Constructor<?>[] constructors = ThreadPoolExecutor.class.getDeclaredConstructors();
        for (int i = 0; i < constructors.length; i++) {
            if (constructors[i].getParameterTypes().length <= 6) {
                // 7 个参数的构造方法，貌似 hook 有问题，暂时不 hook。但是这里还是有些问题的 fixme
                HookBridge.hookMethod(constructors[i], constructorHook);
            }
        }

        try {
            // java.util.concurrent.ThreadPoolExecutor$Worker 是一个内部类，
            // 所以构造方法第一参数就是 ThreadPoolExecutor, 所以构造方法可以将 Worker 和 线程池绑定
            // java.util.concurrent.ThreadPoolExecutor$Worker
            Class<?> workerClass = Class.forName("java.util.concurrent.ThreadPoolExecutor$Worker");
            WORKER_THREAD = workerClass.getDeclaredField("thread");
            HookBridge.hookAllConstructors(workerClass, new WorkerConstructorHook());
        } catch (Exception e) {
            aLog.e(TAG, "java.util.concurrent.ThreadPoolExecutor$Worker", e);
        }

        // 根据构造方法里面的 runnable 是否为 Worker 可知是否为线程池创建的线程。
        //    HookBridge.hookAllConstructors(Thread.class, new ThreadConstructorHook());
        //    HookBridge.findAndHookMethod(Thread.class, "init", new ThreadConstructorHook());
        //    aLog.e(TAG, "findAndHookMethod Thread.init");

        try {
            THREAD_TARGET = Thread.class.getDeclaredField("target");
        } catch (Exception e) {
            aLog.e(TAG, "THREAD_TARGET error", e);
        }

        // start 方法表示
        HookBridge.findAndHookMethod(Thread.class, "start", new ThreadStartHook());

        // run 方法执行完，表示线程执行完。可以考虑在里面做一些清理工作，目前发现还是有问题
        HookBridge.findAndHookMethod(Thread.class, "run", new ThreadRunHook());
        try {
            // kotlin 的线程执行和 java 的不一致，这里需要做个区分
            Class<?> ktThreadRunnable = Class.forName("kotlin.concurrent.ThreadsKt$thread$thread$1");
            HookBridge.findAndHookMethod(ktThreadRunnable, "run", new RunnableRunHook());
        } catch (ClassNotFoundException e) {
            aLog.e(TAG, "kotlin.concurrent.ThreadsKt$thread$thread$1", e);
        }

        // hook 优先级
        HookBridge.findAndHookMethod(Thread.class, "setPriority", int.class, new ThreadSetPriorityHook());

    /*HookBridge.findAndHookMethod(
        Process.class,
        "setThreadPriority",
        int.class,
        new ProcessSetThreadPriorityHook()
    );*/

    }

    static class ThreadPoolExecutorConstructorHook extends MethodHook {

        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            Object threadPool = param.getThisObject();
            String threadPoolInfoKey = Integer.toHexString(threadPool.hashCode());
            aLog.e(TAG, "ThreadPoolExecutorConstructorHook beforeHookedMethod:%s", Arrays.toString(param.getArgs()));
            // aLog.d(
            //     TAG,
            //     "ThreadPoolExecutorConstructorHook beforeHookedMethod threadPoolInfoKey:%s",
            //     threadPoolInfoKey
            // );
            saveThreadPoolCreateInfo(threadPoolInfoKey, "THREAD POOL CREATE", new Throwable().getStackTrace());
        }
    }

    static class WorkerConstructorHook extends MethodHook {
        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            // aLog.w(TAG, "WorkerConstructorHook beforeHookedMethod:%s", Arrays.toString(param.getArgs()));
            String workerKey = Integer.toHexString(param.getThisObject().hashCode());
            String threadPoolKey = Integer.toHexString(param.getArgs()[0].hashCode());
            // aLog.w(TAG, "WorkerConstructorHook beforeHookedMethod workerKey: %s", workerKey);
            // aLog.w(TAG, "WorkerConstructorHook beforeHookedMethod threadPoolKey:%s", threadPoolKey);
            linkThreadPoolAndWorker(threadPoolKey, workerKey);
        }

        @Override
        public void afterHookedMethod(MethodParam param) throws Throwable {
            super.afterHookedMethod(param);
            // 关联 thread 和 worker
            if (null != WORKER_THREAD) {
                WORKER_THREAD.setAccessible(true);
                Object thread = WORKER_THREAD.get(param.getThisObject());
                if (thread != null) {
                    String threadKey = Integer.toHexString(thread.hashCode());
                    String workerKey = Integer.toHexString(param.getThisObject().hashCode());
                    linkThreadAndThreadPool(threadKey, workerKey, null);
                }
            } else {
                aLog.e(TAG, "WorkerConstructorHook afterHookedMethod WORKER_THREAD is null!!!");
            }
        }
    }

    static class ThreadConstructorHook extends MethodHook {

        @Override
        public void afterHookedMethod(MethodParam param) throws Throwable {
            aLog.d(TAG, "ThreadConstructorHook afterHookedMethod:%s", Arrays.toString(param.getArgs()));
            Thread cThread = (Thread) param.getThisObject();
            String threadKey = Integer.toHexString(cThread.hashCode());
            // 获取 workerKey
            String workerKey = "";
            Object[] args = param.getArgs();
            for (int i = 0; i < args.length; i++) {
                String argClassName = args[i].getClass().getName();
                //         aLog.e(TAG, "ThreadConstructorHook afterHookedMethod arg class name:%s", argClassName);
                if ("java.util.concurrent.ThreadPoolExecutor$Worker".equals(argClassName)) {
                    aLog.w(TAG, "ThreadConstructorHook afterHookedMethod find worker");
                    workerKey = Integer.toHexString(args[i].hashCode());
                }
            }
            aLog.w(TAG, "ThreadConstructorHook afterHookedMethod workerKey:%s", workerKey);
            saveThreadCreateInfo(threadKey, workerKey, new Throwable().getStackTrace());
        }
    }

    static class ThreadStartHook extends MethodHook {

        // @Override
        // public void beforeHookedMethod(MethodParam param) throws Throwable {
        //   super.beforeHookedMethod(param);
        //   Thread cThread = (Thread) param.getThisObject();
        //   String threadKey = Integer.toHexString(cThread.hashCode());
        //   String runnableClassName = "";
        //   int runnableHashCode = 0;
        //   String workerKey = "";
        //   // 看 target 是否为 worker 开判断是否为线程池创建的
        //   if (null != THREAD_TARGET) {
        //     THREAD_TARGET.setAccessible(true);
        //     Object target = THREAD_TARGET.get(cThread);
        //     if (null != target) {
        //       runnableClassName = target.getClass().getName();
        //       runnableHashCode = target.hashCode();
        //     }
        //   }
        //   aLog.e(TAG, "ThreadStartHook beforeHookedMethod runnable class name:%s", runnableClassName);
        //   if ("java.util.concurrent.ThreadPoolExecutor$Worker".equals(runnableClassName)) {
        //     workerKey = Integer.toHexString(runnableHashCode);
        //   }
        //   // linkThreadAndThreadPool(threadKey, workerKey, new Throwable().getStackTrace());
        // }

        @Override
        public void afterHookedMethod(MethodParam param) throws Throwable {
            // aLog.d(TAG, "ThreadStartHook afterHookedMethod:%s", Arrays.toString(param.getArgs()));
            Thread cThread = (Thread) param.getThisObject();
            // Thread cThread = Thread.currentThread();
            String threadKey = Integer.toHexString(cThread.hashCode());
            saveStartThreadInfo(threadKey, cThread.getName(), new Throwable().getStackTrace());
        }
    }

    static class ThreadRunHook extends MethodHook {

        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            // super.beforeHookedMethod(param);
            Thread cThread = Thread.currentThread();
            if (cThread == Looper.getMainLooper().getThread()) {
                aLog.d(TAG, "ThreadRunHook beforeHookedMethod in main thread");
                return;
            }
            threadRunStart(cThread);
        }

        @Override
        public void afterHookedMethod(MethodParam param) throws Throwable {
            aLog.d(TAG, "ThreadRunHook afterHookedMethod");
            // super.afterHookedMethod(param);
            Thread cThread = (Thread) param.getThisObject();
            threadRunEnd(cThread);
            String threadKey = Integer.toHexString(cThread.hashCode());
            clearInfoWhenExitThread(threadKey);
        }
    }

    static class RunnableRunHook extends MethodHook {
        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            // aLog.d(TAG, "RunnableRunHook beforeHookedMethod:%s", param.getThisObject());
            // aLog.d(TAG, "RunnableRunHook beforeHookedMethod currentThread:%s", Thread.currentThread());
            // aLog.d(TAG, "RunnableRunHook beforeHookedMethod MainLooper:%s", Looper.getMainLooper().getThread());
            Thread cThread = Thread.currentThread();
            if (cThread == Looper.getMainLooper().getThread()) {
                aLog.d(TAG, "RunnableRunHook beforeHookedMethod in main thread");
                return;
            }
            threadRunStart(cThread);
        }

        @Override
        public void afterHookedMethod(MethodParam param) throws Throwable {
            // aLog.d(TAG, "RunnableRunHook afterHookedMethod:%s", param.getThisObject());
            // aLog.d(TAG, "RunnableRunHook afterHookedMethod currentThread:%s", Thread.currentThread());
            // aLog.d(TAG, "RunnableRunHook afterHookedMethod MainLooper:%s", Looper.getMainLooper().getThread());
            Thread cThread = Thread.currentThread();
            if (cThread == Looper.getMainLooper().getThread()) {
                aLog.d(TAG, "RunnableRunHook afterHookedMethod in main thread");
                return;
            }
            threadRunEnd(cThread);
        }
    }

    // java.lang.Thread.setPriority
    static class ThreadSetPriorityHook extends MethodHook {
        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            super.beforeHookedMethod(param);
            threadPriorityChanged();
        }
    }

    // android.os.Process.setThreadPriority
    static class ProcessSetThreadPriorityHook extends MethodHook {
        @Override
        public void beforeHookedMethod(MethodParam param) throws Throwable {
            super.beforeHookedMethod(param);
            threadPriorityChanged();
        }
    }

    /**
     * 打印某个线程调用栈的任务
     */
    static class DumpThreadTraceTask implements Runnable {

        SoftReference<Thread> threadRef;

        public DumpThreadTraceTask(Thread thread) {
            this.threadRef = new SoftReference<>(thread);
        }

        @Override
        public void run() {
            if (null == threadRef) {
                return;
            }
            Thread thread = threadRef.get();
            if (null == thread) {
                return;
            }
            // aLog.e(TAG, "DumpThreadTraceTask:%s", threadRef.get());
            ThreadTraceIssue traceIssue = new ThreadTraceIssue("THREAD RUN BLOCK TRACE", StackTraceUtils.list(thread));
            traceIssue.threadName = thread.getName();
            traceIssue.print();
            threadRef = null;
        }
    }

}
