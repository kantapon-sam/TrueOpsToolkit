package trueopstoolkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline behavioral tests; fake driver payloads are read as text, never executed. */
public final class SharedChromeDriverTest {
    private static final String CHROME = "130.0.6723.92";
    private static final String OLD = "130.0.6723.80";
    private static final String NEW = "130.0.6723.99";
    private static final SharedChromeDriver.VersionReader TEXT_VERSION = new SharedChromeDriver.VersionReader() {
        @Override public String read(Path path) throws IOException {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        }
    };

    public static void main(String[] args) throws Exception {
        Path workspace = Paths.get(args.length == 0 ? "build/driver-tests" : args[0]).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path root = Files.createTempDirectory(workspace, "run-").toAbsolutePath().normalize();
        try {
            updatesCompatiblePatch(root.resolve("update"));
            offlineCache(root.resolve("offline"));
            rejectsMismatch(root.resolve("mismatch"));
            migration(root.resolve("migration"));
            milestoneFallback(root.resolve("milestone"));
            failedInstallPreservesCache(root.resolve("bad-download"));
            archiveSafety(root.resolve("archives"));
            concurrentUpdate(root.resolve("concurrent"));
            check(SharedChromeDriver.compareVersions("130.0.6723.99", "130.0.6723.100") < 0,
                    "version comparison uses numbers");
            check(!SharedChromeDriver.validVersion("130.0.6723.99/other"), "release version cannot contain a path");
            System.out.println("SHARED CHROMEDRIVER TESTS PASS");
        } finally {
            List<Path> paths = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
            Collections.sort(paths, Collections.reverseOrder());
            for (Path path : paths) {
                if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Cleanup escaped test root.");
                Files.deleteIfExists(path);
            }
        }
    }

    private static void updatesCompatiblePatch(Path home) throws Exception {
        write(SharedChromeDriver.path(home), OLD);
        Source source = new Source(NEW, false);
        SharedChromeDriver.ensure(home, null, CHROME, source, TEXT_VERSION);
        check(NEW.equals(TEXT_VERSION.read(SharedChromeDriver.path(home))), "new patch replaces older matching driver");
        SharedChromeDriver.ensure(home, null, CHROME, source, TEXT_VERSION);
        check(source.checks.get() == 2 && source.downloads.get() == 1, "every ensure checks updates without duplicate download");
        Source lagging = new Source(OLD, false);
        SharedChromeDriver.ensure(home, null, CHROME, lagging, TEXT_VERSION);
        check(lagging.downloads.get() == 0, "newer compatible cached patch is not downgraded");
    }

    private static void offlineCache(Path home) throws Exception {
        write(SharedChromeDriver.path(home), OLD);
        final List<String> messages = new ArrayList<String>();
        SharedChromeDriver.ensure(home, messages::add, CHROME, offline(), TEXT_VERSION);
        check(OLD.equals(TEXT_VERSION.read(SharedChromeDriver.path(home))), "offline update preserves compatible driver");
        check(messages.toString().contains("existing compatible driver"), "offline fallback is reported");
    }

    private static void rejectsMismatch(Path home) throws Exception {
        write(SharedChromeDriver.path(home), "129.0.6668.80");
        expectFailure(() -> SharedChromeDriver.ensure(home, null, CHROME, offline(), TEXT_VERSION),
                "incompatible cache is rejected offline");
        write(SharedChromeDriver.path(home), "130.0.6700.80");
        expectFailure(() -> SharedChromeDriver.ensure(home, null, CHROME, offline(), TEXT_VERSION),
                "unverified same-milestone different-build cache is rejected offline");
        expectFailure(() -> SharedChromeDriver.ensure(home.resolve("missing"), null, CHROME, offline(), TEXT_VERSION),
                "missing cache reports actionable failure offline");
    }

    private static void migration(Path home) throws Exception {
        Path legacy = home.resolve("modules/approve/chromedriver.exe");
        write(legacy, OLD);
        write(home.resolve("modules/delete/chromedriver.exe"), NEW);
        write(home.resolve("modules/approve-ref/chromedriver.exe"), "999.0.0.0");
        SharedChromeDriver.ensure(home, null, CHROME, offline(), TEXT_VERSION);
        check(NEW.equals(TEXT_VERSION.read(SharedChromeDriver.path(home))), "newest compatible legacy driver migrates offline");
        check(OLD.equals(TEXT_VERSION.read(legacy)), "legacy source is preserved during migration");
    }

    private static void milestoneFallback(Path home) throws Exception {
        Source milestone = new Source("130.0.6770.10", true);
        SharedChromeDriver.ensure(home, null, CHROME, milestone, TEXT_VERSION);
        SharedChromeDriver.ensure(home, null, CHROME, offline(), TEXT_VERSION);
        check(milestone.version.equals(TEXT_VERSION.read(SharedChromeDriver.path(home))),
                "official milestone fallback is remembered for offline use");
        Files.delete(SharedChromeDriver.path(home).resolveSibling("selection.properties"));
        SharedChromeDriver.ensure(home, null, CHROME, milestone, TEXT_VERSION);
        SharedChromeDriver.ensure(home, null, CHROME, offline(), TEXT_VERSION);
        check(milestone.downloads.get() == 1, "existing official fallback can restore metadata without downloading again");
        expectFailure(() -> SharedChromeDriver.ensure(home, null, "130.0.9999.1", offline(), TEXT_VERSION),
                "milestone fallback approval is tied to detected browser build");
        Source wrongMajor = new Source("131.0.6770.10", true);
        expectFailure(() -> SharedChromeDriver.ensure(home.resolve("wrong-major"), null, CHROME, wrongMajor, TEXT_VERSION),
                "milestone fallback cannot install another major version");
        check(wrongMajor.downloads.get() == 0, "invalid release is rejected before download");
    }

