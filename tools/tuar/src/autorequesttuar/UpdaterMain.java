package autorequesttuar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.JOptionPane;

/**
 * Runs from a temporary copy of the old JAR after the main application exits.
 */
public final class UpdaterMain {

    private static final long MAX_EXTRACTED_BYTES =
            350L * 1024L * 1024L;
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UpdaterMain() {
    }

    public static void main(String[] args) {
        Path target = null;
        try {
            Map<String, String> options = parseArguments(args);
            Path zip = requireFile(options, "--zip");
            target = requireDirectory(options, "--target");
            Path launchJar = requirePath(options, "--launch");
            String javaCommand = requireValue(options, "--java");
            String cleanup = optionalValue(options, "--cleanup");

            waitForFileToUnlock(launchJar, 30);
            installPackage(zip, target, launchJar);
            Files.deleteIfExists(zip);
            relaunch(javaCommand, launchJar, target);
            log(target, "Update installed successfully.");

            if (!cleanup.isEmpty()) {
                new File(cleanup).deleteOnExit();
            }
        } catch (Exception ex) {
            log(target, "Update failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(
                    null,
                    "Update failed.\n" + ex.getMessage()
                    + "\n\nSee update.log for details.",
                    "Update Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    static void installPackage(
            Path zip,
            Path target,
            Path launchJar) throws IOException {
        Path working = target.resolve(
                ".autorequesttuar-update-"
                + UUID.randomUUID().toString());
        Path extracted = working.resolve("new");
        Path backup = working.resolve("backup");
        Files.createDirectories(extracted);
        Files.createDirectories(backup);

        try {
            extractManagedFiles(zip, extracted);
            Path packagedJar = extracted.resolve(
                    "AutoRequestTUAR.jar");
            Path packagedLib = extracted.resolve("lib");
            if (!Files.isRegularFile(packagedJar)
                    || !Files.isDirectory(packagedLib)) {
                throw new IOException(
                        "Update package is missing the JAR or lib folder.");
            }

            backupManagedFiles(target, launchJar, backup);
            try {
                replaceManagedFiles(
                        extracted,
                        target,
                        launchJar);
            } catch (IOException ex) {
                restoreManagedFiles(target, launchJar, backup);
                throw ex;
            }
        } finally {
            deleteTree(working);
        }
    }

    private static void extractManagedFiles(
            Path zip,
            Path destination) throws IOException {
        long total = 0L;
        try (InputStream input = Files.newInputStream(zip);
                ZipInputStream zipInput =
                        new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!allowedEntry(name)) {
                    zipInput.closeEntry();
                    continue;
                }

                Path resolved = destination.resolve(name).normalize();
                if (!resolved.startsWith(destination)) {
                    throw new IOException(
                            "Blocked unsafe ZIP entry: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    total = copyWithLimit(
                            zipInput,
                            resolved,
                            total);
                }
                zipInput.closeEntry();
            }
        }
    }

