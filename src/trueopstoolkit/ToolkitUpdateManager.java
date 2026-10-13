package trueopstoolkit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Downloads a verified complete toolkit release; never changes a running application. */
public final class ToolkitUpdateManager {
    static final String REPOSITORY = "https://github.com/kantapon-sam/TrueOpsToolkit";
    public static final String FEED = REPOSITORY + "/releases/latest/download/update.properties";
    static final long ARCHIVE_LIMIT = 512L * 1024 * 1024;
    private static final long MANIFEST_LIMIT = 16 * 1024;
    static final String REOPEN_TOKEN_PROPERTY = "trueops.update.restart.token";
    static final String REOPEN_ACK = ".updates/reopened.properties";

    private ToolkitUpdateManager() { }

    public static final class PendingUpdate {
        final Path directory;
        final String version;
        final String sha256;
        final String root;

        PendingUpdate(Path directory, String version, String sha256, String root) {
            this.directory = directory.toAbsolutePath().normalize();
            this.version = version;
            this.sha256 = sha256;
            this.root = root;
        }

        public String getVersion() { return version; }
        public Path getStagingDirectory() { return directory; }
    }

    /** Run on a background worker. A missing first release or no newer version returns null. */
    public static PendingUpdate checkAndStage(Path home, String currentVersion,
            Consumer<String> status) throws IOException {
        return checkAndStage(home, currentVersion, status, new GitHubSource());
    }

    interface ReleaseSource {
        Properties manifest() throws IOException;
        void download(String address, Path destination) throws IOException;
    }

    static PendingUpdate checkAndStage(Path home, String currentVersion,
            Consumer<String> status, ReleaseSource source) throws IOException {
        if (!validVersion(currentVersion)) return null; // Development/prerelease builds are never auto-replaced.
        report(home, status, "Checking for TrueOps Toolkit updates...");
        Properties manifest;
        try { manifest = source.manifest(); }
        catch (FileNotFoundException noRelease) {
            report(home, status, "No TrueOps Toolkit update is available.");
            return null;
        }
        String version = manifest.getProperty("app.version", "").trim();
        String hash = manifest.getProperty("archive.sha256", "").trim();
        String root = manifest.getProperty("archive.root", "").trim();
        String address = manifest.getProperty("archive.url", "").trim();
        validateManifest(version, hash, root, address);
        if (compareVersions(version, currentVersion) <= 0) {
            report(home, status, "TrueOps Toolkit v" + currentVersion + " is up to date.");
            return null;
        }
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path stagingRoot = normalizedHome.resolve(".updates");
        ToolkitUpdater.requireSafePath(normalizedHome, stagingRoot);
        Files.createDirectories(stagingRoot);
        PendingUpdate cached = reusableStage(normalizedHome, stagingRoot, version, hash, root);
        if (cached != null) {
            report(home, status, "TrueOps Toolkit v" + version + " is ready to install when you close the app.");
            return cached;
        }
        Path staging = Files.createTempDirectory(stagingRoot, "release-" + version + "-");
        boolean complete = false;
        try {
            report(home, status, "Downloading TrueOps Toolkit v" + version + "...");
            Path archive = staging.resolve("release.zip");
            source.download(address, archive);
            PendingUpdate pending = new PendingUpdate(staging, version, hash, root);
            verifyArchive(pending);
            Path verification = staging.resolve("verified");
            ToolkitUpdater.extractAndValidate(pending, verification);
            ToolkitUpdater.deleteTree(verification);
            Properties plan = new Properties();
            plan.setProperty("app.version", version);
            plan.setProperty("archive.sha256", hash);
            plan.setProperty("archive.root", root);
            plan.setProperty("archive.url", address);
            try (OutputStream output = Files.newOutputStream(staging.resolve("update.properties"),
                    StandardOpenOption.CREATE_NEW)) { plan.store(output, "Verified toolkit release; no user data"); }
            complete = true;
            report(home, status, "TrueOps Toolkit v" + version + " is ready to install when you close the app.");
            return pending;
        } finally {
            if (!complete) {
                try { ToolkitUpdater.deleteTree(staging); } catch (IOException ignored) { }
                log(home, "Update download/verification failed; installed application was not changed.");
            }
        }
    }

