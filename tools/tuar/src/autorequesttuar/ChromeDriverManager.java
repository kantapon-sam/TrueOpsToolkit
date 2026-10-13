package autorequesttuar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a ChromeDriver matching the locally installed Chrome build.
 */
final class ChromeDriverManager {

    private static final String BUILD_ENDPOINT =
            "https://googlechromelabs.github.io/chrome-for-testing/"
            + "latest-patch-versions-per-build-with-downloads.json";
    private static final String MILESTONE_ENDPOINT =
            "https://googlechromelabs.github.io/chrome-for-testing/"
            + "latest-versions-per-milestone-with-downloads.json";

    private ChromeDriverManager() {
    }

    static Path prepare(Consumer<String> status) throws Exception {
        String toolkitHome = System.getProperty("trueops.home", "").trim();
        if (!toolkitHome.isEmpty()) {
            try {
                Class<?> shared = Class.forName("trueopstoolkit.SharedChromeDriver");
                return (Path) shared.getMethod("ensure", Path.class, Consumer.class)
                        .invoke(null, Paths.get(toolkitHome), status);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new IOException("Shared ChromeDriver could not be prepared.", cause);
            } catch (ReflectiveOperationException ex) {
                throw new IOException("The toolkit shared ChromeDriver manager is unavailable.", ex);
            }
        }
        String chromeVersion = detectChromeVersion();
        String chromeBuild = firstParts(chromeVersion, 3);
        status.accept("Google Chrome detected: " + chromeVersion);

        Path driverPath = AppPaths.getAppFolder()
                .resolve("drivers")
                .resolve("chromedriver.exe")
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(driverPath)) {
            String existingVersion = driverVersion(driverPath);
            if (chromeBuild.equals(firstParts(existingVersion, 3))) {
                status.accept("ChromeDriver is ready: " + existingVersion);
                return driverPath;
            }
        }

        status.accept("Finding a ChromeDriver that matches Chrome...");
        DriverDownload download = findDownload(chromeVersion);
        status.accept("Downloading ChromeDriver " + download.version + "...");
        downloadAndExtract(download.url, driverPath);

        String installedVersion = driverVersion(driverPath);
        if (!chromeBuild.equals(firstParts(installedVersion, 3))) {
            throw new IOException(
                    "The downloaded ChromeDriver does not match Chrome: "
                    + installedVersion + " / " + chromeVersion);
        }
        status.accept("ChromeDriver installed: " + installedVersion);
        return driverPath;
    }

    private static DriverDownload findDownload(String chromeVersion)
            throws IOException {
        String build = firstParts(chromeVersion, 3);
        JsonObject buildRoot = readJson(BUILD_ENDPOINT);
        JsonObject builds = buildRoot.getAsJsonObject("builds");
        if (builds != null && builds.has(build)) {
            DriverDownload match = parseDownload(builds.getAsJsonObject(build));
            if (match != null) {
                return match;
            }
        }

        String milestone = firstParts(chromeVersion, 1);
        JsonObject milestoneRoot = readJson(MILESTONE_ENDPOINT);
        JsonObject milestones = milestoneRoot.getAsJsonObject("milestones");
        if (milestones != null && milestones.has(milestone)) {
            DriverDownload match = parseDownload(
                    milestones.getAsJsonObject(milestone));
            if (match != null) {
                return match;
            }
        }
        throw new IOException(
                "No ChromeDriver was found for Google Chrome "
                + chromeVersion);
    }

    private static DriverDownload parseDownload(JsonObject entry) {
        if (entry == null || !entry.has("version")
                || !entry.has("downloads")) {
            return null;
        }
        JsonObject downloads = entry.getAsJsonObject("downloads");
        if (!downloads.has("chromedriver")) {
            return null;
        }

        String preferredPlatform = is64BitWindows() ? "win64" : "win32";
        String fallbackPlatform = "win32";
        JsonArray array = downloads.getAsJsonArray("chromedriver");
        String fallbackUrl = null;
        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            String platform = item.get("platform").getAsString();
            String url = item.get("url").getAsString();
            if (preferredPlatform.equals(platform)) {
                return new DriverDownload(
                        entry.get("version").getAsString(), url);
            }
            if (fallbackPlatform.equals(platform)) {
                fallbackUrl = url;
            }
        }
        return fallbackUrl == null ? null : new DriverDownload(
                entry.get("version").getAsString(), fallbackUrl);
    }

    private static void downloadAndExtract(String sourceUrl, Path target)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path zipFile = Files.createTempFile("chromedriver-", ".zip");
        Path extracted = Files.createTempFile(
                target.getParent(), "chromedriver-", ".exe");
        boolean found = false;
        try {
            HttpURLConnection connection = open(sourceUrl);
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, zipFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }

            try (ZipInputStream zip = new ZipInputStream(
                    Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName()
                            .replace('\\', '/').toLowerCase(Locale.ROOT);
                    if (!entry.isDirectory()
                            && name.endsWith("/chromedriver.exe")) {
                        Files.copy(
                                zip,
                                extracted,
                                StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                throw new IOException(
                        "chromedriver.exe was not found in the download");
            }
            Files.move(
                    extracted,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(zipFile);
            Files.deleteIfExists(extracted);
        }
    }

    private static String detectChromeVersion() throws IOException {
        String[] registryKeys = {
            "HKCU\\Software\\Google\\Chrome\\BLBeacon",
            "HKLM\\Software\\Google\\Chrome\\BLBeacon",
            "HKLM\\Software\\WOW6432Node\\Google\\Chrome\\BLBeacon"
        };
        for (String key : registryKeys) {
            String output = runProcess("reg", "query", key, "/v", "version");
            String version = findVersion(output);
            if (version != null) {
                return version;
            }
        }
        throw new IOException(
                "Google Chrome version was not found in the Windows Registry");
    }

    private static String driverVersion(Path driver) {
        try {
            return findVersion(runProcess(
                    driver.toAbsolutePath().toString(), "--version"));
        } catch (IOException ex) {
            return null;
        }
    }

    private static JsonObject readJson(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream();
                InputStreamReader reader = new InputStreamReader(
                        input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String sourceUrl)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(sourceUrl).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty(
                "User-Agent", "AutoRequestTUAR/1.0");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException(
                    "Download failed with HTTP " + status + ": " + sourceUrl);
        }
        return connection;
    }

    private static String runProcess(String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Version detection was interrupted", ex);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String findVersion(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstParts(String version, int count) {
        if (version == null) {
            return "";
        }
        String[] parts = version.split("\\.");
        if (parts.length < count) {
            return version;
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append('.');
            }
            result.append(parts[index]);
        }
        return result.toString();
    }

    private static boolean is64BitWindows() {
        String architecture = System.getenv("PROCESSOR_ARCHITECTURE");
        String wow64Architecture = System.getenv(
                "PROCESSOR_ARCHITEW6432");
        return contains64(architecture) || contains64(wow64Architecture);
    }

    private static boolean contains64(String value) {
        return value != null && value.contains("64");
    }

    private static final class DriverDownload {

        final String version;
        final String url;

        DriverDownload(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }
}
