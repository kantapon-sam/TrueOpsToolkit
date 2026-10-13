package com.java.myapp;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads the UTF-8 files maintained by the Toolkit settings page. */
final class PromsSettings {
    private final Map<String, String> values;

    private PromsSettings(Map<String, String> values) {
        this.values = values;
    }

    static PromsSettings readLogin(String path, boolean requireSearch) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        boolean managed = !System.getProperty("trueops.home", "").trim().isEmpty();
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = stripBom(lines.get(index), index);
            if (line.trim().isEmpty()) {
                continue;
            }
            int separator = line.indexOf(',');
            String key = (separator < 0 ? line : line.substring(0, separator)).trim();
            if ((!managed && ("User".equals(key) || "Password".equals(key)))
                    || "Search".equals(key) || "Description".equals(key)) {
                values.put(key, separator < 0 ? "" : line.substring(separator + 1));
            }
        }
        if (managed) {
            readSharedAccount(values);
        }
        PromsSettings settings = new PromsSettings(values);
        if (settings.get("User").trim().isEmpty() || settings.get("Password").isEmpty()) {
            throw new IOException("Set User and Password in the Toolkit login settings before running.");
        }
        if (requireSearch) {
            String search = settings.get("Search").trim();
            if (search.isEmpty()) {
                throw new IOException("Set a non-empty Search in the Toolkit settings before approving.");
            }
            String[] terms = search.split("&&", -1);
            if (terms.length > 4) {
                throw new IOException("Search supports at most four terms separated by &&.");
            }
            for (String term : terms) {
                if (term.trim().isEmpty()) {
                    throw new IOException("Every Search term separated by && must be non-empty.");
                }
            }
            values.put("Search", search);
        }
        return settings;
    }

    private static void readSharedAccount(Map<String, String> values) throws IOException {
        Object credentials = null;
        char[] password = null;
        try {
            Class<?> store = Class.forName("trueopstoolkit.SharedCredentials");
            Path home = Paths.get(System.getProperty("trueops.home")).toAbsolutePath().normalize();
            Optional<?> saved = (Optional<?>) store.getMethod("load", Path.class).invoke(null, home);
            if (!saved.isPresent()) {
                throw new IOException("Set the shared Toolkit account before running.");
            }
            credentials = saved.get();
            String username = (String) credentials.getClass().getMethod("getUsername").invoke(credentials);
            password = (char[]) credentials.getClass().getMethod("copyPassword").invoke(credentials);
            if (username == null || username.trim().isEmpty() || password == null || password.length == 0) {
                throw new IOException("Set the shared Toolkit account before running.");
            }
            values.put("User", username);
            values.put("Password", new String(password));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IOException("Cannot read the shared Toolkit account. Open Shared account settings.", ex);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (credentials instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) credentials).close();
                } catch (Exception ignored) {
                    // The vault releases its temporary decrypted password buffer.
                }
            }
        }
    }

    String get(String name) {
        String value = values.get(name);
        return value == null ? "" : value;
    }

    static List<String[]> readReferences(String path, int columns) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = stripBom(lines.get(index), index);
            if (line.trim().isEmpty()) {
                continue;
            }
            // The final field is free text and may itself contain commas.
            String[] row = line.split(",", columns);
            if (row.length != columns || row[0].trim().isEmpty()
                    || row[1].trim().isEmpty()) {
                String format = columns == 3 ? "ref,type,description" : "ref,reason";
                throw new IOException("Invalid Ref_No.csv row " + (index + 1)
                        + ": expected " + format + " with non-empty required fields.");
            }
            row[0] = row[0].trim();
            if (columns == 3) {
                row[1] = row[1].trim();
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IOException("Add at least one reference in the Toolkit settings before running.");
        }
        return rows;
    }

    private static String stripBom(String line, int index) {
        return index == 0 && line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