    private static PendingUpdate reusableStage(Path home, Path stagingRoot, String version,
            String hash, String root) throws IOException {
        int inspected = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(stagingRoot, "release-" + version + "-*")) {
            for (Path directory : directories) {
                if (++inspected > 100) break;
                try {
                    ToolkitUpdater.requireSafePath(home, directory);
                    // A dispatched helper or a failed installation may still need its recovery files.
                    if (!Files.isDirectory(directory) || Files.exists(directory.resolve("updater.jar"))) continue;
                    PendingUpdate candidate = readPlan(home, directory);
                    if (version.equals(candidate.version) && hash.equalsIgnoreCase(candidate.sha256) && root.equals(candidate.root)) {
                        verifyArchive(candidate);
                        return candidate;
                    }
                } catch (IOException incomplete) { /* A partial download cannot be reused. */ }
            }
        }
        return null;
    }

    /** Starts a separate helper, which waits for this JVM and all operation locks before installation. */
    public static void launchInstaller(PendingUpdate pending, Path home) throws IOException {
        Path normalizedHome = home.toAbsolutePath().normalize();
        if (isInstallPending(normalizedHome)) throw new IOException("Another toolkit update is already being installed.");
        validateStaging(normalizedHome, pending.directory);
        verifyArchive(pending);
        Path helper = prepareInstaller(pending, normalizedHome);
        String processId = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
        if (!processId.matches("[1-9][0-9]{0,18}")) throw new IOException("Cannot identify launcher process.");
        Path java = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java)) throw new IOException("Java runtime was not found.");
        Path log = normalizedHome.resolve("logs/update.log");
        ToolkitUpdater.requireSafePath(normalizedHome, log);
        Files.createDirectories(log.getParent());
        writeInstallMarker(normalizedHome, pending.directory);
        try {
            new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", helper.toString(),
                    "trueopstoolkit.ToolkitUpdater", "--home", normalizedHome.toString(),
                    "--stage", pending.directory.toString(), "--pid", processId)
                    .directory(normalizedHome.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        } catch (IOException failure) {
            clearInstallMarker(normalizedHome, pending.directory);
            throw failure;
        }
        log(home, "Update " + pending.version + " scheduled after launcher exit.");
    }

    /** Use the verified release's helper, so future releases bring their own installer behavior. */
    static Path prepareInstaller(PendingUpdate pending, Path home) throws IOException {
        validateStaging(home, pending.directory);
        verifyArchive(pending);
        Path verification = Files.createTempDirectory(pending.directory, "helper-source-");
        try {
            ToolkitUpdater.extractAndValidate(pending, verification);
            Path incoming = verification.resolve("TrueOpsToolkit.jar");
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(incoming.toFile())) {
                if (jar.getJarEntry("trueopstoolkit/ToolkitUpdater.class") == null
                        || jar.getJarEntry("trueopstoolkit/ToolkitUpdateManager.class") == null) {
                    throw new IOException("The released application is missing its update helper.");
                }
            }
            Path helper = pending.directory.resolve("updater.jar");
            ToolkitUpdater.requireSafePath(home, helper);
            Files.copy(incoming, helper, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(incoming).equals(sha256(helper))) throw new IOException("Update helper copy was incomplete.");
            return helper;
        } finally { ToolkitUpdater.deleteTree(verification); }
    }

    /** A newly opened launcher exits promptly while the previous launcher is being updated. */
    public static boolean isInstallPending(Path home) {
        try {
            Properties marker = readInstallMarker(home);
            if (marker == null) return false;
            long age = System.currentTimeMillis() - Long.parseLong(marker.getProperty("created", "0"));
            String stage = marker.getProperty("stage", "");
            ToolkitUpdater.requireSafePath(home, home.resolve(".updates").resolve(stage));
            return age >= -30000 && age < TimeUnit.MINUTES.toMillis(4)
                    && stage.matches("release-[A-Za-z0-9.\\-]+")
                    && Files.isDirectory(home.resolve(".updates").resolve(stage));
        } catch (IOException | NumberFormatException malformed) { return false; }
    }

    static void writeInstallMarker(Path home, Path stage) throws IOException {
        validateStaging(home, stage);
        Path marker = home.resolve(".updates/installing.properties");
        ToolkitUpdater.requireSafePath(home, marker);
        Properties properties = new Properties();
        properties.setProperty("created", Long.toString(System.currentTimeMillis()));
        properties.setProperty("stage", stage.getFileName().toString());
        Path temporary = Files.createTempFile(marker.getParent(), ".installing-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "Short-lived update handoff"); }
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Properties readInstallMarker(Path home) throws IOException {
        Path file = home.resolve(".updates/installing.properties");
        ToolkitUpdater.requireSafePath(home, file);
        if (!Files.isRegularFile(file)) return null;
        if (Files.size(file) > 4096) throw new IOException("Invalid update handoff marker.");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }

    static void clearInstallMarker(Path home, Path stage) {
        if (home == null || stage == null) return;
        try {
            Properties marker = readInstallMarker(home);
            if (marker != null && stage.getFileName().toString().equals(marker.getProperty("stage"))) {
                Files.deleteIfExists(home.resolve(".updates/installing.properties"));
            }
        } catch (IOException ignored) { /* Marker expiration prevents a stale marker from blocking startup indefinitely. */ }
    }

    /** A relaunch must never race a stale marker or clear a different installer's marker. */
    static void completeInstallMarker(Path home, Path stage) throws IOException {
        Properties marker = readInstallMarker(home);
        if (marker == null) return;
        if (!stage.getFileName().toString().equals(marker.getProperty("stage"))) {
            throw new IOException("A different toolkit installer owns the handoff marker.");
        }
        Files.delete(home.resolve(".updates/installing.properties"));
        if (readInstallMarker(home) != null) throw new IOException("The toolkit handoff marker could not be cleared.");
    }

    /** Called only after the updated launcher's main window is visible. */
    static void reportReopened(Path home, String version) {
        String token = System.getProperty(REOPEN_TOKEN_PROPERTY, "");
        if (!validVersion(version) || !token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return;
        Path temporary = null;
        try {
            Path file = home.toAbsolutePath().normalize().resolve(REOPEN_ACK);
            ToolkitUpdater.requireSafePath(home, file);
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".reopened-", ".tmp");
            Properties ready = new Properties();
            ready.setProperty("app.version", version);
            ready.setProperty("restart.token", token);
            ready.setProperty("ready", Long.toString(System.currentTimeMillis()));
            try (OutputStream output = Files.newOutputStream(temporary)) { ready.store(output, "Updated Toolkit window is visible"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            log(home, "Updated Toolkit opened, but its readiness acknowledgement could not be written.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    static boolean hasReopened(Path home, String version, String token, long launchedAt) throws IOException {
        Path file = home.toAbsolutePath().normalize().resolve(REOPEN_ACK);
        ToolkitUpdater.requireSafePath(home, file);
        if (!Files.isRegularFile(file)) return false;
        if (Files.size(file) > 4096) throw new IOException("Invalid update readiness acknowledgement.");
        Properties ready = new Properties();
        try (InputStream input = Files.newInputStream(file)) { ready.load(input); }
        long time;
        try { time = Long.parseLong(ready.getProperty("ready", "0")); }
        catch (NumberFormatException invalid) { return false; }
        return version.equals(ready.getProperty("app.version")) && token.equals(ready.getProperty("restart.token"))
                && time >= launchedAt && time <= System.currentTimeMillis() + 30000;
    }

    static PendingUpdate readPlan(Path home, Path directory) throws IOException {
        validateStaging(home, directory);
        Path file = directory.resolve("update.properties");
        ToolkitUpdater.requireSafePath(home, file);
        if (Files.size(file) > MANIFEST_LIMIT) throw new IOException("Update plan exceeds the size limit.");
        Properties plan = new Properties();
        try (InputStream input = Files.newInputStream(file)) { plan.load(input); }
        String version = plan.getProperty("app.version", "");
        String hash = plan.getProperty("archive.sha256", "");
        String root = plan.getProperty("archive.root", "");
        validateManifest(version, hash, root, plan.getProperty("archive.url", ""));
        return new PendingUpdate(directory, version, hash, root);
    }

    static void validateStaging(Path home, Path stage) throws IOException {
        Path normalized = home.toAbsolutePath().normalize();
        Path value = stage.toAbsolutePath().normalize();
        if (!normalized.resolve(".updates").equals(value.getParent())
                || !value.getFileName().toString().startsWith("release-")) {
            throw new IOException("Update staging folder is outside this installation.");
        }
        ToolkitUpdater.requireSafePath(normalized, value);
        if (!Files.isDirectory(value)) throw new IOException("Update staging folder is missing.");
    }

    static void validateManifest(String version, String hash, String root, String address) throws IOException {
        if (!validVersion(version) || !hash.matches("[0-9a-fA-F]{64}")
                || !("TrueOpsToolkit-" + version).equals(root)
                || !(REPOSITORY + "/releases/download/v" + version
                + "/TrueOpsToolkit-" + version + "-windows.zip").equals(address)) {
            throw new IOException("GitHub update descriptor is invalid.");
        }
    }

    static boolean validVersion(String version) {
        return version != null && version.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    }

    static int compareVersions(String first, String second) {
        if (!validVersion(first) || !validVersion(second)) throw new IllegalArgumentException("Invalid stable version.");
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }

    static void verifyArchive(PendingUpdate pending) throws IOException {
        Path archive = pending.directory.resolve("release.zip");
        ToolkitUpdater.requireSafePath(pending.directory, archive);
        if (!Files.isRegularFile(archive) || Files.size(archive) > ARCHIVE_LIMIT
                || !sha256(archive).equalsIgnoreCase(pending.sha256)) {
            throw new IOException("Toolkit update failed SHA-256 verification.");
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable.", impossible); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static final class GitHubSource implements ReleaseSource {
        @Override public Properties manifest() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            transfer(FEED, bytes, MANIFEST_LIMIT, 30000L);
            Properties result = new Properties();
            try (InputStream input = new ByteArrayInputStream(bytes.toByteArray())) { result.load(input); }
            return result;
        }
        @Override public void download(String address, Path destination) throws IOException {
            try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                transfer(address, output, ARCHIVE_LIMIT, 300000L);
            }
        }
    }

    static void validateDownloadUrl(URL address) throws IOException {
        String host = address.getHost().toLowerCase(Locale.ROOT);
        boolean github = "github.com".equals(host)
                && address.getPath().startsWith("/kantapon-sam/TrueOpsToolkit/releases/");
        boolean asset = "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host)
                || "github-releases.githubusercontent.com".equals(host);
        if (!"https".equals(address.getProtocol()) || (address.getPort() != -1 && address.getPort() != 443)
                || address.getUserInfo() != null || address.getRef() != null || !(github || asset)) {
            throw new IOException("Update downloads must stay on the toolkit GitHub release endpoints.");
        }
    }

    private static void transfer(String address, OutputStream output, long limit, long timeout) throws IOException {
        URL url = new URL(address);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        for (int redirect = 0; redirect < 6; redirect++) {
            validateDownloadUrl(url);
            if (System.nanoTime() > deadline) throw new IOException("Toolkit update download timed out.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "TrueOpsToolkit-Updater");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                int response = connection.getResponseCode();
                if (response == 404) throw new FileNotFoundException("No published toolkit release descriptor.");
                if (response == 301 || response == 302 || response == 303 || response == 307 || response == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("GitHub download redirect is missing a target.");
                    url = new URL(url, location);
                    continue;
                }
                if (response != 200) throw new IOException("GitHub update download returned HTTP " + response + ".");
                long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("Toolkit update download exceeds the size limit.");
                long received = 0;
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Toolkit update check was interrupted.");
                        if (System.nanoTime() > deadline) throw new IOException("Toolkit update download timed out.");
                        received += count;
                        if (received > limit) throw new IOException("Toolkit update download exceeds the size limit.");
                        output.write(buffer, 0, count);
                    }
                }
                if (expected >= 0 && received != expected) throw new IOException("Toolkit update download was incomplete.");
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many GitHub download redirects.");
    }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"); }

    private static void report(Path home, Consumer<String> status, String message) {
        if (status != null) status.accept(message);
        log(home, message);
    }

    static void log(Path home, String message) {
        if (home == null) return;
        try {
            Path file = home.toAbsolutePath().normalize().resolve("logs/update.log");
            ToolkitUpdater.requireSafePath(home, file);
            Files.createDirectories(file.getParent());
            Files.write(file, Arrays.asList("[" + LocalDateTime.now() + "] " + message), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { /* An offline/read-only installation must still run. */ }
    }
}
