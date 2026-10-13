package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An offline Chrome check which never enters an automation module's main(). */
public final class BrowserCheck {
    private static final String TITLE = "TrueOps Toolkit Browser Check";
    private static final Pattern SESSION = Pattern.compile("\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private BrowserCheck() { }

    /** Locate installed Chrome without downloading or inspecting a user's profile. */
    public static Path findChrome() throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        addChrome(candidates, System.getenv("ProgramFiles"));
        addChrome(candidates, System.getenv("ProgramFiles(x86)"));
        addChrome(candidates, System.getenv("LOCALAPPDATA"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IOException("Google Chrome was not found in the standard installation folders.");
    }

    private static void addChrome(List<Path> candidates, String directory) {
        if (directory != null && !directory.trim().isEmpty()) {
            candidates.add(Paths.get(directory, "Google", "Chrome", "Application", "chrome.exe"));
        }
    }

    /**
     * Launch only the supplied driver, open a local test document, then quit its
     * own browser/session. The returned text is also saved in reportDirectory.
     */
    public static String run(Path driver, Path chrome, Path reportDirectory,
            boolean headless) throws Exception {
        if (chrome == null) chrome = findChrome();
        if (!Files.isRegularFile(driver)) throw new IOException("ChromeDriver is missing: " + driver);
        if (!Files.isRegularFile(chrome)) throw new IOException("Google Chrome is missing: " + chrome);
        Files.createDirectories(reportDirectory);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path report = reportDirectory.resolve("browser-check-" + stamp + ".txt");
        Path log = reportDirectory.resolve("chromedriver-" + stamp + ".log");
        StringBuilder details = new StringBuilder();
        details.append("TrueOps Toolkit offline browser check\r\n");
        details.append("Time: ").append(new Date()).append("\r\n");
        details.append("Driver: ").append(driver.toAbsolutePath()).append("\r\n");
        details.append("Chrome: ").append(chrome.toAbsolutePath()).append("\r\n");
        details.append("Mode: ").append(headless ? "headless" : "visible browser").append("\r\n");
        details.append("Navigation: local data URL only; no business website or module main was run.\r\n");
        final OwnedDriver owned = new OwnedDriver();
        Thread cleanupHook = new Thread(new Runnable() {
            @Override public void run() { owned.close(); }
        }, "browser-check-cleanup-" + stamp);
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        String endpoint = null;
        String sessionId = null;
        Exception failure = null;
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            endpoint = "http://127.0.0.1:" + port;
            owned.endpoint = endpoint;
            ProcessBuilder builder = new ProcessBuilder(driver.toAbsolutePath().toString(),
                    "--port=" + port, "--allowed-ips=127.0.0.1");
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = owned.launch(builder);
            waitUntilReady(endpoint, process);
            owned.checkRunning();
            String arguments = "\"--no-first-run\",\"--no-default-browser-check\","
                    + "\"--disable-background-networking\",\"--disable-component-update\","
                    + "\"--disable-sync\",\"--disable-extensions\",\"--window-size=1100,740\","
                    + "\"--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost\"";
            if (headless) arguments += ",\"--headless=new\"";
            String capabilities = "{\"capabilities\":{\"alwaysMatch\":{\"browserName\":\"chrome\","
                    + "\"goog:chromeOptions\":{\"binary\":" + json(chrome.toAbsolutePath().toString())
                    + ",\"args\":[" + arguments + "]}}}}";
            String session = request(endpoint + "/session", "POST", capabilities, 30000);
            Matcher match = SESSION.matcher(session);
            if (!match.find()) throw new IOException("ChromeDriver did not return a session ID: " + session);
            sessionId = match.group(1);
            owned.sessionId = sessionId;
            owned.checkRunning();
            details.append("Browser version: ").append(jsonStringValue(session, "browserVersion")).append("\r\n");
            details.append("Driver version: ").append(jsonStringValue(session, "chromedriverVersion")).append("\r\n");
            String html = "<!doctype html><html><head><meta charset='utf-8'><title>" + TITLE
                    + "</title></head><body style='font-family:Segoe UI,sans-serif;background:#112132;color:#fff;padding:70px'>"
                    + "<h1>TrueOps Toolkit</h1><h2>Browser check passed</h2>"
                    + "<p>Chrome and ChromeDriver opened this local test page successfully.</p>"
                    + "<p>No PROMS operation has been started.</p></body></html>";
            String localPage = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20");
            request(endpoint + "/session/" + sessionId + "/url", "POST", "{\"url\":" + json(localPage) + "}", 15000);
            String titleResponse = request(endpoint + "/session/" + sessionId + "/title", "GET", null, 5000);
            String actualTitle = jsonStringValue(titleResponse, "value");
            if (!TITLE.equals(actualTitle)) throw new IOException("Unexpected test page title: " + actualTitle);
            details.append("Page title verified: ").append(actualTitle).append("\r\n");
            if (!headless) Thread.sleep(1500L);
        } catch (Exception ex) {
            failure = ex;
            details.append("Error: ").append(ex.toString()).append("\r\n");
        } finally {
            owned.close();
            try { Runtime.getRuntime().removeShutdownHook(cleanupHook); }
            catch (IllegalStateException ignored) { /* JVM shutdown is already running cleanup. */ }
            details.append(owned.messages);
            if (failure == null) failure = owned.failure;
            details.append("Result: ").append(failure == null ? "PASS" : "FAIL").append("\r\n");
            Files.write(report, details.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IOException("Browser check failed. Report: " + report + "\n" + failure.getMessage(), failure);
        return details.append("Report: ").append(report.toAbsolutePath()).toString();
    }

    /** Owns one process and at most one session; monitors are never held during HTTP. */
    private static final class OwnedDriver {
        volatile String endpoint;
        volatile String sessionId;
        volatile Process process;
        volatile String messages = "";
        volatile Exception failure;
        private boolean closing;
        private final CountDownLatch closed = new CountDownLatch(1);

        synchronized Process launch(ProcessBuilder builder) throws IOException {
            checkRunning();
            process = builder.start();
            return process;
        }

        synchronized void checkRunning() throws IOException {
            if (closing) throw new IOException("Browser check is stopping.");
        }

        void close() {
            boolean ownsCleanup;
            synchronized (this) {
                ownsCleanup = !closing;
                closing = true;
            }
            if (!ownsCleanup) {
                try {
                    // If the worker already owns cleanup, the JVM shutdown hook
                    // waits for it instead of ending the process prematurely.
                    if (!closed.await(10, TimeUnit.SECONDS)) forceStop();
                } catch (InterruptedException ex) {
                    forceStop();
                    Thread.currentThread().interrupt();
                }
                return;
            }
            StringBuilder log = new StringBuilder();
            try {
                Process child = process;
                if (child == null) return;
                String session = sessionId;
                if (session != null) {
                    try {
                        request(endpoint + "/session/" + session, "DELETE", null, 4000);
                        log.append("Cleanup: own browser session closed.\r\n");
                    } catch (Exception ex) {
                        failure = ex;
                        log.append("Cleanup warning: ").append(ex.toString()).append("\r\n");
                    }
                }
                if (child.isAlive()) {
                    // This also covers shutdown while POST /session is still
                    // starting Chrome and has not returned a session ID yet.
                    try { request(endpoint + "/shutdown", "GET", null, 1500); }
                    catch (IOException ignored) { /* Driver may close its socket on shutdown. */ }
                }
                if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    child.destroy();
                    if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroyForcibly();
                }
                log.append("Cleanup: own ChromeDriver stopped.\r\n");
            } catch (InterruptedException ex) {
                forceStop();
                failure = ex;
                Thread.currentThread().interrupt();
            } finally {
                messages = log.toString();
                closed.countDown();
            }
        }

        private void forceStop() {
            Process child = process;
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    private static void waitUntilReady(String endpoint, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IOException("ChromeDriver exited before becoming ready.");
            try {
                request(endpoint + "/status", "GET", null, 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100L);
            }
        }
        throw new IOException("ChromeDriver did not start within 15 seconds.");
    }

    private static String request(String endpoint, String method, String body, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            if (response != null) {
                try (InputStream input = response; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            if (status >= 400) throw new IOException("ChromeDriver HTTP " + status + ": " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String jsonStringValue(String source, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(source);
        return matcher.find() ? matcher.group(1) : "(not reported)";
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    /** CLI: BrowserCheck DRIVER REPORT_DIRECTORY [CHROME] [--headless]. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Usage: BrowserCheck DRIVER REPORT_DIRECTORY [CHROME] [--headless]");
        }
        Path chrome = arguments.length > 2 && !"--headless".equals(arguments[2])
                ? Paths.get(arguments[2]) : findChrome();
        boolean headless = false;
        for (String argument : arguments) if ("--headless".equals(argument)) headless = true;
        System.out.println(run(Paths.get(arguments[0]), chrome, Paths.get(arguments[1]), headless));
    }
}
