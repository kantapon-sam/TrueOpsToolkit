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

/** Loopback-only fake pending-approval portal. Contains no real requests or credentials. */
public final class MockPromsPortal implements AutoCloseable {
    public static final class Row {
        public final String ref, project, requester, type;
        public final String[] boq;

        public Row(String ref, String project, String requester, String type) {
            this(ref, project, requester, type, new String[] {"Services"});
        }

        public Row(String ref, String project, String requester, String type, String[] boq) {
            this.ref = ref;
            this.project = project;
            this.requester = requester;
            this.type = type;
            this.boq = boq.clone();
        }
    }

    private final HttpServer server;
    private final byte[] html;
    private final List<Row> rows;
    private final String mode;
    private final int pageSize;
    private final boolean pager;
    private final Map<String, String> approved = new LinkedHashMap<>();
    private final Map<String, String> approvedPairs = new LinkedHashMap<>();
    private final Map<String, String> approvedTypes = new LinkedHashMap<>();
    private final List<String> attempts = new ArrayList<>();

    public MockPromsPortal(Path fixture, List<Row> rows, String mode,
            int pageSize, boolean pager) throws IOException {
        this.html = Files.readAllBytes(fixture);
        this.rows = new ArrayList<>(rows);
        this.mode = mode;
        this.pageSize = pageSize;
        this.pager = pager;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "mock-proms-loopback");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> respond(exchange, 200, "text/html", html));
        server.createContext("/state", exchange -> {
            if (exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("slow_filter=true")) {
                try { Thread.sleep(2300); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Local fixture response interrupted.", stopped);
                }
            }
            respond(exchange, 200, "application/json", state().getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/approve", this::approve);
        server.createContext("/noise", exchange -> respond(exchange, 200, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8)));
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

    public synchronized Map<String, String> approvals() { return new LinkedHashMap<>(approved); }

    public synchronized Map<String, String> approvalTypes() { return new LinkedHashMap<>(approvedTypes); }

    public synchronized List<String> attempts() { return new ArrayList<>(attempts); }

    private synchronized String state() {
        StringBuilder output = new StringBuilder("{\"mode\":").append(quote(mode))
                .append(",\"pageSize\":").append(pageSize)
                .append(",\"pager\":").append(pager).append(",\"rows\":[");
        boolean first = true;
        for (Row row : rows) {
            if (approvedPairs.containsKey(row.ref + "\n" + row.type)) continue;
            if (!first) output.append(',');
            first = false;
            output.append("{\"ref\":").append(quote(row.ref))
                    .append(",\"project\":").append(quote(row.project))
                    .append(",\"requester\":").append(quote(row.requester))
                    .append(",\"type\":").append(quote(row.type)).append(",\"boq\":[");
            for (int index = 0; index < row.boq.length; index++) {
                if (index > 0) output.append(',');
                output.append(quote(row.boq[index]));
            }
            output.append("]}");
        }
        return output.append("]}").toString();
    }

    private void approve(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> fields = parseForm(read(exchange.getRequestBody()));
        String ref = fields.get("ref");
        String type = fields.get("type");
        String description = fields.get("description");
        String response;
        synchronized (this) {
            attempts.add(ref);
            boolean known = false;
            for (Row row : rows) known |= row.ref.equals(ref) && row.type.equals(type);
            if (mode.contains("server-failure")) {
                response = "{\"ok\":false,\"message\":\"Rejected by local fixture\"}";
            } else if (!known || description == null || approvedPairs.containsKey(ref + "\n" + type)) {
                response = "{\"ok\":false,\"message\":\"Invalid exact reference\"}";
            } else {
                if (!mode.contains("false-success")) {
                    approved.put(ref, description);
                    approvedPairs.put(ref + "\n" + type, description);
                    approvedTypes.put(ref, type);
                }
                response = "{\"ok\":true,\"message\":\"Success: approved\"}";
            }
        }
        respond(exchange, 200, "application/json", response.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseForm(String value) throws IOException {
        Map<String, String> output = new LinkedHashMap<>();
        for (String field : value.split("&")) {
            String[] parts = field.split("=", 2);
            if (parts.length == 2) output.put(URLDecoder.decode(parts[0], "UTF-8"),
                    URLDecoder.decode(parts[1], "UTF-8"));
        }
        return output;
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String type,
            byte[] content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(content); }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    @Override public void close() { server.stop(0); }
}