    private static long copyWithLimit(
            InputStream input,
            Path destination,
            long previousTotal) throws IOException {
        long total = previousTotal;
        try (java.io.OutputStream output = Files.newOutputStream(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_EXTRACTED_BYTES) {
                    throw new IOException(
                            "Update package exceeded the extraction limit.");
                }
                output.write(buffer, 0, count);
            }
        }
        return total;
    }

    private static boolean allowedEntry(String name) {
        return "AutoRequestTUAR.jar".equals(name)
                || name.startsWith("lib/")
                || "README.md".equals(name)
                || "VERSION.txt".equals(name);
    }

    private static void backupManagedFiles(
            Path target,
            Path launchJar,
            Path backup) throws IOException {
        if (Files.isRegularFile(launchJar)) {
            Files.copy(
                    launchJar,
                    backup.resolve("application.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        copyTreeIfPresent(
                target.resolve("lib"),
                backup.resolve("lib"));
        copyIfPresent(
                target.resolve("README.md"),
                backup.resolve("README.md"));
        copyIfPresent(
                target.resolve("VERSION.txt"),
                backup.resolve("VERSION.txt"));
    }

    private static void replaceManagedFiles(
            Path extracted,
            Path target,
            Path launchJar) throws IOException {
        replaceFile(
                extracted.resolve("AutoRequestTUAR.jar"),
                launchJar);

        Path targetLib = target.resolve("lib");
        deleteTree(targetLib);
        copyTree(extracted.resolve("lib"), targetLib);

        copyIfPresent(
                extracted.resolve("README.md"),
                target.resolve("README.md"));
        copyIfPresent(
                extracted.resolve("VERSION.txt"),
                target.resolve("VERSION.txt"));
    }

    private static void restoreManagedFiles(
            Path target,
            Path launchJar,
            Path backup) throws IOException {
        copyIfPresent(
                backup.resolve("application.jar"),
                launchJar);
        deleteTree(target.resolve("lib"));
        copyTreeIfPresent(
                backup.resolve("lib"),
                target.resolve("lib"));
        copyIfPresent(
                backup.resolve("README.md"),
                target.resolve("README.md"));
        copyIfPresent(
                backup.resolve("VERSION.txt"),
                target.resolve("VERSION.txt"));
    }

    private static void replaceFile(Path source, Path target)
            throws IOException {
        Path temporary = target.resolveSibling(
                target.getFileName().toString() + ".update");
        Files.copy(
                source,
                temporary,
                StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyIfPresent(Path source, Path target)
            throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTreeIfPresent(Path source, Path target)
            throws IOException {
        if (Files.isDirectory(source)) {
            copyTree(source, target);
        }
    }

    private static void copyTree(Path source, Path target)
            throws IOException {
        try (java.util.stream.Stream<Path> paths =
                Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(
                        source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(
                            destination.getParent());
                    Files.copy(
                            path,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths =
                Files.walk(root)) {
            List<Path> values = new ArrayList<Path>();
            paths.sorted(Comparator.reverseOrder())
                    .forEach(values::add);
            for (Path path : values) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void waitForFileToUnlock(
            Path file,
            int attempts) throws Exception {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Path probe = file.resolveSibling(
                    file.getFileName().toString() + ".lockcheck");
            try {
                Files.copy(
                        file,
                        probe,
                        StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(probe);
                return;
            } catch (IOException ex) {
                lastFailure = ex;
                Files.deleteIfExists(probe);
                Thread.sleep(1000L);
            }
        }
        throw lastFailure == null
                ? new IOException("Application JAR is still in use.")
                : lastFailure;
    }

    private static void relaunch(
            String javaCommand,
            Path launchJar,
            Path target) throws IOException {
        new ProcessBuilder(
                javaCommand,
                "-jar",
                launchJar.toAbsolutePath().toString())
                .directory(target.toFile())
                .start();
    }

    private static Map<String, String> parseArguments(
            String[] args) {
        Map<String, String> values =
                new HashMap<String, String>();
        if (args == null) {
            return values;
        }
        for (int index = 0;
                index + 1 < args.length; index += 2) {
            values.put(args[index], args[index + 1]);
        }
        return values;
    }

    private static String requireValue(
            Map<String, String> options,
            String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing argument " + key);
        }
        return value.trim();
    }

    private static Path requireFile(
            Map<String, String> options,
            String key) {
        Path path = requirePath(options, key);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "File not found for " + key + ": " + path);
        }
        return path;
    }

    private static Path requireDirectory(
            Map<String, String> options,
            String key) {
        Path path = requirePath(options, key);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                    "Directory not found for " + key + ": " + path);
        }
        return path;
    }

    private static Path requirePath(
            Map<String, String> options,
            String key) {
        return new File(requireValue(options, key))
                .toPath().toAbsolutePath().normalize();
    }

    private static String optionalValue(
            Map<String, String> options,
            String key) {
        String value = options.get(key);
        return value == null ? "" : value.trim();
    }

    private static void log(Path target, String message) {
        if (target == null) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(
                LOG_TIME_FORMAT) + "] " + message;
        try {
            Files.write(
                    target.resolve("update.log"),
                    Arrays.asList(line),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The update result is still shown to the user if logging fails.
        }
    }
}
