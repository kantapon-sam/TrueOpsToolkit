package trueopstoolkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** One shared, explicit portal mode. Portal URLs are fixed by the selected mode. */
public final class PortalSettings {
    private static final String MODE_KEY = "portal.mode";

    public enum Mode {
        EXTERNAL("External", "https://proms.truecorp.co.th/proms/login/",
                "https://proms.truecorp.co.th/proms/pendingapprove",
                "https://proms.truecorp.co.th/proms/workorder"),
        INTERNAL("Internal", "http://promsweb.true.th/proms/login",
                "http://promsweb.true.th/proms/pendingapprove",
                "http://promsweb.true.th/proms/workorder");

        private final String label, loginUrl, pendingUrl, workOrderUrl;

        Mode(String label, String loginUrl, String pendingUrl, String workOrderUrl) {
            this.label = label;
            this.loginUrl = loginUrl;
            this.pendingUrl = pendingUrl;
            this.workOrderUrl = workOrderUrl;
        }

        public String getLabel() { return label; }
        @Override public String toString() { return label; }
        public String getLoginUrl() { return loginUrl; }
        public String getPendingUrl() { return pendingUrl; }
        public String getWorkOrderUrl() { return workOrderUrl; }
    }

    private PortalSettings() { }

    public static Mode load(Path home) throws IOException {
        Path config = configDirectory(home);
        if (Files.exists(config) && !Files.isDirectory(config)) {
            throw new IOException("The Toolkit config path must be a directory.");
        }
        Path file = config.resolve("portal.properties");
        if (Files.notExists(file)) return Mode.EXTERNAL;
        if (!Files.isRegularFile(file)) {
            throw new IOException("The portal settings path must be a regular file.");
        }
        Properties settings = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            settings.load(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The portal settings file is malformed.", malformed);
        }
        if (settings.size() != 1 || !settings.containsKey(MODE_KEY)) {
            throw new IOException("Portal settings must contain only portal.mode. Choose External or Internal in Toolkit settings.");
        }
        String selected = settings.getProperty(MODE_KEY, "").trim();
        for (Mode mode : Mode.values()) {
            if (mode.name().equals(selected)) return mode;
        }
        throw new IOException("Unsupported portal mode. Choose External or Internal in Toolkit settings.");
    }

    public static void save(Path home, Mode mode) throws IOException {
        if (mode == null) throw new IOException("Select a valid portal mode.");
        Path config = configDirectory(home);
        Files.createDirectories(config);
        Path file = config.resolve("portal.properties");
        Path temporary = Files.createTempFile(config, ".portal-", ".tmp");
        try {
            Files.write(temporary, (MODE_KEY + "=" + mode.name() + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reload saved mode before each tool invocation, so every tool shares the current setting. */
    public static void apply(Path home) throws IOException {
        Mode mode = load(home);
        System.setProperty("trueops.portal.mode", mode.name());
        System.setProperty("trueops.portal.login", mode.getLoginUrl());
        System.setProperty("trueops.portal.pending", mode.getPendingUrl());
        System.setProperty("trueops.portal.workorder", mode.getWorkOrderUrl());
    }

    private static Path configDirectory(Path home) throws IOException {
        if (home == null) throw new IOException("Toolkit home is required for portal settings.");
        Path normalized = home.toAbsolutePath().normalize();
        if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
            throw new IOException("Toolkit home must be a directory.");
        }
        return normalized.resolve("config");
    }
}
