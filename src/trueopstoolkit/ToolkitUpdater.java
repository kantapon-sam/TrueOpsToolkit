package trueopstoolkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Separate, Java 8 compatible installer. Only application-owned files are replaceable. */
public final class ToolkitUpdater {
    private static final String[] MODULES = { "tuar", "approve", "approve-ref", "delete" };
    private static final String[] MODULE_JARS = { "TrueOps-TUAR.jar", "TrueOps-Approve.jar",
        "TrueOps-ApproveByRef.jar", "TrueOps-DeleteSite.jar" };
    private static final Set<String> ROOT_FILES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "TrueOpsToolkit.jar", "version.properties", "README.md", "CHANGELOG.md", "RELEASE.md")));
    private static final int ENTRY_LIMIT = 4096;
    private static final long EXTRACT_LIMIT = 1536L * 1024 * 1024;

    private ToolkitUpdater() { }

    public static void main(String[] args) {
        Path home = null;
        Path stage = null;
        try {
            if (args.length != 6 || !"--home".equals(args[0]) || !"--stage".equals(args[2])
                    || !"--pid".equals(args[4]) || !args[5].matches("[1-9][0-9]{0,18}")) {
                throw new IOException("Invalid updater arguments.");
            }
            home = Paths.get(args[1]).toAbsolutePath().normalize();
            stage = Paths.get(args[3]).toAbsolutePath().normalize();
            ToolkitUpdateManager.PendingUpdate pending = ToolkitUpdateManager.readPlan(home, stage);
            waitForLauncher(args[5], home);
            installAfterExit(pending, home);
            ToolkitUpdateManager.log(home, "Update " + pending.version + " installed. Open TrueOps Toolkit to use it.");
            cleanupCompleted(stage);
        } catch (Exception failure) {
            // Do not log arbitrary server content, URLs, environment values, or account information.
            ToolkitUpdateManager.log(home, "Update installation failed (" + failure.getClass().getSimpleName()
                    + "). Previous application files were retained or restored; staged recovery files remain in .updates.");
        } finally {
            ToolkitUpdateManager.clearInstallMarker(home, stage);
        }
    }

    private static void installAfterExit(ToolkitUpdateManager.PendingUpdate pending, Path home)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try { install(pending, home, null); return; }
            catch (BusyUpdateException busy) {
                if (System.nanoTime() >= deadline) throw busy;
                Thread.sleep(150L); // A promptly reopened launcher sees the handoff marker and exits.
            }
        }
    }

    interface MutationHook { void afterChange(int completedChanges) throws IOException; }
    private static final class BusyUpdateException extends IOException {
        BusyUpdateException() { super("A toolkit process is still running; update was not applied."); }
    }

    /** Tests call this directly with synthetic releases; no process termination or network is involved. */
    static void install(ToolkitUpdateManager.PendingUpdate pending, Path home, MutationHook hook) throws IOException {
        Path target = home.toAbsolutePath().normalize();
        ToolkitUpdateManager.validateStaging(target, pending.directory);
        Path launcherLock = target.resolve(".launcher.lock"), activeLock = target.resolve(".active.lock");
        requireSafePath(target, launcherLock);
        requireSafePath(target, activeLock);
        try (FileChannel launcherChannel = FileChannel.open(launcherLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock launcher = acquire(launcherChannel);
                FileChannel activeChannel = FileChannel.open(activeLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock active = acquire(activeChannel)) {
            ToolkitUpdateManager.verifyArchive(pending);
            String existing = installedVersion(target);
            if (existing != null && ToolkitUpdateManager.compareVersions(pending.version, existing) <= 0) {
                ToolkitUpdateManager.log(home, "Skipped a staged release because this installation is already the same version or newer.");
                return;
            }
            Path work = Files.createTempDirectory(pending.directory, "install-");
            Path incoming = work.resolve("incoming"), backup = work.resolve("backup");
            boolean recoverable = true;
            try {
                Set<String> incomingFiles = extractAndValidate(pending, incoming);
                Set<String> changes = new TreeSet<String>(incomingFiles);
                changes.addAll(installedLibraries(target)); // Obsolete runtime JARs must not shadow newer dependencies.
                Set<String> existed = new HashSet<String>();
                Files.createDirectories(backup);
                for (String name : changes) {
                    Path destination = target.resolve(name);
                    requireSafePath(target, destination);
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Managed update target is not a regular file.");
                        Path saved = backup.resolve(name);
                        Files.createDirectories(saved.getParent());
                        Files.copy(destination, saved, StandardCopyOption.COPY_ATTRIBUTES);
                        existed.add(name);
                    }
                }
                List<String> changed = new ArrayList<String>();
                try {
                    for (String name : changes) {
                        Path destination = target.resolve(name);
                        requireSafePath(target, destination);
                        changed.add(name); // Include a failing replacement in rollback, too.
                        if (incomingFiles.contains(name)) replaceFile(incoming.resolve(name), destination);
                        else Files.deleteIfExists(destination);
                        if (hook != null) hook.afterChange(changed.size());
                    }
                } catch (IOException | RuntimeException failure) {
                    IOException restoreFailure = null;
                    Collections.reverse(changed);
                    for (String name : changed) {
                        try {
                            Path destination = target.resolve(name);
                            requireSafePath(target, destination);
                            if (existed.contains(name)) replaceFile(backup.resolve(name), destination);
                            else Files.deleteIfExists(destination);
                        } catch (IOException restore) {
                            if (restoreFailure == null) restoreFailure = restore;
                            else restoreFailure.addSuppressed(restore);
                        }
                    }
                    if (restoreFailure != null) {
                        recoverable = false;
                        failure.addSuppressed(restoreFailure);
                        ToolkitUpdateManager.log(target, "Rollback needs recovery from .updates backup; do not remove the staged backup.");
                    } else ToolkitUpdateManager.log(target, "Update failed; rollback restored all previous application files.");
                    throw failure;
                }
            } finally {
                // Retain backups when restoration itself failed, so nothing destroys the recovery copy.
                if (recoverable) deleteTree(work);
            }
        }
    }

    private static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException busy) { }
        throw new BusyUpdateException();
    }

    private static String installedVersion(Path home) throws IOException {
        Path source = home.resolve("version.properties");
        requireSafePath(home, source);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.size(source) > 16384) throw new IOException("Installed version descriptor is invalid.");
        Properties version = new Properties();
        try (InputStream input = Files.newInputStream(source)) { version.load(input); }
        String value = version.getProperty("app.version", "").trim();
        if (!ToolkitUpdateManager.validVersion(value)) throw new IOException("Installed application is not a stable release.");
        return value;
    }

    static Set<String> extractAndValidate(ToolkitUpdateManager.PendingUpdate pending, Path destination) throws IOException {
        Path archive = pending.directory.resolve("release.zip");
        if (Files.size(archive) > ToolkitUpdateManager.ARCHIVE_LIMIT) throw new IOException("Update archive exceeds the size limit.");
        rejectSpecialZipEntries(archive);
        requireSafePath(pending.directory, destination);
        Files.createDirectories(destination);
        Set<String> names = new HashSet<String>(), managed = new TreeSet<String>();
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> members = zip.entries();
            while (members.hasMoreElements()) {
                ZipEntry entry = members.nextElement();
                if (++entries > ENTRY_LIMIT) throw new IOException("Update archive contains too many entries.");
                String name = entry.getName();
                validateZipName(name, pending.root);
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Update archive contains duplicate paths.");
                if (entry.getSize() < 0 || entry.getSize() > EXTRACT_LIMIT) throw new IOException("Update member size is invalid.");
                if (entry.isDirectory()) continue;
                String relative = name.substring(pending.root.length() + 1);
                boolean allowed = managedFile(relative);
                Path file = destination.resolve(relative).normalize();
                if (!file.startsWith(destination)) throw new IOException("Update path escaped its staging folder.");
                OutputStream output = null;
                if (allowed) {
                    requireSafePath(destination, file);
                    Files.createDirectories(file.getParent());
                    output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW);
                }
                long size = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        total += count; // Includes skipped data templates; ignored entries cannot hide a ZIP bomb.
                        if (total > EXTRACT_LIMIT) throw new IOException("Update archive expands beyond the size limit.");
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Update validation was interrupted.");
                        if (output != null) output.write(buffer, 0, count);
                    }
                } finally { if (output != null) output.close(); }
                if (size != entry.getSize()) throw new IOException("Update member was incomplete.");
                if (allowed) {
                    if (size == 0) throw new IOException("Update contains an empty application file.");
                    managed.add(relative);
                }
            }
        }
        if (!managed.containsAll(ROOT_FILES)) throw new IOException("Update is missing launcher, documentation, or version files.");
        String packagedVersion = installedVersion(destination);
        if (!pending.version.equals(packagedVersion)) throw new IOException("Update version descriptor does not match the release.");
        validateJar(destination.resolve("TrueOpsToolkit.jar"), pending.version);
        for (int i = 0; i < MODULES.length; i++) {
            String prefix = "modules/" + MODULES[i] + "/";
            String app = prefix + MODULE_JARS[i];
            if (!managed.contains(app)) throw new IOException("Update is missing a toolkit module.");
            validateJar(destination.resolve(app), pending.version);
            boolean hasLibrary = false;
            for (String name : managed) if (name.startsWith(prefix + "lib/")) {
                validateJar(destination.resolve(name), null);
                hasLibrary = true;
            }
            if (!hasLibrary) throw new IOException("Update is missing module runtime libraries.");
        }
        return managed;
    }

    static boolean managedFile(String name) {
        if (ROOT_FILES.contains(name)) return true;
        for (int i = 0; i < MODULES.length; i++) {
            String prefix = "modules/" + MODULES[i] + "/";
            if ((prefix + MODULE_JARS[i]).equals(name)) return true;
            if (name.startsWith(prefix + "lib/")) {
                String file = name.substring((prefix + "lib/").length());
                return !file.contains("/") && file.matches("[A-Za-z0-9_][A-Za-z0-9_.+\\-]*\\.jar");
            }
        }
        return false;
    }

    private static Set<String> installedLibraries(Path home) throws IOException {
        Set<String> paths = new TreeSet<String>();
        for (String module : MODULES) {
            String prefix = "modules/" + module + "/lib/";
            Path directory = home.resolve(prefix);
            requireSafePath(home, directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Module library location is not a directory.");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) if (managedFile(prefix + file.getFileName())) {
                    requireSafePath(home, file);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Module library is not a regular file.");
                    paths.add(prefix + file.getFileName());
                }
            }
        }
        return paths;
    }

    private static void validateJar(Path path, String version) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (version != null) {
                Manifest manifest = jar.getManifest();
                if (manifest == null || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))) {
                    throw new IOException("Application JAR version does not match the update descriptor.");
                }
            }
        }
    }

    private static void validateZipName(String name, String root) throws IOException {
        if (name.isEmpty() || name.length() > 512 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('\0') >= 0 || !name.startsWith(root + "/")) {
            throw new IOException("Unsafe path in update archive.");
        }
        String[] components = name.split("/", -1);
        for (int i = 0; i < components.length; i++) {
            String part = components[i];
            if (part.isEmpty() && i == components.length - 1) continue;
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || !part.trim().equals(part) || part.matches(".*[\\x00-\\x1f<>\"|?*].*")
                    || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw new IOException("Unsafe path component in update archive.");
            }
        }
    }

    /** Java 8 ZipEntry omits Unix mode bits; read the central directory before extracting anything. */
    private static void rejectSpecialZipEntries(Path archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            long length = file.length();
            if (length < 22) throw new IOException("Invalid update ZIP.");
            int tailSize = (int) Math.min(length, 65557L);
            byte[] tail = new byte[tailSize];
            file.seek(length - tailSize);
            file.readFully(tail);
            int end = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) { end = i; break; }
            }
            if (end < 0 || u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                    || u16(tail, end + 8) != u16(tail, end + 10)) throw new IOException("Unsupported update ZIP directory.");
            int count = u16(tail, end + 10);
            long size = u32(tail, end + 12), offset = u32(tail, end + 16);
            if (count == 0 || count > ENTRY_LIMIT || size == 0xffffffffL || offset == 0xffffffffL
                    || offset + size != length - tailSize + end) throw new IOException("Invalid update ZIP directory bounds.");
            file.seek(offset);
            for (int i = 0; i < count; i++) {
                byte[] header = new byte[46];
                file.readFully(header);
                if (u32(header, 0) != 0x02014b50L) throw new IOException("Invalid update ZIP member.");
                int flags = u16(header, 8);
                long attributes = u32(header, 38);
                int kind = (int) ((attributes >>> 16) & 0170000);
                if ((flags & 1) != 0 || (kind != 0 && kind != 0100000 && kind != 0040000)
                        || (attributes & 0x400) != 0 || u16(header, 34) != 0) {
                    throw new IOException("Update ZIP contains an encrypted, linked, or special file.");
                }
                long next = file.getFilePointer() + u16(header, 28) + u16(header, 30) + u16(header, 32);
                if (next > offset + size) throw new IOException("Invalid update ZIP member bounds.");
                file.seek(next);
            }
            if (file.getFilePointer() != offset + size) throw new IOException("Invalid update ZIP member count.");
        }
    }

    private static int u16(byte[] data, int offset) { return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }
    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }

    /** Refuse symbolic links, junctions/reparse points, and paths outside the selected installation. */
    static void requireSafePath(Path root, Path file) throws IOException {
        Path base = root.toAbsolutePath().normalize(), value = file.toAbsolutePath().normalize();
        if (!value.startsWith(base)) throw new IOException("Update path is outside its installation.");
        for (Path current = value; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Update paths must not contain links or special files.");
            }
            if (current.equals(base)) break; // The user-selected root may live beneath OneDrive or another mounted ancestor.
        }
    }

    private static void replaceFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".toolkit-update-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        requireSafePath(root, root);
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            requireSafePath(root, path);
            Files.deleteIfExists(path);
        }
    }

    private static void waitForLauncher(String processId, Path home) throws IOException, InterruptedException {
        if (!ToolkitUpdateManager.isWindows()) throw new IOException("Automatic installation requires Windows.");
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) throw new IOException("Windows system directory was not found.");
        String powershell = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        String script = "$p=Get-Process -Id ([int]$env:TRUEOPS_UPDATE_PARENT_PID) -ErrorAction SilentlyContinue;"
                + "if($p){if(-not $p.WaitForExit(180000)){exit 2}};exit 0";
        ProcessBuilder builder = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
        builder.environment().put("TRUEOPS_UPDATE_PARENT_PID", processId);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/update.log").toFile()));
        Process process = builder.start();
        try {
            if (!process.waitFor(190, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("Launcher did not exit; update was not applied.");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void cleanupCompleted(Path stage) {
        try {
            Files.deleteIfExists(stage.resolve("release.zip"));
            Files.deleteIfExists(stage.resolve("update.properties"));
            // The helper JAR is open on Windows until JVM shutdown. Delete in child-before-parent order.
            stage.toFile().deleteOnExit();
            stage.resolve("updater.jar").toFile().deleteOnExit();
        } catch (IOException ignored) { /* Harmless staging remnants can be removed after the helper exits. */ }
    }
}
