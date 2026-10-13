package trueopstoolkit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Renames legacy preferences without re-encoding their bytes. Meaningful legacy
 * settings replace an empty release template; distinct current settings remain
 * authoritative and the legacy bytes move to an unused backup filename.
 */
final class SettingsFiles {
    private SettingsFiles() { }

    static void migrate(Path home) throws IOException {
        for(ModuleSpec module:ModuleSpec.values())resolve(module.directory(home));
    }

    static synchronized Path resolve(Path directory) throws IOException {
        Path current=directory.resolve("settings.csv"),legacy=directory.resolve("Login.txt");
        byte[] old=read(legacy),saved=read(current);
        // Import and verify the shared account before removing its original file.
        rejectCredentials(old);rejectCredentials(saved);
        if(old==null)return current;
        if(saved==null || (!meaningful(saved) && meaningful(old))) {
            verify(legacy,old);verify(current,saved);
            move(legacy,current,saved!=null);
        } else {
            // The current settings are authoritative. Retain any distinct legacy
            // content under a new name rather than silently dropping it.
            if(Arrays.equals(old,saved) || !meaningful(old)) {
                verify(legacy,old);verify(current,saved);Files.delete(legacy);
            } else {
                Path backup=backup(directory);
                verify(legacy,old);verify(current,saved);
                move(legacy,backup,false);
            }
        }
        return current;
    }

    private static Path backup(Path directory) throws IOException {
        for(int number=0;number<Integer.MAX_VALUE;number++) {
            Path candidate=directory.resolve(number==0?"settings-legacy.csv":"settings-legacy-"+number+".csv");
            if(!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))return candidate;
        }
        throw new IOException("Could not choose a filename for the legacy settings backup.");
    }
    private static void rejectCredentials(byte[] bytes) throws IOException {
        if(bytes==null)return;
        for(String line:text(bytes).split("\\r?\\n",-1)) {
            String[] pair=line.split(",",2);
            if(pair.length==2 && SharedCredentialsMigration.isCredentialKey(pair[0].trim()) && !pair[1].trim().isEmpty())
                throw new IOException("Import the existing account into Shared account before renaming its settings file. The original data has been retained.");
        }
    }
    private static boolean meaningful(byte[] bytes) throws IOException {
        for(String raw:text(bytes).split("\\r?\\n",-1)) {
            String line=raw.trim();if(line.isEmpty())continue;
            String[] pair=line.split(",",2);
            if(pair.length==2){String key=pair[0].trim(),value=pair[1].trim();if(("Search".equalsIgnoreCase(key) || "Description".equalsIgnoreCase(key) || "Description_Approve".equalsIgnoreCase(key) || "Description_Reject".equalsIgnoreCase(key)) && value.isEmpty())continue;if("SearchDelayMs".equalsIgnoreCase(key))continue;}
            return true;
        }
        return false;
    }
    private static String text(byte[] bytes) throws IOException {
        String value=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return value.startsWith("\uFEFF")?value.substring(1):value;
    }
    private static byte[] read(Path file) throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The settings path must be a regular file: "+file.getFileName());
        return Files.readAllBytes(file);
    }
    private static void verify(Path file,byte[] expected) throws IOException {
        if(!Arrays.equals(expected,read(file)))throw new IOException("The settings files changed while they were being migrated. Reload saved settings and try again.");
    }
    private static void move(Path source,Path target,boolean replace) throws IOException {
        if(!replace && Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());
        try {
            if(replace)Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
        } catch(AtomicMoveNotSupportedException unsupported) {
            if(replace)Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);else Files.move(source,target);
        }
    }
}