    private static void failedInstallPreservesCache(Path home) throws Exception {
        write(SharedChromeDriver.path(home), OLD);
        Source invalid = new Source(NEW, false);
        invalid.payload = "129.0.0.1";
        SharedChromeDriver.ensure(home, null, CHROME, invalid, TEXT_VERSION);
        check(OLD.equals(TEXT_VERSION.read(SharedChromeDriver.path(home))), "failed version verification preserves working executable");
        expectFailure(() -> SharedChromeDriver.ensure(home.resolve("new"), null, CHROME, invalid, TEXT_VERSION),
                "invalid first installation is rejected");
        check(!Files.exists(SharedChromeDriver.path(home.resolve("new"))), "invalid executable never reaches shared path");
        try (java.util.stream.Stream<Path> stream = Files.list(SharedChromeDriver.path(home).getParent())) {
            check(stream.noneMatch(p -> p.getFileName().toString().startsWith(".")), "staged files cleaned after failed update");
        }
    }

    private static void archiveSafety(Path folder) throws Exception {
        Files.createDirectories(folder);
        Path zip = folder.resolve("driver.zip");
        Path staged = folder.resolve("staged.exe");
        archive(zip, new String[] { "../chromedriver.exe" }, NEW);
        expectFailure(() -> SharedChromeDriver.extractDriver(zip, staged), "ZIP traversal is rejected");
        archive(zip, new String[] { "chromedriver.exe", "chromedriver-win64/chromedriver.exe" }, NEW);
        expectFailure(() -> SharedChromeDriver.extractDriver(zip, staged), "duplicate driver members are rejected");
        archive(zip, new String[] { "LICENSE.chromedriver" }, NEW);
        expectFailure(() -> SharedChromeDriver.extractDriver(zip, staged), "missing driver in ZIP is rejected");
        archive(zip, new String[] { "chromedriver-win64/chromedriver.exe" }, NEW);
        SharedChromeDriver.extractDriver(zip, staged);
        check(NEW.equals(TEXT_VERSION.read(staged)), "official ZIP layout extracts to fixed staging path");
    }

    private static void concurrentUpdate(final Path home) throws Exception {
        final CountDownLatch downloading = new CountDownLatch(1);
        final CountDownLatch finishDownload = new CountDownLatch(1);
        final Source source = new Source(NEW, false) {
            @Override public void download(SharedChromeDriver.Release release, Path zip) throws IOException {
                downloading.countDown();
                try {
                    if (!finishDownload.await(5, TimeUnit.SECONDS)) throw new IOException("Test download was not released.");
                } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                super.download(release, zip);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Path> request = () -> SharedChromeDriver.ensure(home, null, CHROME, source, TEXT_VERSION);
            Future<Path> first = workers.submit(request);
            check(downloading.await(5, TimeUnit.SECONDS), "first update reaches staged download");
            Future<Path> second = workers.submit(request);
            Thread.sleep(200L);
            check(!second.isDone(), "concurrent update waits for installation lock");
            finishDownload.countDown();
            check(first.get(5, TimeUnit.SECONDS).equals(second.get(5, TimeUnit.SECONDS)), "concurrent callers receive same shared path");
            check(source.downloads.get() == 1, "concurrent callers download once");
        } finally {
            finishDownload.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static SharedChromeDriver.DriverSource offline() {
        return new SharedChromeDriver.DriverSource() {
            @Override public SharedChromeDriver.Release latest(String chrome) throws IOException { throw new IOException("offline"); }
            @Override public void download(SharedChromeDriver.Release release, Path zip) { throw new AssertionError("Unexpected download"); }
        };
    }

    private static class Source implements SharedChromeDriver.DriverSource {
        final String version;
        final boolean milestone;
        final AtomicInteger checks = new AtomicInteger();
        final AtomicInteger downloads = new AtomicInteger();
        String payload;
        Source(String version, boolean milestone) { this.version = version; this.payload = version; this.milestone = milestone; }
        @Override public SharedChromeDriver.Release latest(String chrome) {
            checks.incrementAndGet();
            return new SharedChromeDriver.Release(version, "unused-in-offline-test", milestone);
        }
        @Override public void download(SharedChromeDriver.Release release, Path zip) throws IOException {
            downloads.incrementAndGet();
            archive(zip, new String[] { "chromedriver-win64/chromedriver.exe" }, payload);
        }
    }

    private static void archive(Path zip, String[] entries, String content) throws IOException {
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream output = new ZipOutputStream(file)) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void write(Path target, String version) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, version.getBytes(StandardCharsets.UTF_8));
    }
    interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation action, String name) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }
}
