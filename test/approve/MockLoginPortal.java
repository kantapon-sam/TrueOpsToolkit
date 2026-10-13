package approve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Synthetic loopback login service, isolated from every real account and portal. */
public final class MockLoginPortal implements AutoCloseable {
    private final HttpServer server;
    private final String mode;
    private final byte[] html;
    private final List<Map<String,String>> attempts = new ArrayList<>();

    public MockLoginPortal(Path fixture, String layout, String mode) throws IOException {
        this.mode = mode;
        String button = "internal".equals(layout)
                ? "<button type='button' data-ng-click='login()' onclick='sendLogin()'>Sign in</button>"
                : "ng".equals(layout)
                ? "<button type='button' ng-click='login()' onclick='sendLogin()'>Sign in</button>"
                : "<button type='button' id='btn_signin' data-ng-click='login()' onclick='sendLogin()'>Sign in</button>";
        if ("ambiguous".equals(mode)) button += "<button type='button' ng-click='login()' onclick='sendLogin()'>Another visible Sign in</button>";
        if ("hidden-duplicate".equals(mode)) button += "<button type='button' ng-click='login()' style='display:none' onclick='sendLogin()'>Hidden Sign in</button>";
        html = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8)
                .replace("@BUTTONS@", button).replace("@MODE@", mode).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "mock-login-loopback");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/proms/login", exchange -> respond(exchange, "text/html", html));
        server.createContext("/attempt", this::attempt);
        server.createContext("/", exchange -> respond(exchange, "text/html",
                "<!doctype html><html><head><title>Synthetic signed-in portal</title></head><body><h1 id='signedIn'>Synthetic portal accepted login</h1></body></html>".getBytes(StandardCharsets.UTF_8)));
        server.start();
    }

    public String loginUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/proms/login"; }

    public synchronized List<Map<String,String>> attempts() {
        List<Map<String,String>> copy = new ArrayList<>();
        for (Map<String,String> attempt : attempts) copy.add(new LinkedHashMap<>(attempt));
        return copy;
    }

    private void attempt(HttpExchange exchange) throws IOException {
        Map<String,String> fields = parse(read(exchange.getRequestBody()));
        synchronized (this) { attempts.add(fields); }
        String response;
        if ("failure".equals(mode)) response = "{\"ok\":false,\"message\":\"Synthetic login rejected\"}";
        else if ("captcha".equals(mode) && !"HUMAN_VERIFIED".equals(fields.get("captcha"))) response = "{\"ok\":false,\"captcha\":true}";
        else response = "{\"ok\":true}";
        respond(exchange, "application/json", response.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String,String> parse(String content) throws IOException {
        Map<String,String> fields = new LinkedHashMap<>();
        for (String part : content.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) fields.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"));
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, String type, byte[] content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(content); }
    }

    @Override public void close() { server.stop(0); }
}
