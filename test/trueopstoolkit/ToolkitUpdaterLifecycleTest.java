package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Real synthetic installation with injected process boundaries; never opens a browser or starts Main. */
public final class ToolkitUpdaterLifecycleTest {
    private static final String VERSION = "1.1.0";
    private static final String ROOT = "TrueOpsToolkit-" + VERSION;
    private static int checks;
    private static Path screenshotReport = Paths.get("build", "test-reports", "updater").toAbsolutePath();

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--screenshots".equals(args[0])) screenshotReport = Paths.get(args[1]).toAbsolutePath();
        else if (args.length != 0) throw new IllegalArgumentException("Usage: ToolkitUpdaterLifecycleTest [--screenshots FOLDER]");
        Path work = Files.createTempDirectory("trueops-updater-lifecycle-");
        try {
            successfulUpdate(work.resolve("successful"));
            rollbackDoesNotRestart(work.resolve("rollback"));
            busyDoesNotRestart(work.resolve("busy"));
            alreadyCurrentDoesNotRestart(work.resolve("same-version"));
            integrityFailureDoesNotRestart(work.resolve("integrity"));
            markerOwnerConflictDoesNotRestart(work.resolve("marker"));
            restartFailureStaysVisible(work.resolve("restart-failure"));
            releasedHelperIsUsed(work.resolve("helper"));
            restartUsesSeparateArguments(work.resolve("directory with spaces"));
            realJavaRestart(work.resolve("real java restart"));
            exitedChildDoesNotConfirmRestart(work.resolve("child exits before showing window"));
            readinessAcknowledgementContract(work.resolve("acknowledgement"));
            System.out.println("PASS: visible updater lifecycle (" + checks
                    + " checks): progress, rollback, markers, lock release, new-release helper, and safe automatic reopening.");
        } finally { ToolkitUpdater.deleteTree(work); }
    }

    private static void successfulUpdate(Path home) throws Exception {
        Fixture fixture = fixture(home);
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        boolean installed = ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services);
        check(installed && services.restarts == 1, "One restart follows a completed installation");
        check(progress.closed && progress.failures == 0, "Successful progress window closes without an error");
        for (String phase : new String[] { "Waiting", "Verifying", "extracting", "Backing up", "Installing", "Reopening", "reopened" }) {
            check(progress.contains(phase), "Visible status includes " + phase);
        }
        check(progress.lastPercent == 100, "Success reaches 100 percent");
        check(VERSION.equals(readVersion(home)), "Fresh application version is installed before restart");
        check(!Files.exists(fixture.pending.directory.resolve("release.zip")), "Successful update clears downloaded archive");
        check(!ToolkitUpdateManager.isInstallPending(home), "Successful update clears handoff marker");
        check(read(home.resolve("config/credentials.dat")).equals("synthetic encrypted vault"), "Shared account file is preserved");
    }

    private static void rollbackDoesNotRestart(Path home) throws Exception {
        Fixture fixture = fixture(home);
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        services.hook = count -> { if (count == 7) throw new IOException("Synthetic replacement failure"); };
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Partial install reports failure");
        check(services.restarts == 0 && progress.failures == 1 && !progress.closed, "Failure stays visible and never reopens");
        check(progress.contains("Restoring") && progress.contains("restored"), "Rollback status is visible");
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old application"), "Old launcher is restored");
        check("1.0.0".equals(readVersion(home)), "Old version is restored");
        check(Files.isRegularFile(fixture.pending.directory.resolve("release.zip")), "Failed install keeps verified recovery stage");
        check(!ToolkitUpdateManager.isInstallPending(home), "Failed installer clears its marker");
    }

    private static void busyDoesNotRestart(Path home) throws Exception {
        Fixture fixture = fixture(home);
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        try (FileChannel channel = FileChannel.open(home.resolve(".active.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Active operation blocks replacement");
            check(services.restarts == 0 && progress.failures == 1, "Busy failure remains visible and does not restart");
        }
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old application"), "Busy application is untouched");
    }

    private static void alreadyCurrentDoesNotRestart(Path home) throws Exception {
        Fixture fixture = fixture(home);
        write(home.resolve("version.properties"), "app.version=2.0.0\n");
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Downgrade is skipped");
        check(services.restarts == 0 && progress.failures == 1, "Skipped install never claims success or restarts");
        check("2.0.0".equals(readVersion(home)), "Advanced version is retained");
    }

    private static void integrityFailureDoesNotRestart(Path home) throws Exception {
        Fixture fixture = fixture(home);
        Files.write(fixture.pending.directory.resolve("release.zip"), new byte[] { 0 });
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Changed archive is rejected at installation");
        check(services.restarts == 0 && progress.failures == 1, "Integrity failure remains visible and does not restart");
        check("1.0.0".equals(readVersion(home)), "Integrity failure preserves existing version");
    }

    private static void markerOwnerConflictDoesNotRestart(Path home) throws Exception {
        Fixture fixture = fixture(home);
        Path other = Files.createDirectory(home.resolve(".updates/release-other"));
        ToolkitUpdateManager.writeInstallMarker(home, other);
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services();
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Another marker owner prevents relaunch");
        check(services.restarts == 0 && progress.failures == 1, "Marker conflict remains visible");
        check(ToolkitUpdateManager.isInstallPending(home), "Another installer's marker is retained");
    }

    private static void restartFailureStaysVisible(Path home) throws Exception {
        Fixture fixture = fixture(home);
        RecordingProgress progress = new RecordingProgress();
        Services services = new Services() {
            @Override void restart(Path target, String version) throws IOException { throw new IOException("Synthetic child startup failure"); }
        };
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Failed reopening does not claim completed lifecycle");
        check(progress.failures == 1 && !progress.closed && progress.lastPercent < 100, "Reopening error remains visible");
        check(VERSION.equals(readVersion(home)), "Reopening failure does not damage installed application");
        check(!ToolkitUpdateManager.isInstallPending(home), "User can reopen after a process-launch failure");
    }

    private static void releasedHelperIsUsed(Path home) throws Exception {
        Fixture fixture = fixture(home);
        Path helper = ToolkitUpdateManager.prepareInstaller(fixture.pending, home);
        check(Arrays.equals(fixture.launcherJar, Files.readAllBytes(helper)), "Helper is copied from the verified new release");
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old application"), "Preparing helper leaves running installation untouched");
        check(!Arrays.equals(Files.readAllBytes(home.resolve("TrueOpsToolkit.jar")), Files.readAllBytes(helper)), "Installed old helper is never used");
        Files.write(fixture.pending.directory.resolve("release.zip"), new byte[] { 0 });
        try { ToolkitUpdateManager.prepareInstaller(fixture.pending, home); throw new AssertionError("Corrupt archive was accepted"); }
        catch (IOException expected) { checks++; }
    }

    private static void restartUsesSeparateArguments(Path home) throws Exception {
        Files.createDirectories(home);
        write(home.resolve("TrueOpsToolkit.jar"), "synthetic launcher");
        List<String> command = ToolkitUpdater.restartCommand(home, VERSION);
        check(command.size() == 6 && command.get(2).equals("-jar"), "Restart is a direct Java command");
        check(command.get(3).equals(home.toAbsolutePath().normalize().resolve("TrueOpsToolkit.jar").toString()), "Spaced launcher path remains one argument");
        check(command.get(4).equals("--updated") && command.get(5).equals(VERSION), "Updated version is passed to the new launcher");
        check(command.get(0).toLowerCase(java.util.Locale.ROOT).endsWith(ToolkitUpdateManager.isWindows() ? "javaw.exe" : "java"), "Windows reopens with javaw");
    }

    private static void realJavaRestart(Path home) throws Exception {
        Fixture fixture = fixture(home, probeJar());
        Path report = screenshotReport;
        VisibleProgress progress = new VisibleProgress(report);
        ToolkitUpdater.UpdateServices services = new ToolkitUpdater.UpdateServices() {
            @Override void waitForExit(String processId, Path target) { }
            @Override boolean install(ToolkitUpdateManager.PendingUpdate pending, Path target, ToolkitUpdater.Progress visible) throws IOException {
                return ToolkitUpdater.installWithProgress(pending, target, null, visible);
            }
        };
        try {
            check(ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Real Java child is launched after successful synthetic update");
            Path result = home.resolve("restarted.properties");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(result) && System.nanoTime() < deadline) Thread.sleep(25);
            check(Files.isRegularFile(result), "Fresh installed JAR stub executes through javaw");
            Properties recorded = new Properties();
            try (InputStream input = Files.newInputStream(result)) { recorded.load(input); }
            check("--updated".equals(recorded.getProperty("argument.0")) && VERSION.equals(recorded.getProperty("argument.1")), "Real child receives update notice arguments");
            check(home.toAbsolutePath().normalize().toString().equals(recorded.getProperty("working.directory")), "Real child starts in the installation folder with spaces");
            check("false".equals(recorded.getProperty("handoff.marker")), "Real child sees no update handoff marker");
            check(!Files.exists(home.resolve(ToolkitUpdateManager.REOPEN_ACK)), "Helper consumes fresh readiness acknowledgement after reopening");
            check(progress.captured.containsAll(Arrays.asList("verifying", "installing", "reopening")) || GraphicsEnvironment.isHeadless(), "Visible installer phase screenshots captured");
        } finally { progress.close(); }

        Fixture failure = fixture(home.resolve("failure"));
        VisibleProgress failedProgress = new VisibleProgress(report);
        Services failing = new Services();
        failing.hook = count -> { if (count == 7) throw new IOException("Synthetic failure for visible rollback check"); };
        try {
            check(!ToolkitUpdater.runUpdate(failure.pending, home.resolve("failure"), "123", failedProgress, failing), "Real installer progress remains visible on failure");
            check(failedProgress.captured.contains("failure") || GraphicsEnvironment.isHeadless(), "Failure window screenshot captured");
        } finally { failedProgress.close(); }
    }

    private static void exitedChildDoesNotConfirmRestart(Path home) throws Exception {
        Fixture fixture = fixture(home, probeJar(NoReadyProbe.class));
        RecordingProgress progress = new RecordingProgress();
        ToolkitUpdater.UpdateServices services = new ToolkitUpdater.UpdateServices() {
            @Override void waitForExit(String processId, Path target) { }
            @Override boolean install(ToolkitUpdateManager.PendingUpdate pending, Path target, ToolkitUpdater.Progress visible) throws IOException {
                return ToolkitUpdater.installWithProgress(pending, target, null, visible);
            }
        };
        check(!ToolkitUpdater.runUpdate(fixture.pending, home, "123", progress, services), "Child exiting without visible-window acknowledgement fails reopening");
        check(progress.failures == 1 && !progress.closed && progress.lastPercent < 100, "Premature child exit leaves a visible reopening error");
        check(progress.contains("was updated, but it could not reopen"), "Reopening error distinguishes installed files from failed installation");
        check(VERSION.equals(readVersion(home)), "Premature child exit keeps successfully installed version");
        check(!ToolkitUpdateManager.isInstallPending(home), "Premature child exit does not block manual reopening");
    }

    private static void readinessAcknowledgementContract(Path home) throws Exception {
        Files.createDirectories(home);
        String token = "12345678-1234-1234-1234-123456789abc";
        String previous = System.getProperty(ToolkitUpdateManager.REOPEN_TOKEN_PROPERTY);
        long launchedAt = System.currentTimeMillis() - 1000;
        try {
            System.setProperty(ToolkitUpdateManager.REOPEN_TOKEN_PROPERTY, token);
            ToolkitUpdateManager.reportReopened(home, VERSION);
            check(ToolkitUpdateManager.hasReopened(home, VERSION, token, launchedAt), "Visible launcher acknowledges version and unique restart token");
            check(!ToolkitUpdateManager.hasReopened(home, "2.0.0", token, launchedAt), "Another version cannot confirm readiness");
            check(!ToolkitUpdateManager.hasReopened(home, VERSION, "another-token", launchedAt), "Another restart cannot confirm readiness");
            check(!ToolkitUpdateManager.hasReopened(home, VERSION, token, System.currentTimeMillis() + 1000), "A stale acknowledgement cannot confirm readiness");
            Files.delete(home.resolve(ToolkitUpdateManager.REOPEN_ACK));
            System.setProperty(ToolkitUpdateManager.REOPEN_TOKEN_PROPERTY, "invalid-token");
            ToolkitUpdateManager.reportReopened(home, VERSION);
            check(!Files.exists(home.resolve(ToolkitUpdateManager.REOPEN_ACK)), "Ordinary or invalid-token launches cannot acknowledge an update");
        } finally {
            if (previous == null) System.clearProperty(ToolkitUpdateManager.REOPEN_TOKEN_PROPERTY);
            else System.setProperty(ToolkitUpdateManager.REOPEN_TOKEN_PROPERTY, previous);
        }
    }

    public static final class NoReadyProbe {
        public static void main(String[] args) { /* Simulate a launcher that exits before showing its main window. */ }
    }

    /** The installed fixture's only executable code; it records launch arguments and exits. */
    public static final class RestartProbe {
        public static void main(String[] args) throws Exception {
            Path home = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Properties recorded = new Properties();
            recorded.setProperty("working.directory", home.toString());
            recorded.setProperty("handoff.marker", Boolean.toString(Files.exists(home.resolve(".updates/installing.properties"))));
            for (int i = 0; i < args.length; i++) recorded.setProperty("argument." + i, args[i]);
            Path temporary = home.resolve("restarted.properties.tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) { recorded.store(output, "Synthetic Java reopening check"); }
            Files.move(temporary, home.resolve("restarted.properties"), StandardCopyOption.ATOMIC_MOVE);
            // This synthetic stub replaces the real Main.setVisible readiness callback.
            Properties ready = new Properties();
            ready.setProperty("app.version", args[1]);
            ready.setProperty("restart.token", System.getProperty("trueops.update.restart.token", ""));
            ready.setProperty("ready", Long.toString(System.currentTimeMillis()));
            Path ack = home.resolve(".updates/reopened.properties");
            Path readyTemporary = home.resolve(".updates/reopened.properties.tmp");
            try (OutputStream output = Files.newOutputStream(readyTemporary)) { ready.store(output, "Synthetic visible window acknowledgement"); }
            Files.move(readyTemporary, ack, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static final class VisibleProgress implements ToolkitUpdater.Progress {
        final ToolkitUpdater.Progress window;
        final Path report;
        final Set<String> captured = new HashSet<String>();
        VisibleProgress(Path report) throws IOException {
            this.report = report;
            Files.createDirectories(report);
            window = GraphicsEnvironment.isHeadless() ? new RecordingProgress() : UpdaterProgressWindow.open();
        }
        @Override public void update(String message, int percent) {
            window.update(message, percent);
            if (message.startsWith("Verifying")) capture("verifying");
            else if (message.startsWith("Installing application files")) capture("installing");
            else if (message.contains("Reopening")) capture("reopening");
        }
        @Override public void failed(String message) { window.failed(message); capture("failure"); }
        @Override public void close() { window.close(); }
        private void capture(String name) {
            if (GraphicsEnvironment.isHeadless() || !captured.add(name)) return;
            try {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window item : Window.getWindows()) {
                        if (!(item instanceof JFrame) || !item.isVisible()
                                || !"TrueOps Toolkit Update".equals(((JFrame) item).getTitle())) continue;
                        layout(item); // Capture final layout instead of Swing's queued intermediate HTML revalidation.
                        BufferedImage image = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = image.createGraphics();
                        try { item.paint(graphics); } finally { graphics.dispose(); }
                        try { ImageIO.write(image, "png", report.resolve("updater-" + name + ".png").toFile()); }
                        catch (IOException failure) { throw new IllegalStateException(failure); }
                        return;
                    }
                    throw new AssertionError("Visible updater window was not found");
                });
            } catch (Exception failure) { throw new AssertionError("Updater screenshot failed", failure); }
        }
        private static void layout(java.awt.Container parent) {
            parent.doLayout();
            for (java.awt.Component child : parent.getComponents()) {
                if (child instanceof java.awt.Container) layout((java.awt.Container) child);
            }
        }
    }

    private static class Services extends ToolkitUpdater.UpdateServices {
        int restarts;
        ToolkitUpdater.MutationHook hook;
        @Override void waitForExit(String processId, Path home) { }
        @Override boolean install(ToolkitUpdateManager.PendingUpdate pending, Path home, ToolkitUpdater.Progress progress) throws IOException {
            return ToolkitUpdater.installWithProgress(pending, home, hook, progress);
        }
        @Override void restart(Path home, String version) throws IOException {
            check(!Files.exists(home.resolve(".updates/installing.properties")), "Marker is removed before restart");
            for (String name : new String[] { ".launcher.lock", ".active.lock" }) {
                try (FileChannel channel = FileChannel.open(home.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock lock = channel.tryLock()) {
                    check(lock != null, "Update releases " + name + " before restart");
                }
            }
            check(VERSION.equals(readVersion(home)), "Restart uses fresh installed files");
            restarts++;
        }
    }

    private static final class RecordingProgress implements ToolkitUpdater.Progress {
        final List<String> messages = new ArrayList<String>();
        int lastPercent, failures;
        boolean closed;
        @Override public void update(String message, int percent) { messages.add(message); lastPercent = percent; }
        @Override public void failed(String message) { messages.add(message); failures++; }
        @Override public void close() { closed = true; }
        boolean contains(String text) { for (String message : messages) if (message.contains(text)) return true; return false; }
    }

    private static Fixture fixture(Path home) throws Exception { return fixture(home, jar(VERSION, true)); }

    private static Fixture fixture(Path home, byte[] launcher) throws Exception {
        Files.createDirectories(home);
        write(home.resolve("TrueOpsToolkit.jar"), "old application");
        write(home.resolve("version.properties"), "app.version=1.0.0\n");
        write(home.resolve("config/credentials.dat"), "synthetic encrypted vault");
        Path original = home.resolve("new-release.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(original))) {
            entry(zip, "TrueOpsToolkit.jar", launcher);
            entry(zip, "version.properties", bytes("app.version=" + VERSION + "\n"));
            for (String doc : new String[] { "README.md", "CHANGELOG.md", "RELEASE.md" }) entry(zip, doc, bytes("new docs"));
            String[] modules = { "approve", "approve-ref", "delete", "tk07" };
            String[] jars = { "TrueOps-Approve.jar", "TrueOps-ApproveByRef.jar", "TrueOps-DeleteSite.jar", "TrueOps-TK07.jar" };
            for (int i = 0; i < modules.length; i++) {
                String prefix = "modules/" + modules[i] + "/";
                entry(zip, prefix + jars[i], jar(VERSION, false));
                entry(zip, prefix + "lib/runtime.jar", jar(null, false));
                write(home.resolve(prefix + jars[i]), "old module");
            }
        }
        final Properties properties = new Properties();
        properties.setProperty("app.version", VERSION);
        properties.setProperty("archive.root", ROOT);
        properties.setProperty("archive.sha256", ToolkitUpdateManager.sha256(original));
        properties.setProperty("archive.url", ToolkitUpdateManager.REPOSITORY + "/releases/download/v" + VERSION
                + "/TrueOpsToolkit-" + VERSION + "-windows.zip");
        ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null,
                new ToolkitUpdateManager.ReleaseSource() {
                    @Override public Properties manifest() { return properties; }
                    @Override public void download(String address, Path destination) throws IOException {
                        Files.copy(original, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                });
        ToolkitUpdateManager.writeInstallMarker(home, pending.directory);
        return new Fixture(pending, launcher);
    }

    private static byte[] jar(String version, boolean helper) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) manifest.getMainAttributes().putValue("Implementation-Version", version);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            for (String name : helper ? new String[] { "trueopstoolkit/ToolkitUpdater.class", "trueopstoolkit/ToolkitUpdateManager.class" }
                    : new String[] { "synthetic.txt" }) {
                jar.putNextEntry(new ZipEntry(name)); jar.write(bytes("synthetic fixture")); jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] probeJar() throws IOException { return probeJar(RestartProbe.class); }

    private static byte[] probeJar(Class<?> probe) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, probe.getName());
        manifest.getMainAttributes().putValue("Implementation-Version", VERSION);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String resource = probe.getName().replace('.', '/') + ".class";
        try (JarOutputStream jar = new JarOutputStream(output, manifest);
                InputStream code = ToolkitUpdaterLifecycleTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (code == null) throw new IOException("Synthetic restart probe class was not found.");
            jar.putNextEntry(new ZipEntry(resource));
            byte[] buffer = new byte[4096];
            int count;
            while ((count = code.read(buffer)) >= 0) jar.write(buffer, 0, count);
            jar.closeEntry();
        }
        return output.toByteArray();
    }
    private static void entry(ZipOutputStream zip, String name, byte[] value) throws IOException {
        zip.putNextEntry(new ZipEntry(ROOT + "/" + name)); zip.write(value); zip.closeEntry();
    }
    private static String readVersion(Path home) throws IOException {
        Properties value = new Properties();
        try (java.io.InputStream input = Files.newInputStream(home.resolve("version.properties"))) { value.load(input); }
        return value.getProperty("app.version");
    }
    private static final class Fixture {
        final ToolkitUpdateManager.PendingUpdate pending;
        final byte[] launcherJar;
        Fixture(ToolkitUpdateManager.PendingUpdate pending, byte[] launcherJar) { this.pending = pending; this.launcherJar = launcherJar; }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void write(Path path, String value) throws IOException { Files.createDirectories(path.getParent()); Files.write(path, bytes(value)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
