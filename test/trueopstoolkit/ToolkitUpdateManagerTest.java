package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Synthetic fixtures only: no GitHub, browser, real user data, or process exits. */
public final class ToolkitUpdateManagerTest {
    private static final String NEW_VERSION = "1.1.0";
    private static final String ROOT = "TrueOpsToolkit-" + NEW_VERSION;
    private static final String[] MODULES = { "approve", "approve-ref", "delete" };
    private static final String[] JARS = { "TrueOps-Approve.jar", "TrueOps-ApproveByRef.jar", "TrueOps-DeleteSite.jar" };
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--verify-release".equals(args[0])) {
            verifyRealRelease(Paths.get(args[1]).toAbsolutePath(), Paths.get(args[2]).toAbsolutePath());
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Usage: ToolkitUpdateManagerTest [--verify-release ARCHIVE UPDATE_PROPERTIES]");
        Path work = Files.createTempDirectory("trueops-update-test-");
        try {
            testVersionsAndUrls();
            testNoUpdate(work);
            testSuccessfulInstall(work);
            testLegacyTuarCompatibility(work);
            testRollback(work);
            testActiveProcess(work);
            testInvalidArchives(work);
            System.out.println("PASS: toolkit updater (" + checks + " checks): staging, integrity, no downgrade, safe ZIPs, preservation, operation locks, and rollback.");
        } finally { ToolkitUpdater.deleteTree(work); }
    }

    private static void verifyRealRelease(Path archive, Path descriptor) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(descriptor)) { properties.load(input); }
        Path temporary = Files.createTempDirectory("trueops-release-validation-");
        try {
            Source source = new Source(archive, properties.getProperty("app.version"));
            source.properties.clear();
            source.properties.putAll(properties);
            ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(temporary, "0.0.0", null, source);
            check(pending != null, "Stable release newer than 0.0.0");
            check(Files.isRegularFile(pending.directory.resolve("update.properties")), "Validated update plan written");
            System.out.println("PASS: real release ZIP, descriptor, SHA-256, safe paths, module/library allowlist, and all application versions: " + pending.version);
        } finally { ToolkitUpdater.deleteTree(temporary); }
    }

    private static void testVersionsAndUrls() throws Exception {
        check(ToolkitUpdateManager.compareVersions("1.10.0", "1.9.99") > 0, "Numeric semver ordering");
        check(ToolkitUpdateManager.compareVersions("2.0.0", "1.99.99") > 0, "Major semver ordering");
        for (String value : new String[] { "v1.0.0", "01.0.0", "1.0", "1.0.0-beta", "1.0.0+build", "2147483648.0.0", "1.0.0\n" }) {
            check(!ToolkitUpdateManager.validVersion(value), "Reject nonstable or invalid version");
        }
        ToolkitUpdateManager.validateDownloadUrl(new URL(ToolkitUpdateManager.FEED));
        ToolkitUpdateManager.validateDownloadUrl(new URL("https://release-assets.githubusercontent.com/github-production-release-asset/123?token=synthetic"));
        for (String address : new String[] { "http://github.com/kantapon-sam/TrueOpsToolkit/releases/latest", "https://github.com/other/repo/releases/latest",
                "https://github.com.evil.example/kantapon-sam/TrueOpsToolkit/releases/latest", "https://github.com:444/kantapon-sam/TrueOpsToolkit/releases/latest",
                "https://user@github.com/kantapon-sam/TrueOpsToolkit/releases/latest", "https://example.com/release.zip", "https://raw.githubusercontent.com/owner/repo/file" }) {
            fails(() -> ToolkitUpdateManager.validateDownloadUrl(new URL(address)), "Untrusted download URL");
        }
        check(!ToolkitUpdater.managedFile("config/credentials.dat"), "Credential vault is outside managed paths");
        check(!ToolkitUpdater.managedFile("modules/approve/Login.txt"), "Login settings are outside managed paths");
        check(!ToolkitUpdater.managedFile("modules/approve/settings.csv"), "Current module settings are outside managed paths");
        check(!ToolkitUpdater.managedFile("drivers/chromedriver.exe"), "Driver is outside managed paths");
        check(!ToolkitUpdater.managedFile("Run-TrueOpsToolkit.cmd"), "Legacy command launcher is no longer managed or required");
        check(!ToolkitUpdater.managedFile("Run-TrueOpsToolkit.bat"), "Batch launchers cannot be installed");
        check(!ToolkitUpdater.managedFile("TrueOpsToolkit.exe"), "Executable launchers cannot be installed");
        check(!ToolkitUpdater.managedFile("modules/approve/lib/nested/data.jar"), "Only flat runtime library JARs are managed");
        check(!ToolkitUpdater.managedFile("modules/tuar/TrueOps-TUAR.jar"), "Separately maintained TUAR executable is outside managed paths");
        check(!ToolkitUpdater.managedFile("modules/tuar/lib/old-runtime.jar"), "Separately maintained TUAR libraries are outside managed paths");
    }

    private static void testNoUpdate(Path work) throws Exception {
        Path home = createHome(work.resolve("no-update"));
        Source source = fixture(work.resolve("no-update.zip"), members(), NEW_VERSION);
        check(ToolkitUpdateManager.checkAndStage(home, NEW_VERSION, null, source) == null, "Same version is ignored");
        check(ToolkitUpdateManager.checkAndStage(home, "2.0.0", null, source) == null, "Downgrades are ignored");
        check(ToolkitUpdateManager.checkAndStage(home, "development", null, source) == null, "IDE build is untouched");
        check(source.downloads == 0, "No unnecessary download");
        source.absent = true;
        check(ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source) == null, "No first release (404) is normal");
        source.absent = false;
        source.properties.setProperty("archive.url", "https://github.com/other/repo/releases/download/v1.1.0/package.zip");
        fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source), "Feed cannot choose another repository");
    }

    private static void testSuccessfulInstall(Path work) throws Exception {
        Path home = createHome(work.resolve("install"));
        Map<String, byte[]> preserved = userData();
        for (Map.Entry<String, byte[]> entry : preserved.entrySet()) write(home.resolve(entry.getKey()), entry.getValue());
        Source source = fixture(work.resolve("good.zip"), members(), NEW_VERSION);
        try (ZipFile archive = new ZipFile(source.archive.toFile())) {
            check(archive.getEntry(ROOT + "/modules/tuar/TrueOps-TUAR.jar") == null, "New installer accepts a complete release containing only three PROMS tools");
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toLowerCase(java.util.Locale.ROOT);
                check(!name.matches(".*\\.(exe|bat|cmd)$"), "Clean update fixture contains no executable or batch files");
            }
        }
        ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source);
        check(pending != null && NEW_VERSION.equals(pending.getVersion()), "New release staged");
        check(!ToolkitUpdateManager.isInstallPending(home), "Downloading does not block opening the application");
        ToolkitUpdateManager.writeInstallMarker(home, pending.directory);
        check(ToolkitUpdateManager.isInstallPending(home), "Dispatched installation blocks immediate reopening");
        ToolkitUpdateManager.clearInstallMarker(home, pending.directory.resolveSibling("release-other"));
        check(ToolkitUpdateManager.isInstallPending(home), "Another helper cannot clear the current handoff marker");
        ToolkitUpdateManager.clearInstallMarker(home, pending.directory);
        check(!ToolkitUpdateManager.isInstallPending(home), "Finished helper releases the handoff marker");
        write(home.resolve(".updates/installing.properties"), bytes("created=1\nstage=" + pending.directory.getFileName() + "\n"));
        check(!ToolkitUpdateManager.isInstallPending(home), "Abandoned handoff marker expires");
        Files.delete(home.resolve(".updates/installing.properties"));
        ToolkitUpdateManager.PendingUpdate reused = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source);
        check(pending.directory.equals(reused.directory) && source.downloads == 1, "Repeated checks reuse the verified staged release");
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old launcher"), "Staging does not alter running launcher");
        ToolkitUpdater.install(pending, home, null);
        check(read(home.resolve("version.properties")).contains("app.version=" + NEW_VERSION), "Whole toolkit version changed");
        for (int i = 0; i < MODULES.length; i++) {
            check(Files.size(home.resolve("modules/" + MODULES[i] + "/" + JARS[i])) > 20, "Module installed");
            check(Files.exists(home.resolve("modules/" + MODULES[i] + "/lib/new-runtime.jar")), "New runtime installed");
            check(!Files.exists(home.resolve("modules/" + MODULES[i] + "/lib/obsolete-runtime.jar")), "Obsolete runtime removed");
        }
        for (Map.Entry<String, byte[]> entry : preserved.entrySet()) {
            check(Arrays.equals(entry.getValue(), Files.readAllBytes(home.resolve(entry.getKey()))), "Preserved user-owned file: " + entry.getKey());
        }
        write(home.resolve("version.properties"), bytes("app.version=2.0.0\n"));
        ToolkitUpdater.install(pending, home, null);
        check(read(home.resolve("version.properties")).contains("2.0.0"), "Stale staged update cannot downgrade an advanced installation");
    }

    private static void testRollback(Path work) throws Exception {
        Path home = createHome(work.resolve("rollback"));
        Map<String, byte[]> legacy = legacyTuarFiles();
        Map<String, byte[]> before = snapshotManaged(home);
        Source source = fixture(work.resolve("rollback.zip"), members(), NEW_VERSION);
        ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source);
        fails(() -> ToolkitUpdater.install(pending, home, count -> {
            if (count == 12) throw new IOException("Synthetic file lock failure halfway through installation");
        }), "Partial replacement fails");
        Map<String, byte[]> after = snapshotManaged(home);
        check(before.keySet().equals(after.keySet()), "Rollback removes newly installed files and restores obsolete libraries");
        for (String name : before.keySet()) check(Arrays.equals(before.get(name), after.get(name)), "Rollback restored " + name);
        for (Map.Entry<String, byte[]> entry : legacy.entrySet()) {
            check(Arrays.equals(entry.getValue(), Files.readAllBytes(home.resolve(entry.getKey()))), "Rollback leaves separate TUAR file untouched: " + entry.getKey());
        }
        check(read(home.resolve("logs/update.log")).contains("rollback restored"), "Rollback recorded without account data");
    }

    private static void testLegacyTuarCompatibility(Path work) throws Exception {
        Path home = createHome(work.resolve("legacy-tuar"));
        Map<String, byte[]> values = members();
        // Releases include harmless legacy shortcuts while supporting the immutable
        // 1.0.0 validator, including installations that skip intermediate versions.
        values.put(ROOT + "/modules/tuar/TrueOps-TUAR.jar", jar(NEW_VERSION));
        values.put(ROOT + "/modules/tuar/lib/TrueOps-TUAR-compat.jar", jar(NEW_VERSION));
        Source source = fixture(work.resolve("legacy-tuar.zip"), values, NEW_VERSION);
        ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source);
        check(pending != null, "Release with legacy TUAR shortcut is accepted by the three-module contract");
        ToolkitUpdater.install(pending, home, null);
        for (Map.Entry<String, byte[]> entry : legacyTuarFiles().entrySet()) {
            check(Arrays.equals(entry.getValue(), Files.readAllBytes(home.resolve(entry.getKey()))), "New installer preserves separate TUAR file despite compatibility shortcut: " + entry.getKey());
        }
        check(!Files.exists(home.resolve("modules/tuar/lib/TrueOps-TUAR-compat.jar")), "New installer does not add legacy TUAR shortcut libraries");
    }

    private static void testActiveProcess(Path work) throws Exception {
        Path home = createHome(work.resolve("busy"));
        Source source = fixture(work.resolve("busy.zip"), members(), NEW_VERSION);
        ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source);
        for (String lockName : new String[] { ".active.lock", ".launcher.lock" }) {
            try (FileChannel channel = FileChannel.open(home.resolve(lockName), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.lock()) {
                fails(() -> ToolkitUpdater.install(pending, home, null), "Open application/operation prevents installation");
            }
            check(read(home.resolve("TrueOpsToolkit.jar")).equals("old launcher"), "Busy installation is untouched");
        }
        write(pending.directory.resolve("release.zip"), bytes("tampered after successful staging"));
        fails(() -> ToolkitUpdater.install(pending, home, null), "Archive hash is rechecked at install");
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old launcher"), "Changed stage cannot alter app");
    }

    private static void testInvalidArchives(Path work) throws Exception {
        Path home = createHome(work.resolve("invalid"));
        Source badHash = fixture(work.resolve("bad-hash.zip"), members(), NEW_VERSION);
        badHash.properties.setProperty("archive.sha256", repeat('0', 64));
        fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, badHash), "SHA mismatch rejected");
        int index = 0;
        for (String path : new String[] { ROOT + "/ignored/../../escape.txt", ROOT + "/ignored\\..\\escape.txt", "/absolute.txt",
                ROOT + "/ignored/NUL.txt", ROOT + "/ignored/file.txt:stream", ROOT + "/ignored/trailing. ", ROOT + "/README.MD" }) {
            Map<String, byte[]> values = members();
            values.put(path, bytes("ignored data must still pass path validation"));
            Source source = fixture(work.resolve("bad-path-" + index++ + ".zip"), values, NEW_VERSION);
            fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, source), "Unsafe ZIP member rejected even when not installable");
        }
        Map<String, byte[]> incomplete = members();
        incomplete.remove(ROOT + "/modules/delete/TrueOps-DeleteSite.jar");
        Source missing = fixture(work.resolve("missing.zip"), incomplete, NEW_VERSION);
        fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, missing), "Partial toolkit package rejected");
        Map<String, byte[]> mismatch = members();
        mismatch.put(ROOT + "/TrueOpsToolkit.jar", jar("9.0.0"));
        Source wrong = fixture(work.resolve("wrong-version.zip"), mismatch, NEW_VERSION);
        fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, wrong), "Wrong JAR version rejected");
        Source symlink = fixture(work.resolve("symlink.zip"), members(), NEW_VERSION);
        byte[] archive = Files.readAllBytes(symlink.archive);
        boolean changed = false;
        for (int i = 0; i + 46 < archive.length; i++) if ((archive[i] & 255) == 0x50 && (archive[i + 1] & 255) == 0x4b
                && (archive[i + 2] & 255) == 0x01 && (archive[i + 3] & 255) == 0x02) {
            int attributes = 0120777 << 16;
            for (int b = 0; b < 4; b++) archive[i + 38 + b] = (byte) (attributes >>> (8 * b));
            changed = true;
            break;
        }
        check(changed, "Synthetic Unix symlink ZIP fixture constructed");
        Files.write(symlink.archive, archive);
        symlink.properties.setProperty("archive.sha256", ToolkitUpdateManager.sha256(symlink.archive));
        fails(() -> ToolkitUpdateManager.checkAndStage(home, "1.0.0", null, symlink), "ZIP symlink external attributes rejected");
        check(read(home.resolve("TrueOpsToolkit.jar")).equals("old launcher"), "All invalid releases preserve original launcher");
        check(!Files.exists(home.resolve("escape.txt")), "No traversal output created");
        try (java.util.stream.Stream<Path> stages = Files.list(home.resolve(".updates"))) {
            check(stages.count() == 0, "Failed staging removes temporary release data");
        }
    }

    private static Path createHome(Path home) throws IOException {
        write(home.resolve("TrueOpsToolkit.jar"), bytes("old launcher"));
        write(home.resolve("version.properties"), bytes("app.version=1.0.0\n"));
        write(home.resolve("Run-TrueOpsToolkit.cmd"), bytes("old wrapper"));
        write(home.resolve("README.md"), bytes("old readme"));
        for (int i = 0; i < MODULES.length; i++) {
            write(home.resolve("modules/" + MODULES[i] + "/" + JARS[i]), bytes("old module " + MODULES[i]));
            write(home.resolve("modules/" + MODULES[i] + "/lib/obsolete-runtime.jar"), bytes("old runtime " + MODULES[i]));
        }
        for (Map.Entry<String, byte[]> entry : legacyTuarFiles().entrySet()) write(home.resolve(entry.getKey()), entry.getValue());
        return home;
    }

    private static Map<String, byte[]> members() throws IOException {
        Map<String, byte[]> values = new LinkedHashMap<String, byte[]>();
        values.put(ROOT + "/", new byte[0]);
        values.put(ROOT + "/TrueOpsToolkit.jar", jar(NEW_VERSION));
        values.put(ROOT + "/version.properties", bytes("app.version=" + NEW_VERSION + "\n"));
        for (String doc : new String[] { "README.md", "CHANGELOG.md", "RELEASE.md" }) values.put(ROOT + "/" + doc, bytes("new docs " + doc));
        for (int i = 0; i < MODULES.length; i++) {
            values.put(ROOT + "/modules/" + MODULES[i] + "/" + JARS[i], jar(NEW_VERSION));
            values.put(ROOT + "/modules/" + MODULES[i] + "/lib/new-runtime.jar", jar(null));
        }
        for (String name : userData().keySet()) {
            // Clean releases omit executable/batch files; the installation may already contain a driver or older shortcut.
            // TUAR code is added only by the dedicated legacy compatibility fixture.
            if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(exe|bat|cmd)$")
                    && !name.equals("modules/tuar/TrueOps-TUAR.jar") && !name.startsWith("modules/tuar/lib/")) {
                values.put(ROOT + "/" + name, bytes("blank release template, must never replace local data"));
            }
        }
        return values;
    }

    private static Map<String, byte[]> userData() {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (String name : new String[] { "config/credentials.dat", "config/other.properties", "User", "attachments/local.txt", "logs/work.log",
                "Run-TrueOpsToolkit.cmd", "user-scripts/local-task.bat",
                "drivers/chromedriver.exe", "modules/tuar/requests.xlsx", "modules/tuar/config/settings.properties", "modules/tuar/attachments/local.txt",
                "modules/approve/Login.txt", "modules/approve-ref/Login.txt", "modules/approve-ref/Ref_No.csv", "modules/delete/Login.txt",
                "modules/approve/settings.csv", "modules/approve-ref/settings.csv", "modules/delete/settings.csv",
                "modules/delete/Ref_No.csv", "modules/approve/lib/user-notes.txt" }) result.put(name, bytes("synthetic existing data: " + name));
        result.putAll(legacyTuarFiles());
        return result;
    }

    private static Map<String, byte[]> legacyTuarFiles() {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (String name : new String[] { "modules/tuar/TrueOps-TUAR.jar", "modules/tuar/lib/obsolete-runtime.jar",
                "modules/tuar/requests.xlsx", "modules/tuar/config/settings.properties", "modules/tuar/attachments/local.txt" }) {
            result.put(name, bytes("separate TUAR file: " + name));
        }
        return result;
    }

    private static Map<String, byte[]> snapshotManaged(Path home) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        try (java.util.stream.Stream<Path> paths = Files.walk(home)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                String name = home.relativize(file).toString().replace('\\', '/');
                if (ToolkitUpdater.managedFile(name) && Files.isRegularFile(file)) files.put(name, Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static Source fixture(Path archive, Map<String, byte[]> members, String version) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : members.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return new Source(archive, version);
    }

    private static byte[] jar(String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) manifest.getMainAttributes().putValue("Implementation-Version", version);
        try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
            output.putNextEntry(new ZipEntry("synthetic.txt"));
            output.write(ToolkitUpdateManagerTest.bytes("fixture"));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static final class Source implements ToolkitUpdateManager.ReleaseSource {
        final Path archive;
        final Properties properties = new Properties();
        int downloads;
        boolean absent;
        Source(Path archive, String version) throws IOException {
            this.archive = archive;
            properties.setProperty("app.version", version);
            properties.setProperty("archive.root", "TrueOpsToolkit-" + version);
            properties.setProperty("archive.sha256", ToolkitUpdateManager.sha256(archive));
            properties.setProperty("archive.url", ToolkitUpdateManager.REPOSITORY + "/releases/download/v" + version + "/TrueOpsToolkit-" + version + "-windows.zip");
        }
        @Override public Properties manifest() throws IOException {
            if (absent) throw new FileNotFoundException("Synthetic absent first release");
            return properties;
        }
        @Override public void download(String address, Path destination) throws IOException {
            downloads++;
            Files.copy(archive, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    interface Operation { void run() throws Exception; }
    private static void fails(Operation action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(label + " did not fail");
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String read(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static void write(Path file, byte[] content) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, content); }
    private static String repeat(char value, int count) { char[] result = new char[count]; Arrays.fill(result, value); return new String(result); }
}
