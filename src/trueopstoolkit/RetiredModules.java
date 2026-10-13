package trueopstoolkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Retires the bundled TUAR directory while keeping its complete data outside the installation. */
public final class RetiredModules {
    private RetiredModules() { }

    /** Caller must hold the launcher and active-tool locks for the installation. */
    public static Path archiveTuar(Path home) throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        Path backups = local == null || local.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".trueops-toolkit", "backups")
                : Paths.get(local, "TrueOpsToolkit", "backups");
        return archiveTuar(home, backups, null);
    }

    interface ArchiveHook { void afterVerified(Path original, Path backup) throws IOException; }

    /** Explicit backup location and hook keep the filesystem regression tests isolated. */
    static Path archiveTuar(Path home, Path backups, ArchiveHook hook) throws IOException {
        Path installation = home.toAbsolutePath().normalize();
        Path source = installation.resolve("modules").resolve("tuar");
        ToolkitUpdater.requireSafePath(installation, source);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The retired TUAR path is not a normal directory; its files were retained.");
        }
        Path parent = backups.toAbsolutePath().normalize();
        if (parent.startsWith(installation)) throw new IOException("TUAR backups must be outside the Toolkit installation.");
        Map<Path, Entry> original = snapshot(source);
        ToolkitUpdater.requireSafePath(parent.getRoot(), parent);
        Files.createDirectories(parent);
        ToolkitUpdater.requireSafePath(parent.getRoot(), parent);
        Path archive = Files.createTempDirectory(parent, "removed-tuar-");
        Path saved = archive.resolve("tuar");
        boolean verified = false;
        try {
            Files.createDirectory(saved);
            for (Map.Entry<Path, Entry> item : original.entrySet()) {
                Path from = source.resolve(item.getKey()), to = saved.resolve(item.getKey());
                ToolkitUpdater.requireSafePath(source, from);
                ToolkitUpdater.requireSafePath(archive, to);
                if (item.getKey().toString().isEmpty()) continue;
                if (item.getValue().directory) Files.createDirectory(to);
                else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            }
            requireEqual(original, snapshot(saved));
            requireEqual(original, snapshot(source));
            Properties info = new Properties();
            info.setProperty("original.directory", source.toString());
            info.setProperty("verification", "SHA-256 and directory structure");
            info.setProperty("entries", Integer.toString(original.size()));
            try (OutputStream output = Files.newOutputStream(archive.resolve("archive-info.properties"), StandardOpenOption.CREATE_NEW)) {
                info.store(output, "Verified TUAR backup; retain this directory to recover previous requests and attachments.");
            }
            verified = true;
            if (hook != null) hook.afterVerified(source, archive);
            // A concurrent edit must leave the original intact; never remove data absent from the backup.
            requireEqual(original, snapshot(source));
            List<Path> paths = new ArrayList<Path>(original.keySet());
            Collections.reverse(paths); // snapshot inserts each parent before its children.
            for (Path relative : paths) {
                Path file = source.resolve(relative).normalize();
                if (!file.startsWith(source)) throw new IOException("Retired TUAR removal escaped its original directory.");
                ToolkitUpdater.requireSafePath(source, file);
                Entry expected = original.get(relative);
                if (!expected.directory && !expected.equals(entry(file))) {
                    throw new IOException("TUAR files changed while being retired; changed originals were retained.");
                }
                Files.delete(file);
            }
            return archive;
        } catch (IOException | RuntimeException failure) {
            // A complete verified backup stays available even if Windows prevents removing some originals.
            // Incomplete copies also stay available; no automatic cleanup can erase recovery material.
            throw new IOException((verified ? "A verified TUAR backup is available at " : "TUAR retirement could not finish; recovery copies remain at ")
                    + archive + ". Existing Toolkit files were retained wherever removal could not complete.", failure);
        }
    }

    private static Map<Path, Entry> snapshot(Path root) throws IOException {
        Map<Path, Entry> contents = new LinkedHashMap<Path, Entry>();
        visit(root, root, contents);
        return contents;
    }

    private static void visit(Path root, Path file, Map<Path, Entry> contents) throws IOException {
        ToolkitUpdater.requireSafePath(root, file);
        Entry value = entry(file);
        contents.put(root.relativize(file), value);
        if (value.directory) {
            List<Path> children = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(file)) {
                for (Path child : stream) children.add(child);
            }
            Collections.sort(children);
            for (Path child : children) visit(root, child, contents);
        }
    }

    private static Entry entry(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("TUAR retirement refuses links, junctions, and special files; original data was retained.");
        }
        if (attributes.isDirectory()) return new Entry(true, 0L, null);
        if (!attributes.isRegularFile()) throw new IOException("TUAR contains an unsupported file; original data was retained.");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 verification is unavailable.", impossible); }
        long size = 0L;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) { digest.update(buffer, 0, read); size += read; }
        }
        if (size != attributes.size()) throw new IOException("TUAR files changed during backup; originals were retained.");
        return new Entry(false, size, digest.digest());
    }

    private static void requireEqual(Map<Path, Entry> expected, Map<Path, Entry> actual) throws IOException {
        if (!expected.equals(actual)) throw new IOException("TUAR backup verification failed or source files changed; originals were retained.");
    }

    private static final class Entry {
        final boolean directory;
        final long size;
        final byte[] hash;
        Entry(boolean directory, long size, byte[] hash) { this.directory = directory; this.size = size; this.hash = hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry value = (Entry) other;
            return directory == value.directory && size == value.size && Arrays.equals(hash, value.hash);
        }
        @Override public int hashCode() { return Arrays.hashCode(hash) + (directory ? 1 : 0) + (int) (size ^ (size >>> 32)); }
    }
}
