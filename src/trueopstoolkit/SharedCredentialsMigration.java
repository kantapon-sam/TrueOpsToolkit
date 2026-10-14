package trueopstoolkit;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Retire encrypted accounts without decryption; consolidate older plaintext module accounts. */
public final class SharedCredentialsMigration {
    private SharedCredentialsMigration() { }

    public static boolean migrate(Path home) throws IOException {return migrate(home,null);}

    static boolean migrate(Path home,Path tools) throws IOException {
        Optional<SharedCredentials.Credentials> existing=SharedCredentials.load(home);
        boolean hasSavedAccount=existing.isPresent();
        if(hasSavedAccount)existing.get().close();
        // Do not decrypt an old account or resurrect a stale module account during upgrade.
        if(Files.exists(SharedCredentials.encryptedFile(home))) {
            cleanLegacy(home,tools);
            return SharedCredentials.removeEncryptedAccount(home);
        }
        if(!hasSavedAccount) {
            Object[] legacy=null;
            for(Path directory:directories(home,null)) {
                legacy=readDirectoryAccount(directory);
                if(legacy!=null)break;
            }
            if(legacy==null && tools!=null)for(String module:new String[]{"approve","approve-ref","delete","tk07"}) {
                legacy=readDirectoryAccount(tools.resolve(module).resolve("seed"));if(legacy!=null)break;
            }
            if(legacy==null)return false;
            char[] password=(char[])legacy[1];
            try {
                SharedCredentials.save(home,(String)legacy[0],password);
                Optional<SharedCredentials.Credentials> verified=SharedCredentials.load(home);
                if(!verified.isPresent())throw new IOException("Could not verify the saved account. The original data has been retained.");
                try(SharedCredentials.Credentials credentials=verified.get()) {
                    char[] actual=credentials.copyPassword();
                    try {if(!credentials.getUsername().equals(legacy[0]) || !Arrays.equals(password,actual))throw new IOException("Account migration verification failed. The original data has been retained.");}
                    finally {Arrays.fill(actual,'\0');}
                }
            } finally {Arrays.fill(password,'\0');Arrays.fill(legacy,null);}
        }
        cleanLegacy(home,tools);
        return false;
    }

    static void cleanLegacy(Path home,Path tools) throws IOException {
        for(Path directory:directories(home,tools)) {
            for(String name:new String[]{"Login.txt","settings.csv"}) {
                Path login=directory.resolve(name);
                if(!Files.isRegularFile(login))continue;
                List<String> retained=new ArrayList<String>();boolean changed=false;
                for(String raw:Files.readAllLines(login,StandardCharsets.UTF_8)) {
                    String line=stripBom(raw),key=line.split(",",2)[0].trim();
                    if(isCredentialKey(key))changed=true;else retained.add(line);
                }
                if(changed)atomicWrite(login,(String.join("\r\n",retained)+"\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static boolean isCredentialKey(String key) {
        return "User".equalsIgnoreCase(key) || "Username".equalsIgnoreCase(key) || "Password".equalsIgnoreCase(key);
    }
    private static List<Path> directories(Path home,Path tools) {
        List<Path> directories=new ArrayList<Path>();
        for(ModuleSpec module:ModuleSpec.values())directories.add(module.directory(home));
        if(tools!=null)for(ModuleSpec module:ModuleSpec.values())directories.add(tools.resolve(module.id).resolve("seed"));
        return directories;
    }
    private static Object[] readDirectoryAccount(Path directory) throws IOException {
        Object[] current=readLegacyLogin(directory.resolve("settings.csv"));
        return current==null?readLegacyLogin(directory.resolve("Login.txt")):current;
    }
    private static Object[] readLegacyLogin(Path file) throws IOException {
        if(!Files.isRegularFile(file))return null;
        String username="",password="";
        for(String raw:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            String[] pair=stripBom(raw).split(",",2);if(pair.length!=2)continue;
            String key=pair[0].trim();
            if("User".equalsIgnoreCase(key) || "Username".equalsIgnoreCase(key))username=pair[1].trim();
            else if("Password".equalsIgnoreCase(key))password=pair[1];
        }
        return username.isEmpty() || password.isEmpty()?null:new Object[]{username,password.toCharArray()};
    }
    private static String stripBom(String line){return line.startsWith("\uFEFF")?line.substring(1):line;}
    private static void atomicWrite(Path target,byte[] content) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),".preferences-",".tmp");
        try {Files.write(temporary,content);try{Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}}
        finally {Files.deleteIfExists(temporary);}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2 && args.length!=4)throw new IllegalArgumentException("Usage: --home PATH [--seeds TOOLS]");
        if(!"--home".equals(args[0]) || (args.length==4 && !"--seeds".equals(args[2])))throw new IllegalArgumentException("Usage: --home PATH [--seeds TOOLS]");
        Path home=Paths.get(args[1]).toAbsolutePath();Files.createDirectories(home);
        try(FileChannel launcher=FileChannel.open(home.resolve(".launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileChannel channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock launcherLock=launcher.tryLock();if(launcherLock==null)throw new IOException("Close the Toolkit launcher before migrating account settings.");
            FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Close the active module before migrating account settings.");
            try {
                boolean cleared=migrate(home,args.length==4?Paths.get(args[3]).toAbsolutePath():null);
                SettingsFiles.migrate(home);
                Optional<SharedCredentials.Credentials> account=SharedCredentials.load(home);
                if(account.isPresent()){account.get().close();System.out.println("Shared account migration completed; legacy credentials removed, no credential values logged.");}
                else if(cleared)System.out.println("Old encrypted account removed without decryption. Enter the account again in Shared account; new accounts are saved without encryption.");
                else System.out.println("No complete account to import. Set the shared account in the application; legacy cleanup is pending until then.");
            }
            finally {lock.release();launcherLock.release();}
        }
    }
}
