package autorequesttuar;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * Checks GitHub for a newer release without delaying the application window.
 */
final class AutoUpdateManager {

    private static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/kantapon-sam/"
            + "AutoRequestTUAR/main/update/version.json";
    private static final String MANIFEST_URL_PROPERTY =
            "autorequesttuar.update.manifestUrl";
    private static final String DISABLED_PROPERTY =
            "autorequesttuar.update.disabled";
    private static final Pattern JSON_TEXT_PATTERN =
            Pattern.compile(
                    "\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 300L * 1024L * 1024L;
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AutoUpdateManager() {
    }

    static void checkForUpdatesAtStartup(
            final Window parent,
            final String currentVersion,
            final BooleanSupplier applicationIsIdle) {
        if (Boolean.getBoolean(DISABLED_PROPERTY)
                || !AppPaths.isRunningFromJar()) {
            return;
        }

        Thread checker = new Thread(() -> {
            try {
                UpdateManifest manifest = fetchManifest(
                        System.getProperty(
                                MANIFEST_URL_PROPERTY,
                                DEFAULT_MANIFEST_URL));
                if (manifest == null
                        || compareVersions(
                                manifest.getVersion(),
                                currentVersion) <= 0) {
                    log("Application is up to date ("
                            + currentVersion + ").");
                    return;
                }
                validateDownloadUrl(manifest);
                if (!isPackageAvailable(manifest.getDownloadUrl())) {
                    log("Release " + manifest.getVersion()
                            + " is not published yet.");
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    if (applicationIsIdle != null
                            && !applicationIsIdle.getAsBoolean()) {
                        log("Update prompt postponed because a TUAR "
                                + "batch is running.");
                        return;
                    }
                    if (confirmUpdate(
                            parent,
                            currentVersion,
                            manifest)) {
                        downloadAndInstall(parent, manifest);
                    }
                });
            } catch (Exception ex) {
                // A blocked internet connection must never delay TUAR work.
                log("Update check skipped: " + ex.getMessage(), ex);
            }
        }, "autorequesttuar-update-check");
        checker.setDaemon(true);
        checker.start();
    }

