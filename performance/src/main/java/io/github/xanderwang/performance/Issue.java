package io.github.xanderwang.performance;

import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.xanderwang.asu.aLog;

/**
 * @author Xander Wang Created on 2020/12/8.
 * @Description
 */
public class Issue {

    /**
     * log tag
     */
    private static final String TAG = "Issue";
    /**
     * 检测 UI block
     */
    public static final int TYPE_UI_BLOCK = 0;
    /**
     * 检测 FPS
     */
    public static final int TYPE_FPS = 1;
    /**
     * 检测 IPC，进程间通讯
     */
    public static final int TYPE_IPC = 2;
    /**
     * 检测线程的创建
     */
    public static final int TYPE_THREAD = 3;
    /**
     * 图片相关的
     */
    public static final int TYPE_BITMAP = 4;

    /**
     * 任务线程池
     */
    private volatile static ExecutorService taskService = Executors.newSingleThreadExecutor();

    /**
     * 虽然 SimpleDateFormat 是线程不安全的，但是这里只在单线程池里面使用，
     * <p>
     * 所以这样写没有太大的问题
     */
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    /**
     * 类型
     */
    protected int type = -1;
    /**
     * 消息
     */
    protected String msg = "";
    /**
     * 发生的时间
     */
    protected String createTime = "";
    /**
     * 数据
     */
    protected Object data;
    /**
     * byte 数据，用来缓存数据的 string 数组
     */
    protected byte[] dataBytes;

    /**
     * 构造方法
     *
     * @param type 类型
     * @param msg  信息
     * @param data 详细数据
     */
    public Issue(int type, String msg, Object data) {
        this.type = type;
        this.msg = msg;
        createTime = dateFormat.format(new Date());
        this.data = data;
    }

    /**
     * 获取类型
     *
     * @return
     */
    public int getType() {
        return type;
    }

    /**
     * 获取信息
     *
     * @return
     */
    public String getMsg() {
        return msg;
    }

    /**
     * 获取数据
     *
     * @return
     */
    public Object getData() {
        return data;
    }

    /**
     * 设置数据
     *
     * @param data
     */
    public void setData(Object data) {
        this.data = data;
    }

    /**
     * type 格式化为 string
     *
     * @return
     */
    private String formatType() {
        String str = null;
        switch (type) {
            case TYPE_UI_BLOCK:
                str = "UI BLOCK";
                break;
            case TYPE_FPS:
                str = "FPS";
                break;
            case TYPE_IPC:
                str = "IPC";
                break;
            case TYPE_THREAD:
                str = "THREAD";
                break;
            case TYPE_BITMAP:
                str = "BITMAP";
                break;
            default:
                str = "UNKNOWN";
        }
        return str;
    }

    /**
     * 格式化 issue
     */
    private void formatIssue() {
        String issueString = null;
        if (null == dataBytes || dataBytes.length == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n=================================================\n");
            sb.append("type: ").append(formatType()).append('\n');
            sb.append("msg: ").append(msg).append('\n');
            sb.append("create time: ").append(createTime).append('\n');
            formatExtraInfo(sb);
            if (data instanceof List) {
                sb.append("trace:\n");
                formatList(sb, (List) data);
            } else if (null != data) {
                sb.append("data: ").append(data).append('\n');
            }
            issueString = sb.toString();
            dataBytes = issueString.getBytes();
            // data = null; // 释放，节省内存
            log(TAG, issueString);
        } else {
            log(TAG, new String(dataBytes));
        }
    }

    /**
     * 格式化其他的额外信息
     *
     * @param sb
     */
    protected void formatExtraInfo(StringBuilder sb) {
    }

    protected void formatList(StringBuilder sb, List<?> dataList) {
        if (null == dataList || dataList.isEmpty()) {
            return;
        }
        for (int i = 0, len = dataList.size(); i < len; i++) {
            Object item = dataList.get(i);
            sb.append('\t').append(item).append('\n');
        }
    }

    protected void log(String tag, String msg) {
        aLog.w(tag, msg);
    }

    public void print() {
        formatIssue();
        saveIssue(this);
    }

    /**
     * 保存 issue
     *
     * @param issue
     */
    static void saveIssue(Issue issue) {
        executorService().execute(new SaveIssueTask(issue));
    }

