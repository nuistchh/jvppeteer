package com.ruiyun.jvppeteer.cdp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruiyun.jvppeteer.cdp.entities.FetcherOptions;
import com.ruiyun.jvppeteer.cdp.entities.RevisionInfo;
import com.ruiyun.jvppeteer.common.BrowserRevision;
import com.ruiyun.jvppeteer.common.ChromeReleaseChannel;
import com.ruiyun.jvppeteer.common.Product;
import com.ruiyun.jvppeteer.exception.JvppeteerException;
import com.ruiyun.jvppeteer.util.FileUtil;
import com.ruiyun.jvppeteer.util.Helper;
import com.ruiyun.jvppeteer.util.StreamUtil;
import com.ruiyun.jvppeteer.util.StringUtil;
import com.ruiyun.jvppeteer.util.ValidateUtil;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static com.ruiyun.jvppeteer.common.Constant.*;

/**
 * 浏览器下载器
 */
public class BrowserFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserFetcher.class);

    private static final String LINUX = "linux64";
    private static final String MAC_ARM64 = "mac-arm64";
    private static final String MAC_X64 = "mac-x64";
    private static final String WIN32 = "win32";
    private static final String WIN64 = "win64";
    private final Product product;
    private final String platform;
    private final String downloadHost;
    private String downloadsFolder;
    private final String revision;

    private static final Map<Product, Map<String, String>> downloadURLs = new HashMap<>();

    static {
        Map<String, String> chrome = new HashMap<>();
        chrome.put("host", "https://storage.googleapis.com");
        chrome.put(LINUX, "%s/chrome-for-testing-public/%s/linux64/%s.zip");
        chrome.put(MAC_ARM64, "%s/chrome-for-testing-public/%s/mac-arm64/%s.zip");
        chrome.put(MAC_X64, "%s/chrome-for-testing-public/%s/mac-x64/%s.zip");
        chrome.put(WIN32, "%s/chrome-for-testing-public/%s/win32/%s.zip");
        chrome.put(WIN64, "%s/chrome-for-testing-public/%s/win64/%s.zip");
        downloadURLs.put(Product.Chrome, chrome);

        Map<String, String> chromium = new HashMap<>();
        chromium.put("host", "https://storage.googleapis.com");
        chromium.put(LINUX, "%s/chromium-browser-snapshots/Linux_x64/%s/%s.zip");
        chromium.put(MAC_ARM64, "%s/chromium-browser-snapshots/Mac_Arm/%s/%s.zip");
        chromium.put(MAC_X64, "%s/chromium-browser-snapshots/Mac/%s/%s.zip");
        chromium.put(WIN32, "%s/chromium-browser-snapshots/Win/%s/%s.zip");
        chromium.put(WIN64, "%s/chromium-browser-snapshots/Win_x64/%s/%s.zip");
        downloadURLs.put(Product.Chromium, chromium);


        Map<String, String> chromedriver = new HashMap<>();
        chromedriver.put("host", "https://storage.googleapis.com");
        chromedriver.put(LINUX, "%s/chrome-for-testing-public/%s/linux64/%s.zip");
        chromedriver.put(MAC_ARM64, "%s/chrome-for-testing-public/%s/mac-arm64/%s.zip");
        chromedriver.put(MAC_X64, "%s/chrome-for-testing-public/%s/mac-x64/%s.zip");
        chromedriver.put(WIN32, "%s/chrome-for-testing-public/%s/win32/%s.zip");
        chromedriver.put(WIN64, "%s/chrome-for-testing-public/%s/win64/%s.zip");
        downloadURLs.put(Product.Chromedriver, chromedriver);

        Map<String, String> chrome_headless_shell = new HashMap<>();
        chrome_headless_shell.put("host", "https://storage.googleapis.com");
        chrome_headless_shell.put(LINUX, "%s/chrome-for-testing-public/%s/linux64/%s.zip");
        chrome_headless_shell.put(MAC_ARM64, "%s/chrome-for-testing-public/%s/mac-arm64/%s.zip");
        chrome_headless_shell.put(MAC_X64, "%s/chrome-for-testing-public/%s/mac-x64/%s.zip");
        chrome_headless_shell.put(WIN32, "%s/chrome-for-testing-public/%s/win32/%s.zip");
        chrome_headless_shell.put(WIN64, "%s/chrome-for-testing-public/%s/win64/%s.zip");
        downloadURLs.put(Product.Chrome_headless_shell, chrome_headless_shell);

        Map<String, String> firefox = new HashMap<>();
        firefox.put("host", "http://archive.mozilla.org/pub/firefox/releases");
        firefox.put(LINUX, "%s/%s/linux-x86_64/en-US/%s.tar.bz2");
        firefox.put(MAC_ARM64, "%s/%s/mac/en-US/%s.dmg");
        firefox.put(MAC_X64, "%s/%s/mac/en-US/%s.dmg");
        firefox.put(WIN32, "%s/%s/win32/en-US/%s.exe");
        firefox.put(WIN64, "%s/%s/win64/en-US/%s.exe");
        downloadURLs.put(Product.Firefox, firefox);
    }

    public BrowserFetcher(FetcherOptions options) {
        this.product = options.getProduct();
        this.platform = StringUtil.isNotEmpty(options.getPlatform()) ? options.getPlatform() : detectBrowserPlatform();
        this.downloadHost = StringUtil.isNotEmpty(options.getHost()) ? options.getHost() : downloadURLs.get(this.product).get("host");
        this.downloadsFolder = options.getCacheDir();
        this.revision = options.getVersion();
    }

    /**
     * <p>下载浏览器，如果项目目录下不存在对应版本时</p>
     * <p>如果不指定版本，则使用默认配置版本</p>
     *
     * @return 浏览器本地信息
     * @throws InterruptedException 异常
     * @throws IOException          异常
     */
    public RevisionInfo downloadBrowser() throws InterruptedException, IOException {
        ValidateUtil.assertArg(StringUtil.isNotBlank(this.revision), "Browser revision must be specified");
        RevisionInfo revisionInfo = this.revisionInfo(this.revision);
        if (!revisionInfo.getLocal()) {
            return this.downloadAndInstall(this.revision);
        }
        return revisionInfo;
    }

    /**
     * 根据给定得浏览器版本下载浏览器并安装
     *
     * @param revision 浏览器版本
     * @return RevisionInfo
     * @throws IOException          异常
     * @throws InterruptedException 异常
     */
    private RevisionInfo downloadAndInstall(String revision) throws IOException, InterruptedException {
        String url = getDownloadURL(this.product, this.platform, this.downloadHost, revision);
        //存放浏览器得文件夹 带有平台标识和版本标识
        String folderPath = this.relativeVersionPath(revision);
        if (!(exists(this.downloadsFolder))) {
            mkdir(this.downloadsFolder);
        }
        if (!exists(folderPath)) {
            mkdir(folderPath);
        }

        // 使用Java代码直接下载，不再调用shell脚本
        try {
            downloadWithTimeout(url, folderPath, archive(this.product, this.platform, revision));
        } catch (Exception e) {
            LOGGER.warn("Java download failed, falling back to shell: {}", e.getMessage());
            executeShell(url, folderPath, archive(this.product, this.platform, revision), fileName(this.product,this.platform));
        }

        RevisionInfo revisionInfo = this.revisionInfo(revision);
        if (revisionInfo != null) {
            File executableFile = new File(revisionInfo.getExecutablePath());
            executableFile.setExecutable(true, false);
        }
        return revisionInfo;
    }

    private void downloadWithTimeout(String url, String folderPath, String archiveName) throws IOException {
        int connectTimeout = 10000;
        int readTimeout = 30000;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestMethod("GET");

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + conn.getResponseCode());
        }

        String zipPath = folderPath + File.separator + archiveName + ".zip";
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(zipPath)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(folderPath, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    file.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        new File(zipPath).delete();
    }

    /**
     * 本地存在的浏览器版本
     *
     * @return 版本集合
     * @throws IOException 异常
     */
    public List<String> localRevisions() throws IOException {
        if (!exists(this.downloadsFolder))
            return new ArrayList<>();
        try (Stream<Path> list = Files.list(Paths.get(this.downloadsFolder))) {
            return list.map(revisionsPath -> parseRevisionsPath(this.product, revisionsPath)).filter(entry -> entry != null && this.platform.equals(entry.getPlatform())).map(RevisionEntry::getRevision).collect(Collectors.toList());
        }
    }

    /**
     * 删除指定版本的浏览器文件
     *
     * @param revision 版本
     * @throws IOException 异常
     */
    public void remove(String revision) throws IOException {
        String folderPath = this.relativeVersionPath(revision);
        ValidateUtil.assertArg(exists(folderPath), "Failed to remove: revision " + revision + " is not downloaded");
        Files.delete(Paths.get(folderPath));
    }

    /**
     * 根据给定的浏览器产品以及版本路径解析得到浏览器版本和平台
     *
     * @param product       win linux mac
     * @param revisionsPath 版本路径
     * @return RevisionEntry 浏览器版本信息的实体
     */
    private RevisionEntry parseRevisionsPath(Product product, Path revisionsPath) {
        Path fileName = revisionsPath.getFileName();
        String[] split = fileName.toString().split("-");

        if (split.length != 2)
            return null;

        String platform = split[0];
        String revision = split[1];
        if (Objects.isNull(downloadURLs.get(product).get(platform)))
            return null;
        // CHROME CHROMEHEADLESSSHELL CHROMEDRIVER 符合 xxx.xxx.xxx.xxx 格式
        if (product.equals(Product.Chrome) || product.equals(Product.Chrome_headless_shell) || product.equals(Product.Chromedriver)) {
            Pattern pattern = Pattern.compile("^\\d+\\.\\d+\\.\\d+(.\\d+)?$");
            Matcher matcher = pattern.matcher(revision);
            if (!matcher.matches())
                return null;
        }
        // CHROMIUM 全是数字
        if (product.equals(Product.Chromium)) {
            Pattern pattern = Pattern.compile("^\\d+$");
            Matcher matcher = pattern.matcher(revision);
            if (!matcher.matches())
                return null;
        }

        String executablePath = this.relativeExecutablePath(revision, revisionsPath.toAbsolutePath().toString());
        if (!Files.exists(Paths.get(executablePath))) {
            return null;
        }
        RevisionEntry entry = new RevisionEntry();
        entry.setPlatform(platform);
        entry.setProduct(product);
        entry.setRevision(revision);
        return entry;
    }

    /**
     * 静态内部类，描述谷歌版本相关内容,在这里
     */
    public static class RevisionEntry {

        private Product product;

        private String platform;

        private String revision;

        public Product getProduct() {
            return product;
        }

        public void setProduct(Product product) {
            this.product = product;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            if (StringUtil.isNotEmpty(platform)) {
                this.platform = platform;
            } else {
                this.platform = BrowserFetcher.detectBrowserPlatform();
            }
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }
    }

    /**
     * 执行 shell 文件实现下载并安装功能
     *
     * @param url        url
     * @param folderPath 安装路径
     */
    private void executeShell(String url, String folderPath, String archiveName, String executableName) throws IOException, InterruptedException {
        Process process;
        Path shellPath = null;
        BufferedReader stderrReader = null;
        BufferedReader stdoutReader = null;
        try {
            if (Helper.isLinux()) {
                shellPath = copyResourceFileToDirectory(INSTALL_CHROME_FOR_TESTING_LINUX, FileUtil.createProfileDir(SHELLS_PREFIX), "/scripts/");
                process = new ProcessBuilder("/bin/sh", "-c", shellPath.toAbsolutePath() + " " + folderPath + " " + url + " " + archiveName + " " + executableName).redirectErrorStream(true).start();
            } else if (Helper.isWindows()) {
                shellPath = copyResourceFileToDirectory(INSTALL_CHROME_FOR_TESTING_WIN, FileUtil.createProfileDir(SHELLS_PREFIX), "/scripts/");
                process = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", shellPath.toAbsolutePath().toString(), "-url", url, "-savePath", folderPath, "-archive", archiveName, "-executableName", executableName).redirectErrorStream(true).start();
            } else if (Helper.isMac()) {
                shellPath = copyResourceFileToDirectory(INSTALL_CHROME_FOR_TESTING_MAC, FileUtil.createProfileDir(SHELLS_PREFIX), "/scripts/");
                process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", shellPath.toAbsolutePath().toString()});
            } else {
                throw new JvppeteerException("Unsupported platform: " + Helper.platform());
            }
            // 读取输出流
            if (process != null) {
                stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                String stdoutLine;
                while ((stdoutLine = stdoutReader.readLine()) != null) {
                    LOGGER.info(stdoutLine);
                }
                // 等待进程完成
                boolean exitCode = process.waitFor(10L, TimeUnit.MINUTES);
                if (!exitCode) {
                    process.destroy();
                    throw new JvppeteerException("install chrome for testing failed");
                }
            }
        } finally {
            // 关闭资源
            if (stdoutReader != null) {
                stdoutReader.close();
            }
            if (stderrReader != null) {
                stderrReader.close();
            }
            if (shellPath != null) {
                shellPath.toFile().delete();
                shellPath.getParent().toFile().delete();
            }
        }
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded));
    }
    /**
     * 把resource文件复制到temp目录下
     *
     * @param name       复制的文件名
     * @param targetDir     目标文件夹
     * @param folderPath resource下的要复制的文件所在的目录
     * @return 复制后的文件路径
     * @throws IOException 创建文件失败
     */
    public static Path copyResourceFileToDirectory(String name, String targetDir, String folderPath) throws IOException {
        Path targetPath = Paths.get(targetDir);
        Path path = targetPath.resolve(name);
        if (Helper.isUnixLike()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        } else if (Helper.isWindows()) {
            Files.createFile(path);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path.toFile())); BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(BrowserFetcher.class.getResourceAsStream(folderPath + name))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        }
        return path;
    }

    /**
     * 创建文件夹
     *
     * @param folder 要创建的文件夹
     * @throws IOException 创建文件失败
     */
    private void mkdir(String folder) throws IOException {
        File file = new File(folder);
        if (!file.exists()) {
            Files.createDirectory(file.toPath());
        }
    }

    /**
     * 根据浏览器版本获取对应浏览器路径
     *
     * @param revision 浏览器版本
     * @return string
     */
    public String relativeVersionPath(String revision) {
        return Helper.join(this.downloadsFolder, this.platform + "-" + revision);
    }

    /**
     * 获取浏览器版本相关信息
     *
     * @param revision 版本
     * @return RevisionInfo
     */
    public RevisionInfo revisionInfo(String revision) {
        String versionPath = this.relativeVersionPath(revision);
        String executablePath = relativeExecutablePath(revision, versionPath);
        String url = getDownloadURL(this.product, this.platform, this.downloadHost, revision);
        boolean local = this.exists(executablePath);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("revision:{}, executablePath:{}, folderPath:{}, local:{}, url:{}, product:{}", revision, executablePath, versionPath, local, url, this.product);
        }
        return new RevisionInfo(revision, executablePath, versionPath, local, url, this.product);
    }

    /**
     * 下载后的浏览器的启动路径
     *
     * @param revision    版本 例如火狐 133.0
     * @param versionPath 版本文件夹的路径 例如火狐浏览器 ：C:\Users\fanyong\Desktop\jvppeteer\example\.local-browser\win32-133.0
     * @return 浏览器的启动路径
     */
    private String relativeExecutablePath(String revision, String versionPath) {
        String executablePath;
        if (Product.Chrome.equals(this.product)) {
            if (MAC_ARM64.equals(this.platform) || MAC_X64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "Google Chrome for Testing.app", "Contents", "MacOS", "Google Chrome for Testing");
            } else if (LINUX.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome");
            } else if (WIN32.equals(this.platform) || WIN64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome.exe");
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + this.platform);
            }
        } else if (Product.Chromium.equals(this.product)) {
            if (MAC_ARM64.equals(this.platform) || MAC_X64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "Chromium.app", "Contents", "MacOS", "Chromium");
            } else if (LINUX.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome");
            } else if (WIN32.equals(this.platform) || WIN64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome.exe");
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + this.platform);
            }
        } else if (Product.Chromedriver.equals(this.product)) {
            if (MAC_ARM64.equals(this.platform) || MAC_X64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chromedriver");
            } else if (LINUX.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chromedriver");
            } else if (WIN32.equals(this.platform) || WIN64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chromedriver.exe");
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + this.platform);
            }
        } else if (Product.Chrome_headless_shell.equals(this.product)) {
            if (MAC_ARM64.equals(this.platform) || MAC_X64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome-headless-shell");
            } else if (LINUX.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome-headless-shell");
            } else if (WIN32.equals(this.platform) || WIN64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, archive(this.product, this.platform, revision), "chrome-headless-shell.exe");
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + this.platform);
            }
        } else if (Product.Firefox.equals(this.product)) {
            if (MAC_ARM64.equals(this.platform) || MAC_X64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, "Firefox.app", "Contents", "MacOS", "firefox");
            } else if (LINUX.equals(this.platform)) {
                executablePath = Helper.join(versionPath, "firefox", "firefox");
            } else if (WIN32.equals(this.platform) || WIN64.equals(this.platform)) {
                executablePath = Helper.join(versionPath, "core", "firefox.exe");
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + this.platform);
            }
        } else {
            throw new IllegalArgumentException("Unsupported product: " + this.product);
        }
        return executablePath;
    }

    /**
     * 获取浏览器的文件名
     *
     * @param product 产品
     * @return 文件名
     */
    public static String fileName(Product product, String platform) {
        platform = StringUtil.isEmpty(platform) ? detectBrowserPlatform() : platform;
        if (Product.Chrome.equals(product)) {
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "Google Chrome for Testing";
            } else if (LINUX.equals(platform)) {
                return "chrome";
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "chrome.exe";
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        } else if (Product.Chromium.equals(product)) {
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "Chromium";
            } else if (LINUX.equals(platform)) {
                return "chrome";
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "chrome.exe";
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        } else if (Product.Chromedriver.equals(product)) {
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "chromedriver";
            } else if (LINUX.equals(platform)) {
                return "chromedriver";
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "chromedriver.exe";
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        } else if (Product.Chrome_headless_shell.equals(product)) {
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "chrome-headless-shell";
            } else if (LINUX.equals(platform)) {
                return "chrome-headless-shell";
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "chrome-headless-shell.exe";
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        } else if (Product.Firefox.equals(product)) {
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "firefox";
            } else if (LINUX.equals(platform)) {
                return "firefox";
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "firefox.exe";
            } else {
                throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        } else {
            throw new IllegalArgumentException("Unsupported product: " + product);
        }
    }

    /**
     * 检测给定的路径是否存在
     *
     * @param filePath 文件路径
     * @return boolean
     */
    public boolean exists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * 根据平台信息和版本信息确定要下载的浏览器压缩包名称
     *
     * @param product  产品
     * @param platform 平台
     * @param revision 版本
     * @return 压缩包名字
     */
    public String archive(Product product, String platform, String revision) {
        boolean match = LINUX.equals(platform) || MAC_X64.equals(platform) || MAC_ARM64.equals(platform) || WIN32.equals(platform) || WIN64.equals(platform);
        if (Product.Chrome.equals(product)) {
            if (match)
                return "chrome-" + platform;
        } else if (Product.Chromium.equals(product)) {
            if (LINUX.equals(platform))
                return "chrome-linux";
            if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform))
                return "chrome-mac";
            if (WIN32.equals(platform) || WIN64.equals(platform)) {
                if (Integer.parseInt(revision, 10) > 591479)
                    return "chrome-win";
                else
                    return "chrome-win32";
            }
        } else if (Product.Chromedriver.equals(product)) {
            if (match)
                return "chromedriver-" + platform;
        } else if (Product.Chrome_headless_shell.equals(product)) {
            if (match)
                return "chrome-headless-shell-" + platform;
        } else if (Product.Firefox.equals(product)) {
            if (LINUX.equals(platform)) {
                return "firefox-" + revision;
            } else if (MAC_ARM64.equals(platform) || MAC_X64.equals(platform)) {
                return "Firefox " + revision;
            } else if (WIN32.equals(platform) || WIN64.equals(platform)) {
                return "Firefox Setup " + revision;
            }
        }
        throw new JvppeteerException("Unsupported platform: " + platform);
    }

    /**
     * 解释火狐浏览器版本号
     *
     * @param revision 版本号
     */
    private String parseRevision(String revision) {
        return revision.replace("stable_", "");
    }

    private String folder() {
        String[] strings = downloadURLs.get(this.product).get(this.platform).split("/");
        if (Product.Chromium.equals(this.product)) {
            return strings[2];
        } else {
            return strings[3];
        }
    }

    /**
     * 将几个字符串拼接成浏览器的下载路径
     *
     * @param product  产品：chrome or firefox
     * @param platform win linux mac
     * @param host     域名地址
     * @param revision 版本
     * @return 下载浏览器的url
     */
    public String getDownloadURL(Product product, String platform, String host, String revision) {
        return String.format(downloadURLs.get(product).get(platform), host, revision, archive(product, platform, revision));
    }

    public String host() {
        return downloadHost;
    }

    public String platform() {
        return platform;
    }

    public String getDownloadsFolder() {
        return downloadsFolder;
    }

    public void setDownloadsFolder(String downloadsFolder) {
        this.downloadsFolder = downloadsFolder;
    }

    public Product product() {
        return product;
    }

    private static String detectBrowserPlatform() {
        if (Helper.isMac())
            return Helper.is64() ? MAC_ARM64 : MAC_X64;
        else if (Helper.isLinux())
            return LINUX;
        else if (Helper.isWindows())
            return Helper.is64() ? WIN64 : WIN32;
        else
            throw new JvppeteerException("Unsupported platform: " + Helper.platform());
    }
}
