package autorequesttuar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Non-secret TUAR preferences, separate from the encrypted password store. */
final class SettingsStore {

    private final Path file;

    SettingsStore() {
        this(AppPaths.getAppFolder().resolve("User").resolve("settings.properties"));
    }

    SettingsStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Properties load() throws IOException {
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                values.load(input);
            }
        }
        return values;
    }

    void save(Properties changes) throws IOException {
        Properties values = load();
        values.putAll(changes);
        if (SharedAccount.isManaged()) {
            values.remove("username");
            values.remove("password");
            values.remove("rememberLogin");
        }
        writeProperties(file, values, "AutoRequestTUAR settings (no passwords)");
    }

    static Path workbookPath(String input) {
        Path path = resolvePath(input, "Requests workbook");
        if (path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Choose an Excel workbook ending in .xlsx.");
        }
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Requests workbook must be a file.");
        }
        return path;
    }

    static Path attachmentsPath(String input) {
        Path path = resolvePath(input, "Attachments folder");
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Attachments path must be a folder.");
        }
        return path;
    }

    private static Path resolvePath(String input, String label) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute()) {
                path = AppPaths.getAppFolder().resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException ex) {
            throw new IllegalArgumentException(label + " contains an invalid path.", ex);
        }
    }

    static void writeProperties(Path file, Properties values, String comment)
            throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "tuar-settings-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, comment);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
