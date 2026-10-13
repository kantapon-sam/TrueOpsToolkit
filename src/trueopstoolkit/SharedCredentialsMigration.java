package trueopstoolkit;

import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Import before removing legacy credentials. Never writes a plaintext backup. */
public final class SharedCredentialsMigration {
    private SharedCredentialsMigration() { }

    public static void migrate(Path home) throws IOException {migrate(home,null);}

    static void migrate(Path home,Path tools) throws IOException {
        Optional<SharedCredentials.Credentials> existing=SharedCredentials.load(home);
        if(existing.isPresent()) {
            existing.get().close();
        } else {
            Object[] legacy=null;
            for(Path directory:directories(home,null)) {
                legacy=readLegacyLogin(directory.resolve("Login.txt"));
                if(legacy!=null)break;
            }
            if(legacy==null)legacy=readLegacyTuar(home);
            if(legacy==null && tools!=null)for(String module:new String[]{"approve","approve-ref","delete","tuar"}) {
                legacy=readLegacyLogin(tools.resolve(module).resolve("seed/Login.txt"));if(legacy!=null)break;
            }
            if(legacy==null)return;
            char[] password=(char[])legacy[1];
            try {
                SharedCredentials.save(home,(String)legacy[0],password);
                Optional<SharedCredentials.Credentials> verified=SharedCredentials.load(home);
                if(!verified.isPresent())throw new IOException("Could not verify the encrypted account. The original data has been retained.");
                try(SharedCredentials.Credentials credentials=verified.get()) {
                    char[] actual=credentials.copyPassword();
                    try {if(!credentials.getUsername().equals(legacy[0]) || !Arrays.equals(password,actual))throw new IOException("Account migration verification failed. The original data has been retained.");}
                    finally {Arrays.fill(actual,'\0');}
                }
            } finally {Arrays.fill(password,'\0');Arrays.fill(legacy,null);}
        }
        cleanLegacy(home,tools);
    }

    static void cleanLegacy(Path home,Path tools) throws IOException {
        for(Path directory:directories(home,tools)) {
            Path login=directory.resolve("Login.txt");
            if(Files.isRegularFile(login)) {
                List<String> retained=new ArrayList<String>();boolean changed=false;
                for(String raw:Files.readAllLines(login,StandardCharsets.UTF_8)) {
                    String line=stripBom(raw),key=line.split(",",2)[0].trim();
                    if(isCredentialKey(key))changed=true;else retained.add(line);
                }
                if(changed)atomicWrite(login,(String.join("\r\n",retained)+"\r\n").getBytes(StandardCharsets.UTF_8));
            }
            Path settings=directory.resolve("User/settings.properties");
            Path oldAccount=directory.resolve("User/login.properties");
            if(Files.isRegularFile(settings) || Files.isRegularFile(oldAccount)) {
                Properties values=new Properties();if(Files.isRegularFile(settings))try(InputStream input=Files.newInputStream(settings)){values.load(input);}
                boolean changed=false;
                if(Files.isRegularFile(oldAccount)) {
                    Properties old=new Properties();try(InputStream input=Files.newInputStream(oldAccount)){old.load(input);}
                    for(String key:new String[]{"serverIp","loginType"})if(!values.containsKey(key) && old.containsKey(key)){values.setProperty(key,old.getProperty(key));changed=true;}
                }
                for(String key:new ArrayList<String>(values.stringPropertyNames()))if(isCredentialKey(key) || "rememberLogin".equalsIgnoreCase(key)){values.remove(key);changed=true;}
                if(changed){ByteArrayOutputStream output=new ByteArrayOutputStream();values.store(output,"TUAR preferences; account is encrypted in the shared vault");atomicWrite(settings,output.toByteArray());}
            }
            Files.deleteIfExists(oldAccount);
        }
    }

    static boolean isCredentialKey(String key) {
        return "User".equalsIgnoreCase(key) || "Username".equalsIgnoreCase(key) || "Password".equalsIgnoreCase(key);
    }
    private static List<Path> directories(Path home,Path tools) {
        List<Path> directories=new ArrayList<Path>();
        for(String module:new String[]{"approve","approve-ref","delete","tuar"})directories.add(home.resolve("modules").resolve(module));
        if(tools!=null)for(String module:new String[]{"approve","approve-ref","delete","tuar"})directories.add(tools.resolve(module).resolve("seed"));
        return directories;
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
    private static Object[] readLegacyTuar(Path home) throws IOException {
        Path folder=home.resolve("modules/tuar"),file=folder.resolve("User/login.properties");
        if(!Files.isRegularFile(file))return null;
        Path jar=folder.resolve("TrueOps-TUAR.jar");if(!Files.isRegularFile(jar))jar=folder.resolve("AutoRequestTUAR.jar");
        if(!Files.isRegularFile(jar))throw new IOException("The TUAR application needed to migrate the existing account is missing. Build the project first.");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},SharedCredentialsMigration.class.getClassLoader())) {
            Class<?> type=Class.forName("autorequesttuar.CredentialStore",true,loader);
            Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
            Method load=type.getDeclaredMethod("load");load.setAccessible(true);
            Optional<?> value=(Optional<?>)load.invoke(constructor.newInstance(file));
            if(!value.isPresent())return null;
            Object credentials=value.get();
            try {
                Method username=credentials.getClass().getDeclaredMethod("getUsername"),password=credentials.getClass().getDeclaredMethod("copyPassword");
                username.setAccessible(true);password.setAccessible(true);
                return new Object[]{username.invoke(credentials),password.invoke(credentials)};
            } finally {Method close=credentials.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(credentials);}
        } catch(Exception ex){throw new IOException("Could not read the existing TUAR account. Set up a shared account in the app. The original data has been retained.");}
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
                migrate(home,args.length==4?Paths.get(args[3]).toAbsolutePath():null);
                Optional<SharedCredentials.Credentials> account=SharedCredentials.load(home);
                if(account.isPresent()){account.get().close();System.out.println("Shared account migration completed; legacy credentials removed, no credential values logged.");}
                else System.out.println("No complete account to import. Set the shared account in the application; legacy cleanup is pending until then.");
            }
            finally {lock.release();launcherLock.release();}
        }
    }
}
