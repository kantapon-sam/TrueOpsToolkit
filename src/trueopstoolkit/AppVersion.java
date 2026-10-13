package trueopstoolkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** The build copies the single version.properties source into the launcher. */
public final class AppVersion {
    private static final String CURRENT = load();

    private AppVersion() { }

    public static String current() { return CURRENT; }

    private static String load() {
        try (InputStream input = AppVersion.class.getResourceAsStream("/trueopstoolkit/version.properties")) {
            String version = read(input);
            if (version != null) return version;
        } catch (IOException ignored) { }
        Package metadata = AppVersion.class.getPackage();
        if (metadata != null && metadata.getImplementationVersion() != null) {
            return metadata.getImplementationVersion();
        }
        // Also support running source/classes directly from the project in an IDE.
        Path source = Paths.get("version.properties");
        if (Files.isRegularFile(source)) {
            try (InputStream input = Files.newInputStream(source)) {
                String version = read(input);
                if (version != null) return version;
            } catch (IOException ignored) { }
        }
        return "development";
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return null;
        Properties properties = new Properties();
        properties.load(input);
        String value = properties.getProperty("app.version", "").trim();
        return value.isEmpty() ? null : value;
    }
}
