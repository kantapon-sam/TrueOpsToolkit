package tk07;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** Synthetic loopback-only TK07 portal and PDF downloads. */
public final class MockTk07Portal implements AutoCloseable {
    public static final class Row {
        public final String reference, project, type, pdfText;
        public final int attachments;
        public final boolean validPdf;
        public final String[] attachmentNames;
        public Row(String reference, String project, String type, String pdfText, int attachments, boolean validPdf) {
            this(reference, project, type, pdfText, attachments, validPdf, null);
        }
        public Row(String reference, String project, String type, String pdfText, int attachments, boolean validPdf, String[] attachmentNames) {
            this.reference = reference; this.project = project; this.type = type;
            this.pdfText = pdfText; this.attachments = attachments; this.validPdf = validPdf;
            this.attachmentNames = attachmentNames == null ? null : attachmentNames.clone();
        }
    }
    private final HttpServer server;
    private final List<Row> rows;
    private final Map<String, String> decisions = new LinkedHashMap<>(), comments = new LinkedHashMap<>();
    private final List<String> attempts = new ArrayList<>();
    private final String mode;
    private int downloads, legacyAttachmentLoads, activityAttachmentLoads;

    public MockTk07Portal(Path fixture, List<Row> rows, String mode) throws IOException {
        this.rows = new ArrayList<>(rows); this.mode = mode;
        byte[] html = Files.readAllBytes(fixture);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "tk07-loopback"); thread.setDaemon(true); return thread; }));
        server.createContext("/", exchange -> respond(exchange, 200, "text/html", html));
        server.createContext("/state", this::state);
        server.createContext("/attachments", this::attachments);
        server.createContext("/proms/pmsAttachmentController/list/wo/", this::activityAttachments);
        server.createContext("/pdf", this::pdf);
        server.createContext("/decision", this::decision);
        server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    public synchronized Map<String, String> decisions() { return new LinkedHashMap<>(decisions); }
    public synchronized Map<String, String> comments() { return new LinkedHashMap<>(comments); }
    public synchronized List<String> attempts() { return new ArrayList<>(attempts); }
    public synchronized int downloads() { return downloads; }
    public synchronized int legacyAttachmentLoads() { return legacyAttachmentLoads; }
    public synchronized int activityAttachmentLoads() { return activityAttachmentLoads; }

    private void state(HttpExchange exchange) throws IOException {
        Map<String, String> fields = parse(exchange.getRequestURI().getRawQuery());
        String query = fields.containsKey("query") ? fields.get("query").toLowerCase(Locale.ROOT) : "";
        if (!query.isEmpty()) delay(mode.contains("slow") ? 2400 : 700);
        StringBuilder output = new StringBuilder("{\"mode\":").append(quote(mode)).append(",\"rows\":[");
        synchronized (this) {
            boolean first = true;
            for (Row row : rows) {
                if (decisions.containsKey(row.reference) || !(row.reference + " " + row.project + " " + row.type).toLowerCase(Locale.ROOT).contains(query)) continue;
                if (!first) output.append(','); first = false;
                output.append("{\"ref\":").append(quote(row.reference)).append(",\"project\":").append(quote(row.project))
                        .append(",\"requester\":\"Synthetic requester\",\"type\":").append(quote(row.type))
                        .append(",\"actId\":").append(quote(activityId(row)))
                        .append(",\"woId\":").append(quote(workOrderId(row))).append('}');
            }
        }
        respond(exchange, 200, "application/json", bytes(output.append("]}").toString()));
    }
    private void attachments(HttpExchange exchange) throws IOException {
        synchronized (this) { legacyAttachmentLoads++; }
        Row row = find(parse(exchange.getRequestURI().getRawQuery()).get("ref"));
        delay(mode.contains("slow-attachments") ? 2300 : 700);
        StringBuilder response = new StringBuilder("{\"names\":[");
        List<String> names = names(row);
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) response.append(','); response.append(quote(names.get(index)));
        }
        respond(exchange, 200, "application/json", bytes(response.append("]}").toString()));
    }
    private void activityAttachments(HttpExchange exchange) throws IOException {
        synchronized (this) { activityAttachmentLoads++; }
        String actId = exchange.getRequestURI().getPath().substring("/proms/pmsAttachmentController/list/wo/".length());
        Row row = findActivity(actId);
        delay(mode.contains("slow-attachments") ? 2300 : 700);
        if (mode.contains("http-error")) { respond(exchange, 503, "application/json", bytes("[]")); return; }
        if (mode.contains("msg-code")) { respond(exchange, 200, "application/json", bytes("{\"msgCode\":\"SYNTHETIC_FAILURE\"}")); return; }
        if (mode.contains("invalid-body")) { respond(exchange, 200, "application/json", bytes("{\"names\":[]}")); return; }
        List<String> names = names(row);
        StringBuilder response = new StringBuilder("[");
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) response.append(',');
            response.append("{\"attachmentId\":").append(quote(mode.contains("duplicate-id") ? "duplicate-attachment" : "attachment-" + rows.indexOf(row) + "-" + index))
                    .append(",\"fileName\":").append(quote(mode.contains("empty-file-name") ? "" : names.get(index))).append('}');
        }
        respond(exchange, 200, "application/json", bytes(response.append(']').toString()));
    }
    private static List<String> names(Row row) {
        List<String> names = new ArrayList<>();
        if (row.attachmentNames != null) { java.util.Collections.addAll(names, row.attachmentNames); return names; }
        if (row.attachments == -1) names.add("Design.pdf");
        for (int index = 0; index < row.attachments; index++) names.add("RFS synthetic " + (index + 1) + ".pdf");
        return names;
    }
    private synchronized String activityId(Row row) { return "synthetic-activity-" + rows.indexOf(row); }
    private synchronized String workOrderId(Row row) { return "synthetic-work-order-" + rows.indexOf(row); }
    private synchronized Row findActivity(String actId) throws IOException {
        for (Row row : rows) if (activityId(row).equals(actId) || (activityId(row) + "-wrong").equals(actId)) return row;
        throw new IOException("Unknown synthetic activity");
    }
    private void pdf(HttpExchange exchange) throws IOException {
        Row row = find(parse(exchange.getRequestURI().getRawQuery()).get("ref"));
        synchronized (this) { downloads++; }
        byte[] content = row.validPdf ? pdfBytes(row.pdfText) : bytes("Synthetic invalid PDF");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"RFS synthetic.pdf\"");
        respond(exchange, 200, "application/pdf", content);
    }
    private void decision(HttpExchange exchange) throws IOException {
        Map<String, String> fields = parse(read(exchange.getRequestBody()));
        String ref = fields.get("ref"), action = fields.get("action"), comment = fields.get("description");
        synchronized (this) {
            attempts.add(ref);
            find(ref);
            if (mode.contains("server-failure")) { respond(exchange, 200, "application/json", bytes("{\"ok\":false}")); return; }
            if ((!"approve".equals(action) && !"reject".equals(action)) || comment == null || decisions.containsKey(ref)) {
                respond(exchange, 400, "application/json", bytes("{\"ok\":false}")); return;
            }
            if (!mode.contains("false-success")) { decisions.put(ref, action); comments.put(ref, comment); }
        }
        respond(exchange, 200, "application/json", bytes("{\"ok\":true}"));
    }
    private synchronized Row find(String reference) throws IOException {
        for (Row row : rows) if (row.reference.equals(reference)) return row;
        throw new IOException("Unknown synthetic reference");
    }
    public static byte[] pdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12); content.newLineAtOffset(50, 700);
                content.showText(text); content.endText();
            }
            document.save(output); return output.toByteArray();
        }
    }
    private static void delay(int millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Fixture interrupted", stopped); }
    }
    private static Map<String, String> parse(String text) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text == null) return fields;
        for (String field : text.split("&")) { String[] parts = field.split("=", 2); if (parts.length == 2) fields.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8")); }
        return fields;
    }
    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int size;
        while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }
    @Override public void close() { server.stop(0); }
}
