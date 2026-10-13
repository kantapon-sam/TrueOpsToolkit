package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** One ChromeDriver installation for every toolkit module, with no Selenium dependency. */
public final class SharedChromeDriver {
    private static final String CFT = "https://googlechromelabs.github.io/chrome-for-testing/";
    private static final String STORAGE = "https://storage.googleapis.com/chrome-for-testing-public/";
    private static final String LEGACY = "https://chromedriver.storage.googleapis.com/";
    private static final Pattern VERSION = Pattern.compile("(?<![0-9.])([0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6})(?![0-9.])");
    private static final long ZIP_LIMIT = 64L * 1024 * 1024;
    private static final long EXTRACT_LIMIT = 128L * 1024 * 1024;
    private static final Consumer<String> QUIET = new Consumer<String>() {
        @Override public void accept(String message) { }
    };

    private SharedChromeDriver() { }

    public static Path path(Path home) {
        return home.toAbsolutePath().normalize().resolve("drivers").resolve("chromedriver.exe");
    }

    /**
     * Check for a newer compatible release on every call. Call on a worker thread
     * before starting automation, while the toolkit operation guard is held.
     * Updates are staged and validated before replacing the shared executable.
     */
    public static Path ensure(Path home, Consumer<String> status) throws Exception {
        return ensure(home, status, detectChromeVersion(), new OfficialSource(), new VersionReader() {
            @Override public String read(Path executable) throws IOException {
                return driverVersion(executable);
            }
        });
    }

    // The package-private dependencies let tests exercise failures without network or browser activity.
    interface VersionReader { String read(Path executable) throws IOException; }
    interface DriverSource {
        Release latest(String chromeVersion) throws IOException;
        void download(Release release, Path zip) throws IOException;
    }

    static final class Release {
        final String version;
        final String url;
        final boolean milestoneFallback;
        Release(String version, String url, boolean milestoneFallback) {
            this.version = version;
            this.url = url;
            this.milestoneFallback = milestoneFallback;
        }
    }

