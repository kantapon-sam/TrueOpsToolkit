package com.java.myapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Preferences contain no credentials; the Toolkit's shared account file supplies the login. */
final class Tk07Settings {
    private final Map<String, String> preferences;
    private String username;
    private String password;

    private Tk07Settings(Map<String, String> preferences) { this.preferences = preferences; }

    static Tk07Settings read(Path file) throws IOException {
        Tk07Settings settings = readPreferences(file);
        settings.readSharedAccount();
        return settings;
    }

    static Tk07Settings readPreferences(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.trim().isEmpty()) continue;
            int separator = line.indexOf(',');
            String key = (separator < 0 ? line : line.substring(0, separator)).trim();
            if ("Search".equals(key) || "Description_Approve".equals(key) || "Description_Reject".equals(key)) {
                values.put(key, separator < 0 ? "" : line.substring(separator + 1));
            }
        }
        String search = values.get("Search");
        if (search == null || search.trim().isEmpty()) throw new IOException("Set a non-empty Search before starting TK07 Review.");
        String[] terms = search.trim().split("&&", -1);
        if (terms.length > 2) throw new IOException("Search supports at most two terms separated by &&.");
        for (String term : terms) if (term.trim().isEmpty()) throw new IOException("Every Search term must be non-empty.");
        values.put("Search", search.trim());
        return new Tk07Settings(values);
    }

    private void readSharedAccount() throws IOException {
        String configured = System.getProperty("trueops.home", "").trim();
        if (configured.isEmpty()) throw new IOException("Open TK07 Review from TrueOps Toolkit to use the shared account.");
        Object credentials = null;
        char[] secret = null;
        try {
            Class<?> store = Class.forName("trueopstoolkit.SharedCredentials");
            Optional<?> saved = (Optional<?>) store.getMethod("load", Path.class)
                    .invoke(null, Paths.get(configured).toAbsolutePath().normalize());
            if (!saved.isPresent()) throw new IOException("Set the shared Toolkit account before running TK07 Review.");
            credentials = saved.get();
            username = (String) credentials.getClass().getMethod("getUsername").invoke(credentials);
            secret = (char[]) credentials.getClass().getMethod("copyPassword").invoke(credentials);
            if (username == null || username.trim().isEmpty() || secret == null || secret.length == 0) {
                throw new IOException("Set the shared Toolkit account before running TK07 Review.");
            }
            password = new String(secret);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Cannot read the shared Toolkit account. Open Shared account settings.", failure);
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
            if (credentials instanceof AutoCloseable) {
                try { ((AutoCloseable) credentials).close(); }
                catch (Exception ignored) { }
            }
        }
    }

    String search() { return preferences.get("Search"); }
    String approvalDescription() { return preferences.containsKey("Description_Approve") ? preferences.get("Description_Approve") : ""; }
    String rejectionDescription() { return preferences.containsKey("Description_Reject") ? preferences.get("Description_Reject") : ""; }
    String username() { return username; }
    String password() { return password; }
}