    static UpdateManifest parseManifest(String json) {
        if (json == null) {
            return null;
        }
        UpdateManifest manifest = new UpdateManifest(
                jsonValue(json, "version"),
                jsonValue(json, "url"),
                jsonValue(json, "sha256"),
                jsonValue(json, "notes"));
        return manifest.isValid() ? manifest : null;
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left);
        String[] rightParts = normalizeVersion(right);
        int maximum = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < maximum; index++) {
            int leftValue = index < leftParts.length
                    ? numericPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length
                    ? numericPart(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return leftValue < rightValue ? -1 : 1;
            }
        }
        return 0;
    }

    static void validateDownloadUrl(UpdateManifest manifest)
            throws IOException {
        if (manifest == null || !manifest.isValid()) {
            throw new IOException("Invalid update manifest.");
        }
        try {
            URI uri = new URI(manifest.getDownloadUrl());
            String expectedPath = "/kantapon-sam/AutoRequestTUAR/"
                    + "releases/download/v" + manifest.getVersion()
                    + "/AutoRequestTUAR-dist-"
                    + manifest.getVersion() + ".zip";
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || !expectedPath.equals(uri.getPath())) {
                throw new IOException(
                        "Update URL is outside the official "
                        + "AutoRequestTUAR releases.");
            }
        } catch (java.net.URISyntaxException ex) {
            throw new IOException("Invalid update URL.", ex);
        }
    }

    private static UpdateManifest fetchManifest(String manifestUrl)
            throws IOException {
        HttpURLConnection connection = openConnection(
                cacheBusted(manifestUrl));
        try {
            requireSuccess(connection, "Update manifest");
            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream())) {
                String json = readUtf8(input, MAX_MANIFEST_BYTES);
                UpdateManifest manifest = parseManifest(json);
                if (manifest == null) {
                    throw new IOException(
                            "GitHub returned an invalid update manifest.");
                }
                return manifest;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isPackageAvailable(String urlValue) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(urlValue);
            connection.setRequestProperty("Range", "bytes=0-0");
            int response = connection.getResponseCode();
            return response == HttpURLConnection.HTTP_OK
                    || response == HttpURLConnection.HTTP_PARTIAL;
        } catch (IOException ex) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void downloadAndInstall(
            Component parent,
            UpdateManifest manifest) {
        final JDialog progress = progressDialog(
                parent,
                "Downloading AutoRequestTUAR v"
                + manifest.getVersion() + "...");
        SwingWorker<File, Void> worker = new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                return download(manifest);
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    launchUpdater(get());
                } catch (Exception ex) {
                    log("Update installation could not start: "
                            + ex.getMessage(), ex);
                    JOptionPane.showMessageDialog(
                            parent,
                            "The update could not be installed.\n"
                            + ex.getMessage()
                            + "\n\nSee update.log for details.",
                            "Update Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private static File download(UpdateManifest manifest)
            throws Exception {
        validateDownloadUrl(manifest);
        HttpURLConnection connection = openConnection(
                manifest.getDownloadUrl());
        File temporary = File.createTempFile(
                "autorequesttuar-update-", ".zip");
        boolean complete = false;
        try {
            requireSuccess(connection, "Update package");
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > MAX_PACKAGE_BYTES) {
                throw new IOException("Update package is too large.");
            }
            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream());
                    FileOutputStream output =
                            new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_PACKAGE_BYTES) {
                        throw new IOException(
                                "Update package exceeded the size limit.");
                    }
                    output.write(buffer, 0, count);
                }
            }

            String actualHash = sha256(temporary);
            if (!manifest.getSha256().equalsIgnoreCase(actualHash)) {
                throw new IOException(
                        "SHA-256 verification failed.");
            }
            complete = true;
            log("Downloaded verified update "
                    + manifest.getVersion() + ".");
            return temporary;
        } finally {
            connection.disconnect();
            if (!complete) {
                Files.deleteIfExists(temporary.toPath());
            }
        }
    }

    private static void launchUpdater(File downloadedZip)
            throws IOException {
        File runningJar = AppPaths.getRunningLocation();
        if (!AppPaths.isRunningFromJar()) {
            throw new IOException(
                    "Auto update is available only from the JAR.");
        }

        File updaterJar = File.createTempFile(
                "autorequesttuar-updater-", ".jar");
        Files.copy(
                runningJar.toPath(),
                updaterJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        List<String> command = new ArrayList<String>();
        command.add(AppPaths.getJavaExecutable());
        command.add("-cp");
        command.add(updaterJar.getAbsolutePath());
        command.add("autorequesttuar.UpdaterMain");
        command.add("--zip");
        command.add(downloadedZip.getAbsolutePath());
        command.add("--target");
        command.add(AppPaths.getAppFolder().toFile()
                .getAbsolutePath());
        command.add("--launch");
        command.add(runningJar.getAbsolutePath());
        command.add("--java");
        command.add(AppPaths.getJavaExecutable());
        command.add("--cleanup");
        command.add(updaterJar.getAbsolutePath());

        new ProcessBuilder(command)
                .directory(AppPaths.getAppFolder().toFile())
                .start();
        log("Updater launched. Closing the current version.");
        System.exit(0);
    }

    private static boolean confirmUpdate(
            Component parent,
            String currentVersion,
            UpdateManifest manifest) {
        JTextArea notes = new JTextArea(
                manifest.getNotes().isEmpty()
                        ? "Maintenance and application update."
                        : manifest.getNotes());
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setRows(7);
        notes.setColumns(48);
        notes.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        Object[] message = new Object[]{
            "AutoRequestTUAR " + manifest.getVersion()
            + " is available.",
            "Current version: " + currentVersion,
            "The program will restart after the update.",
            new JScrollPane(notes)
        };
        return JOptionPane.showConfirmDialog(
                parent,
                message,
                "Update Available",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    private static JDialog progressDialog(
            Component parent,
            String message) {
        Window owner = parent instanceof Window
                ? (Window) parent
                : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Updating");
        dialog.setModal(true);
        dialog.setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(BorderFactory.createEmptyBorder(
                15, 18, 15, 18));
        content.add(new JLabel(message), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(360, 18));
        content.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    private static HttpURLConnection openConnection(String value)
            throws IOException {
        URL url = new URL(value);
        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                "AutoRequestTUAR-auto-updater");
        connection.setRequestProperty(
                "Accept",
                "application/json, application/octet-stream, */*");
        return connection;
    }

    private static void requireSuccess(
            HttpURLConnection connection,
            String description) throws IOException {
        int response = connection.getResponseCode();
        if (response < 200 || response >= 300) {
            throw new IOException(description
                    + " returned HTTP " + response + ".");
        }
    }

    private static String cacheBusted(String url) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "ts=" + System.currentTimeMillis();
    }

    private static String readUtf8(InputStream input, long limit)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new IOException("Downloaded content is too large.");
            }
            output.write(buffer, 0, count);
        }
        return new String(
                output.toByteArray(),
                StandardCharsets.UTF_8);
    }

    private static String jsonValue(String json, String key) {
        Pattern pattern = Pattern.compile(String.format(
                JSON_TEXT_PATTERN.pattern(),
                Pattern.quote(key)));
        Matcher matcher = pattern.matcher(json);
        return matcher.find()
                ? unescapeJson(matcher.group(1)) : "";
    }

    private static String unescapeJson(String value) {
        return value == null ? "" : value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String[] normalizeVersion(String version) {
        String safe = version == null ? "" : version.trim();
        return safe.replaceFirst("^[vV]", "").split("\\.");
    }

    private static int numericPart(String value) {
        String digits = value == null
                ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte element : digest.digest()) {
            value.append(String.format("%02x", element));
        }
        return value.toString();
    }

    private static void log(String message) {
        log(message, null);
    }

    private static void log(String message, Exception error) {
        String line = "[" + LocalDateTime.now().format(
                LOG_TIME_FORMAT) + "] " + message;
        System.out.println("[UPDATE] " + message);
        File logFile = AppPaths.getAppFolder()
                .resolve("update.log").toFile();
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(logFile, true))) {
            writer.write(line);
            writer.newLine();
            if (error != null) {
                writer.write(error.toString());
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Update logging must not stop the main program.
        }
    }
}