    static Path ensure(Path home, Consumer<String> status, String chromeVersion,
            DriverSource source, VersionReader reader) throws Exception {
        if (!validVersion(chromeVersion)) throw new IOException("Cannot identify the installed Google Chrome version.");
        Consumer<String> log = status == null ? QUIET : status;
        Path target = path(home);
        Files.createDirectories(target.getParent());
        log.accept("Google Chrome: " + chromeVersion + "; shared ChromeDriver: " + target);
        try (FileChannel channel = FileChannel.open(target.resolveSibling("update.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = acquire(channel, log)) {
            String existing = readVersion(target, reader);
            if (existing == null) {
                migrateLegacy(home, target, chromeVersion, log, reader);
                existing = readVersion(target, reader);
            }
            boolean usable = cachedCompatible(target, chromeVersion, existing);
            try {
                log.accept("Checking Google for the latest ChromeDriver compatible with Chrome " + chromeVersion + "...");
                Release release = source.latest(chromeVersion);
                validateRelease(chromeVersion, release);
                // Preserve a newer cached patch of the same build if Google's feed lags.
                if (release.version.equals(existing) || (usable && sameBuild(existing, release.version)
                        && compareVersions(existing, release.version) >= 0)) {
                    if (release.version.equals(existing)) writeSelection(target, chromeVersion, release, log);
                    log.accept("Shared ChromeDriver is ready: " + existing);
                    return target;
                }
                if (release.milestoneFallback) {
                    log.accept("Google has no driver for this exact Chrome build yet; using milestone "
                            + major(chromeVersion) + " release " + release.version + ".");
                }
                log.accept("Downloading shared ChromeDriver " + release.version + "...");
                Path archive = Files.createTempFile(target.getParent(), ".download-", ".zip");
                Path staged = Files.createTempFile(target.getParent(), ".driver-", ".exe");
                try {
                    source.download(release, archive);
                    extractDriver(archive, staged);
                    String downloaded = reader.read(staged);
                    if (!release.version.equals(downloaded)) {
                        throw new IOException("Downloaded ChromeDriver failed version verification (expected "
                                + release.version + ", received " + downloaded + ").");
                    }
                    // Never delete the working driver first. An active Windows executable
                    // cannot be replaced; that failure falls back to the compatible cache.
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    writeSelection(target, chromeVersion, release, log);
                    log.accept("Shared ChromeDriver updated: " + release.version);
                    return target;
                } finally {
                    Files.deleteIfExists(archive);
                    Files.deleteIfExists(staged);
                }
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                if (usable && Files.isRegularFile(target)) {
                    log.accept("ChromeDriver update unavailable: " + failure.getMessage()
                            + " Using the existing compatible driver " + existing + ".");
                    return target;
                }
                throw new IOException("Cannot prepare ChromeDriver for Google Chrome " + chromeVersion
                        + ". Check the internet/proxy connection and try again, or place a driver matching Chrome "
                        + build(chromeVersion) + " in " + target
                        + ". Close running automation before retrying an update. " + failure.getMessage(), failure);
            }
        }
    }

    private static FileLock acquire(FileChannel channel, Consumer<String> log) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        boolean reported = false;
        while (System.nanoTime() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException busy) { /* Another worker in this JVM owns it. */ }
            if (!reported) {
                log.accept("Waiting for another shared ChromeDriver update to finish...");
                reported = true;
            }
            try { Thread.sleep(150L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("ChromeDriver update was interrupted.", interrupted);
            }
        }
        throw new IOException("Another ChromeDriver update is still running. Please retry after it finishes.");
    }

    private static void migrateLegacy(Path home, Path target, String chrome, Consumer<String> log,
            VersionReader reader) throws IOException {
        String[] locations = { "modules/approve/chromedriver.exe",
            "modules/approve-ref/chromedriver.exe", "modules/delete/chromedriver.exe" };
        Path best = null;
        String bestVersion = null;
        for (String location : locations) {
            Path candidate = home.resolve(location);
            String version = readVersion(candidate, reader);
            if (sameBuild(chrome, version) && (bestVersion == null || compareVersions(version, bestVersion) > 0)) {
                best = candidate;
                bestVersion = version;
            }
        }
        if (best == null) return;
        Path staged = Files.createTempFile(target.getParent(), ".migrate-", ".exe");
        try {
            Files.copy(best, staged, StandardCopyOption.REPLACE_EXISTING);
            if (!bestVersion.equals(reader.read(staged))) throw new IOException("Legacy ChromeDriver changed during migration.");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.accept("Copied compatible ChromeDriver " + bestVersion + " to the shared drivers folder.");
        } finally { Files.deleteIfExists(staged); }
    }

    private static String readVersion(Path executable, VersionReader reader) {
        if (!Files.isRegularFile(executable)) return null;
        try {
            String version = reader.read(executable);
            return validVersion(version) ? version : null;
        } catch (IOException unavailable) { return null; }
    }

    private static boolean cachedCompatible(Path target, String chrome, String driver) {
        if (sameBuild(chrome, driver)) return true;
        if (!validVersion(driver) || !major(chrome).equals(major(driver))) return false;
        Properties selection = new Properties();
        try (InputStream input = Files.newInputStream(target.resolveSibling("selection.properties"))) {
            selection.load(input);
            return "true".equals(selection.getProperty("milestoneFallback"))
                    && build(chrome).equals(selection.getProperty("chromeBuild"))
                    && driver.equals(selection.getProperty("driverVersion"));
        } catch (IOException missing) { return false; }
    }

    private static void writeSelection(Path target, String chrome, Release release, Consumer<String> log) {
        Properties selection = new Properties();
        selection.setProperty("chromeBuild", build(chrome));
        selection.setProperty("driverVersion", release.version);
        selection.setProperty("milestoneFallback", Boolean.toString(release.milestoneFallback));
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".selection-", ".properties");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                selection.store(output, "Google Chrome for Testing selection; no account information");
            }
            Files.move(temporary, target.resolveSibling("selection.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.accept("ChromeDriver is installed, but its fallback selection could not be saved: " + failure.getMessage());
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static void validateRelease(String chrome, Release release) throws IOException {
        if (release == null || !validVersion(release.version)
                || !(sameBuild(chrome, release.version) || (release.milestoneFallback
                && Integer.parseInt(major(chrome)) >= 115 && major(chrome).equals(major(release.version))))) {
            throw new IOException("Google returned a ChromeDriver release that does not match the installed Chrome.");
        }
    }

    /** Only exact known ZIP member names are extracted, always to a fixed staging path. */
    static void extractDriver(Path archive, Path target) throws IOException {
        if (Files.size(archive) > ZIP_LIMIT) throw new IOException("ChromeDriver archive exceeds the size limit.");
        long total = 0;
        boolean found = false;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 100) throw new IOException("ChromeDriver archive contains too many entries.");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains(":") || name.equals("..")
                        || name.startsWith("../") || name.contains("/../")) {
                    throw new IOException("Unsafe path in ChromeDriver archive.");
                }
                boolean driver = !entry.isDirectory() && (name.equals("chromedriver.exe")
                        || name.equals("chromedriver-win64/chromedriver.exe")
                        || name.equals("chromedriver-win32/chromedriver.exe"));
                if (driver && found) throw new IOException("ChromeDriver archive contains duplicate executables.");
                OutputStream output = driver ? Files.newOutputStream(target) : null;
                long memberSize = 0;
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        memberSize += count;
                        if (total > EXTRACT_LIMIT) throw new IOException("ChromeDriver archive expands beyond the size limit.");
                        if (output != null) output.write(buffer, 0, count);
                    }
                } finally { if (output != null) output.close(); }
                if (driver) {
                    if (memberSize == 0) throw new IOException("ChromeDriver archive contains an empty executable.");
                    found = true;
                }
                input.closeEntry();
            }
        }
        if (!found) throw new IOException("The download did not contain chromedriver.exe.");
    }

    private static final class OfficialSource implements DriverSource {
        @Override public Release latest(String chrome) throws IOException {
            boolean modern = Integer.parseInt(major(chrome)) >= 115;
            String endpoint = modern ? CFT : LEGACY;
            String version;
            boolean fallback = false;
            try { version = releaseVersion(endpoint + "LATEST_RELEASE_" + build(chrome)); }
            catch (FileNotFoundException missingBuild) {
                if (!modern) throw missingBuild;
                version = releaseVersion(endpoint + "LATEST_RELEASE_" + major(chrome));
                fallback = true;
            }
            String platform = windows64() ? "win64" : "win32";
            String url = modern ? STORAGE + version + "/" + platform + "/chromedriver-" + platform + ".zip"
                    : LEGACY + version + "/chromedriver_win32.zip";
            return new Release(version, url, fallback);
        }

        @Override public void download(Release release, Path zip) throws IOException {
            try (OutputStream output = Files.newOutputStream(zip)) {
                transfer(release.url, output, ZIP_LIMIT, 90000L);
            }
        }
    }

    private static String releaseVersion(String url) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transfer(url, output, 1024L, 15000L);
        String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        if (!validVersion(value)) throw new IOException("Invalid ChromeDriver release response from Google.");
        return value;
    }

    private static void transfer(String address, OutputStream output, long limit, long timeout) throws IOException {
        URL url = new URL(address);
        String host = url.getHost();
        if (!"https".equals(url.getProtocol()) || !(host.equals("googlechromelabs.github.io")
                || host.equals("storage.googleapis.com") || host.equals("chromedriver.storage.googleapis.com"))) {
            throw new IOException("ChromeDriver downloads must use an official Google HTTPS endpoint.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "TrueOpsToolkit/SharedChromeDriver");
        connection.setRequestProperty("Cache-Control", "no-cache");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            int code = connection.getResponseCode();
            if (code == 404) throw new FileNotFoundException("Google has no published ChromeDriver release at " + address);
            if (code != 200) throw new IOException("Google ChromeDriver download returned HTTP " + code + ".");
            long expected = connection.getContentLengthLong();
            if (expected > limit) throw new IOException("ChromeDriver download exceeds the size limit.");
            long received = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("ChromeDriver download was interrupted.");
                    if (System.nanoTime() > deadline) throw new IOException("ChromeDriver download timed out.");
                    received += count;
                    if (received > limit) throw new IOException("ChromeDriver download exceeds the size limit.");
                    output.write(buffer, 0, count);
                }
            }
            if (expected >= 0 && received != expected) throw new IOException("ChromeDriver download was incomplete.");
        } finally { connection.disconnect(); }
    }

    public static String driverVersion(Path driver) throws IOException {
        String output = runProcess(new ProcessBuilder(driver.toAbsolutePath().toString(), "--version"));
        if (!output.trim().startsWith("ChromeDriver ")) throw new IOException("The executable did not identify itself as ChromeDriver.");
        String version = findVersion(output);
        if (version == null) throw new IOException("ChromeDriver did not report a valid version.");
        return version;
    }

    public static String detectChromeVersion() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IOException("This toolkit requires Google Chrome for Windows.");
        }
        List<Path> candidates = new ArrayList<Path>();
        addChrome(candidates, System.getenv("ProgramFiles"));
        addChrome(candidates, System.getenv("ProgramFiles(x86)"));
        addChrome(candidates, System.getenv("LOCALAPPDATA"));
        for (Path chrome : candidates) {
            if (!Files.isRegularFile(chrome)) continue;
            String systemRoot = System.getenv("SystemRoot");
            String powershell = systemRoot == null ? "powershell.exe" : Paths.get(systemRoot,
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            ProcessBuilder query = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "[Diagnostics.FileVersionInfo]::GetVersionInfo($env:TRUEOPS_CHROME_BINARY).ProductVersion");
            query.environment().put("TRUEOPS_CHROME_BINARY", chrome.toString());
            try {
                String version = findVersion(runProcess(query));
                if (version != null) return version;
            } catch (IOException unavailable) { /* Fall back to Chrome's registry beacon. */ }
        }
        String[] keys = { "HKCU\\Software\\Google\\Chrome\\BLBeacon", "HKLM\\Software\\Google\\Chrome\\BLBeacon",
            "HKLM\\Software\\WOW6432Node\\Google\\Chrome\\BLBeacon" };
        for (String key : keys) {
            try {
                String version = findVersion(runProcess(new ProcessBuilder("reg.exe", "query", key, "/v", "version")));
                if (version != null) return version;
            } catch (IOException unavailable) { /* Try the next installation. */ }
        }
        throw new IOException("Google Chrome could not be detected. Install/open Google Chrome, then try again.");
    }

    private static void addChrome(List<Path> candidates, String folder) {
        if (folder != null && !folder.trim().isEmpty()) {
            candidates.add(Paths.get(folder, "Google", "Chrome", "Application", "chrome.exe"));
        }
    }

    private static String runProcess(ProcessBuilder builder) throws IOException {
        final Process process = builder.redirectErrorStream(true).start();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final IOException[] readFailure = new IOException[1];
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 65536) throw new IOException("Version command output exceeded the size limit.");
                        output.write(buffer, 0, count);
                    }
                } catch (IOException failure) { readFailure[0] = failure; }
            }
        }, "chromedriver-version-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("Version detection command timed out.");
            reader.join(1000L);
            if (reader.isAlive()) throw new IOException("Version detection output timed out.");
            if (readFailure[0] != null) throw readFailure[0];
            if (process.exitValue() != 0) throw new IOException("Version detection command failed.");
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Version detection was interrupted.", interrupted);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }

    static String findVersion(String text) {
        if (text == null) return null;
        Matcher match = VERSION.matcher(text);
        return match.find() ? match.group(1) : null;
    }

    static boolean validVersion(String version) { return version != null && VERSION.matcher(version).matches(); }
    private static String build(String version) { return version.substring(0, version.lastIndexOf('.')); }
    private static String major(String version) { return version.substring(0, version.indexOf('.')); }
    static boolean sameBuild(String first, String second) {
        return validVersion(first) && validVersion(second) && build(first).equals(build(second));
    }
    static int compareVersions(String first, String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        for (int i = 0; i < 4; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static boolean windows64() {
        String architecture = System.getenv("PROCESSOR_ARCHITECTURE");
        String nativeArchitecture = System.getenv("PROCESSOR_ARCHITEW6432");
        return (architecture != null && architecture.contains("64"))
                || (nativeArchitecture != null && nativeArchitecture.contains("64"))
                || System.getProperty("os.arch", "").contains("64");
    }

    /** CLI: SharedChromeDriver --home PATH [--version-only]. Never launches a browser. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"--home".equals(args[0]) || args.length > 3
                || (args.length == 3 && !"--version-only".equals(args[2]))) {
            throw new IllegalArgumentException("Usage: SharedChromeDriver --home PATH [--version-only]");
        }
        Path home = Paths.get(args[1]);
        if (args.length == 3) {
            System.out.println("Chrome: " + detectChromeVersion());
            System.out.println("Shared driver: " + path(home));
            System.out.println("Driver version: " + (Files.isRegularFile(path(home)) ? driverVersion(path(home)) : "not installed"));
        } else {
            System.out.println(ensure(home, new Consumer<String>() {
                @Override public void accept(String message) { System.out.println(message); }
            }));
        }
    }
}