    /**
     * 获取线程池
     *
     * @return 线程池
     */
    static ExecutorService executorService() {
        return taskService;
    }

    /**
     * 保存 issue 的 task
     */
    static class SaveIssueTask implements Runnable {

        /**
         * 待保存的 issue
         */
        Issue issue;

        public SaveIssueTask(Issue issue) {
            this.issue = issue;
        }

        @Override
        public void run() {
            if (null == issue || null == issue.dataBytes) {
                return;
            }
            MappedByteBuffer buffer = gMappedByteBuffer();
            if (buffer.remaining() < issue.dataBytes.length) {
                // 空间不够了，另外再创建一个 buffer
                createLogFileAndBuffer();
                buffer = gMappedByteBuffer();
            }
            buffer.put(issue.dataBytes);
            int dataPosition = buffer.position();
            aLog.d(TAG, "SaveIssueTask buffer at:" + dataPosition);
            gLineBytes = String.format(Locale.US, LINE_FORMAT, dataPosition).getBytes();
            buffer.position(0);
            buffer.put(gLineBytes);
            buffer.position(dataPosition);
            issue.dataBytes = null;
        }
    }

    private static String ISSUES_CACHE_DIR_NAME = "perf_issues";

    private static File ISSUES_CACHE_DIR;

    private static PERF.IssueSupplier<File> gCacheDirSupplier = new PERF.IssueSupplier<File>() {
        @Override
        public File get() {
            return AppHelper.appContext().getCacheDir();
        }
    };

    private static PERF.IssueSupplier<Integer> gMaxCacheSizeSupplier = new PERF.IssueSupplier<Integer>() {
        @Override
        public Integer get() {
            return MAX_CACHE_SIZE;
        }
    };

    private static PERF.LogFileUploader logFileUploader = new PERF.LogFileUploader() {
        @Override
        public boolean upload(File logFile) {
            return false;
        }
    };

    private static PERF.IssueSupplier<PERF.LogFileUploader> gUploaderSupplier
        = new PERF.IssueSupplier<PERF.LogFileUploader>() {
        @Override
        public PERF.LogFileUploader get() {
            return logFileUploader;
        }
    };

    private static final int MAX_CACHE_SIZE = 10 * 1024 * 1024;

    private static final int BUFFER_SIZE = 1024 * 1024;

    private static File gLogFile;

    private static RandomAccessFile gRandomAccessFile;

    private static MappedByteBuffer gMappedByteBuffer;

    private static byte[] gLineBytes = String.valueOf(BUFFER_SIZE).getBytes();

    // log 文件的第一行固定为文件最后字节的位置
    private static final int gLineBytesLength = gLineBytes.length;

    private static final String LINE_FORMAT = "%-" + gLineBytesLength + "d";

    /**
     * 初始化
     *
     * @param cacheDir        缓存目录
     * @param maxCacheSize    最大缓存大小
     * @param logFileUploader 文件上传
     */
    protected static void init(PERF.IssueSupplier<File> cacheDir, PERF.IssueSupplier<Integer> maxCacheSize,
        PERF.IssueSupplier<PERF.LogFileUploader> logFileUploader) {
        if (null != cacheDir) {
            gCacheDirSupplier = cacheDir;
        }
        if (null != maxCacheSize) {
            gMaxCacheSizeSupplier = maxCacheSize;
        }
        if (null != logFileUploader) {
            gUploaderSupplier = logFileUploader;
        }
        ISSUES_CACHE_DIR = new File(gCacheDirSupplier.get(), ISSUES_CACHE_DIR_NAME);
        ISSUES_CACHE_DIR.mkdirs();
        aLog.e(TAG, "issues save in:" + ISSUES_CACHE_DIR.getAbsolutePath());
    }

    /**
     * 获取 buffer
     *
     * @return
     */
    protected static MappedByteBuffer gMappedByteBuffer() {
        if (null == gMappedByteBuffer) {
            initMappedByteBuffer();
        }
        return gMappedByteBuffer;
    }

    /**
     * 初始化 log 文件以及 log 对于的 buffer
     */
    protected static void createLogFileAndBuffer() {
        aLog.e(TAG, "createLogFileAndBuffer gBuffer:" + gMappedByteBuffer);
        if (null != gMappedByteBuffer) {
            gMappedByteBuffer.force();
            gMappedByteBuffer = null;
        }
        if (null != gRandomAccessFile) {
            try {
                gRandomAccessFile.close();
            } catch (IOException e) {
                aLog.e(TAG, "gRandomAccessFile IOException", e);
            }
            gRandomAccessFile = null;
        }
        if (null != gLogFile) {
            zipLogFile(gLogFile);
            gLogFile = null;
        }
        // String fileName = "issues_" + SystemClock.elapsedRealtimeNanos() + ".log";
        String fileName = "issues_" + dateFormat.format(new Date()) + ".log";
        try {
            gLogFile = new File(ISSUES_CACHE_DIR, fileName);
            if (gLogFile.exists()) {
                gLogFile.delete();
            }
            gLogFile.createNewFile();
            aLog.e(TAG, "create log file :" + gLogFile.getAbsolutePath());
            gRandomAccessFile = new RandomAccessFile(gLogFile.getAbsolutePath(), "rw");
            gMappedByteBuffer = gRandomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_SIZE);
            // 写入 line
            gLineBytes = String.format(Locale.US, LINE_FORMAT, 0).getBytes();
            gMappedByteBuffer.put(gLineBytes);
        } catch (Exception e) {
            aLog.e(TAG, "gRandomAccessFile IOException", e);
        }
        deleteOldFiles();
    }

    /**
     * 初始化 log 的 buffer
     */
    protected static synchronized void initMappedByteBuffer() {
        // 遍历保存文件夹，按照创建时间排序，
        // 只处理 log 文件，然后第一个 log 文件是上一次创建的，并初始化全局的 log file
        // 其他的 log 文件做压缩处理，最后做一次空间清理
        if (taskService == null) {
            taskService = Executors.newSingleThreadExecutor();
        }
        File[] files = ISSUES_CACHE_DIR.listFiles();
        if (null == files || files.length == 0) {
            createLogFileAndBuffer();
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File fileA, File fileB) {
                // 结果为负数表示 fileB 排在前面，也就是说后创建的文件在前面
                return (int) (fileB.lastModified() - fileA.lastModified());
            }
        });
        // List<File> waitToZipLogFiles = new ArrayList<>();
        File lastLogFile = null;
        for (int i = 0; i < files.length; i++) {
            final File file = files[i];
            if (file.isDirectory() || !file.isFile()) {
                continue;
            }
            if (file.getName().endsWith(".log")) {
                if (lastLogFile == null) {
                    lastLogFile = file;
                    continue;
                }
                zipLogFile(file);
            } else if (file.getName().endsWith(".zip")) {
                // 开始上传 log 文件
                Runnable uploadRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (doUploadZipLogFile(file)) {
                            try {
                                file.delete();
                            } catch (Exception e) {
                                aLog.e(TAG, "initMappedByteBuffer", e);
                            }
                        }
                    }
                };
                // fixme 上传文件可以考虑用另外的线程来上传，暂时放到这里
                executorService().execute(uploadRunnable);
            }
        }
        aLog.e(TAG, "initMappedByteBuffer lastLogFile:" + lastLogFile);
        if (null != lastLogFile) {
            readLogInfo(lastLogFile);
        } else {
            createLogFileAndBuffer();
        }
    }

    /**
     * 读取 log 信息
     *
     * @param logFile
     */
    protected static void readLogInfo(File logFile) {
        // 处理 last log file 为全局的 log file
        try {
            gLogFile = logFile;
            gRandomAccessFile = new RandomAccessFile(logFile.getAbsolutePath(), "rw");
            gMappedByteBuffer = gRandomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_SIZE);
            gMappedByteBuffer.get(gLineBytes);// 读取行号记录
            String gLineString = new String(gLineBytes).trim();
            int lastPosition = 0;
            if (TextUtils.isEmpty(gLineString)) {
                // 新创建出来文件就立刻 crash 了，导致之前没有写入任何内容
                gLineBytes = String.format(Locale.US, LINE_FORMAT, 0).getBytes();
                gMappedByteBuffer.put(gLineBytes);// 写入 0
                lastPosition = gMappedByteBuffer().position();
            } else {
                lastPosition = Integer.parseInt(gLineString);
            }
            aLog.w(TAG, "initMappedByteBuffer lastPosition:" + lastPosition);
            if (lastPosition >= BUFFER_SIZE) {
                createLogFileAndBuffer();
            } else {
                gMappedByteBuffer.position(lastPosition);
            }
            deleteOldFiles();// 尝试删除超出磁盘缓存的 log 文件
        } catch (IOException e) {
            aLog.e(TAG, "initMappedByteBuffer", e);
            createLogFileAndBuffer();
        }
    }

    /**
     * 压缩 log 文件
     *
     * @param logFile
     */
    protected static void zipLogFile(final File logFile) {
        // 压缩 log 文件，成功后删除原始 log 文件
        // 上传成功后删除压缩后的 log file
        aLog.e(TAG, "zipLogFile:" + logFile);
        executorService().submit(new Runnable() {
            @Override
            public void run() {
                File zipLogFile = doZipLogFile(logFile);
                boolean uploadSuccess = doUploadZipLogFile(zipLogFile);
                if (uploadSuccess) {
                    try {
                        zipLogFile.delete();
                    } catch (Exception e) {
                        aLog.d(TAG, "delete log file" + zipLogFile.getAbsolutePath(), e);
                    }
                }
            }
        });
    }

    /**
     * 执行压缩 log 文件，并删除
     *
     * @param logFile
     * @return
     */
    private static File doZipLogFile(File logFile) {
        File zipLogFileDir = logFile.getParentFile();
        String zipLogFileName = logFile.getName().replace(".log", ".zip");
        File zipLogFile = new File(zipLogFileDir, zipLogFileName);
        if (zipLogFile.exists()) {
            // 如果 zip 文件和 log 文件都存在，说明有异常了，重新压缩一遍
            try {
                zipLogFile.delete();
            } catch (Exception e) {
                aLog.d(TAG, "delete log file:" + logFile.getAbsolutePath(), e);
            }
        }
        try {
            aLog.e(TAG, "doZipLogFile src:" + logFile.getAbsolutePath());
            aLog.e(TAG, "doZipLogFile dst:" + zipLogFile.getAbsolutePath());
            FileOutputStream fos = new FileOutputStream(zipLogFile);
            ZipOutputStream zop = new ZipOutputStream(fos);
            ZipEntry zipEntry = new ZipEntry(logFile.getName());
            zop.putNextEntry(zipEntry);
            byte[] bytes = new byte[1024 * 64];
            int length;
            FileInputStream fip = new FileInputStream(logFile);
            while ((length = fip.read(bytes)) >= 0) {
                zop.write(bytes, 0, length);
            }
            zop.closeEntry();
            zop.close();
            fos.close();
            fip.close();
            logFile.delete();
        } catch (Exception e) {
            e.printStackTrace();
            aLog.d(TAG, "zip log file:" + logFile.getAbsolutePath(), e);
        }
        return zipLogFile;
    }

    /**
     * 上传 log 文件
     *
     * @param zipLogFile
     * @return
     */
    private static boolean doUploadZipLogFile(File zipLogFile) {
        if (zipLogFile == null || !zipLogFile.exists()) {
            return false;
        }
        return gUploaderSupplier.get().upload(zipLogFile);
    }

    /**
     * 删除旧的文件
     */
    private static void deleteOldFiles() {
        // .log 文件忽略，然后按时间排序，然后删除
        final long maxCacheSize = gMaxCacheSizeSupplier.get();
        Runnable deleteRunnable = new Runnable() {
            @Override
            public void run() {
                File[] files = ISSUES_CACHE_DIR.listFiles();
                if (null == files || files.length == 0) {
                    return;
                }
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File fileA, File fileB) {
                        // 结果为负数表示 fileB 排在前面，也就是说后创建的文件在前面
                        return (int) (fileB.lastModified() - fileA.lastModified());
                    }
                });
                long fileLength = 0;
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (!(file.isFile() && file.getName().endsWith("zip"))) {
                        continue;
                    }
                    if (fileLength >= maxCacheSize) {
                        file.delete();
                    } else {
                        fileLength += file.length();
                    }
                }
            }
        };
        taskService.submit(deleteRunnable);
    }
}
