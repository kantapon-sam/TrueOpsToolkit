package trueopstoolkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Uses only synthetic legacy accounts in disposable folders. */
public final class SharedCredentialsMigrationTest {
    public static void main(String[] args) throws Exception {
        Path temporary=Files.createTempDirectory("trueops-migration-"),home=temporary.resolve("runtime"),tools=temporary.resolve("tools");
        try {
            for(String id:new String[]{"approve","approve-ref","delete"}) {
                write(home.resolve("modules/"+id+"/Login.txt"),"User,"+id+"-user\r\nPassword,"+id+"-secret,ไทย\r\nSearch,SAMPLE\r\nDescription,ทดสอบ,comma\r\n");
                write(tools.resolve(id+"/seed/Login.txt"),"User,seed-user\r\nPassword,seed-secret\r\nSearch,SAMPLE\r\n");
            }
            write(home.resolve("modules/approve/settings.csv"),"\uFEFFUser,current-user\r\nPassword,current-secret,ไทย\r\nSearch,CURRENT\r\nDescription,current,comma\r\n");
            write(tools.resolve("approve/seed/settings.csv"),"User,current-seed-user\r\nPassword,current-seed-secret\r\nDescription,current seed\r\n");
            Path prefs=home.resolve("modules/tuar/User/settings.properties"),old=home.resolve("modules/tuar/User/login.properties");
            write(prefs,"username=old-user\npassword=old-secret\nrememberLogin=true\nheadless=true\n");
            write(old,"username=old-user\npassword=legacy-data\nserverIp=192.0.2.50\nloginType=CLLS\n");
            Path tuarLogin=home.resolve("modules/tuar/Login.txt"),tuarCsv=home.resolve("modules/tuar/settings.csv"),tuarSeed=tools.resolve("tuar/seed/Login.txt");
            write(tuarLogin,"User,separate-user\nPassword,separate-secret\nSearch,separate search\n");write(tuarCsv,"User,separate-current\nPassword,separate-current-secret\n");write(tuarSeed,"User,separate-seed\nPassword,separate-seed-secret\n");
            byte[] tuarPrefs=Files.readAllBytes(prefs),tuarAccount=Files.readAllBytes(old),tuarLegacy=Files.readAllBytes(tuarLogin),tuarSettings=Files.readAllBytes(tuarCsv),tuarSeedBytes=Files.readAllBytes(tuarSeed);
            SharedCredentialsMigration.migrate(home,tools);
            try(SharedCredentials.Credentials account=SharedCredentials.load(home).get()) {
                char[] password=account.copyPassword();try{check("current-user".equals(account.getUsername()) && Arrays.equals(password,"current-secret,ไทย".toCharArray()),"current settings account imported and encrypted before the old filename is removed");}finally{Arrays.fill(password,'\0');}
            }
            for(String id:new String[]{"approve","approve-ref","delete"}) {
                String runtime=read(home.resolve("modules/"+id+"/Login.txt")),seed=read(tools.resolve(id+"/seed/Login.txt"));
                check(!runtime.contains("User,") && !runtime.contains("Password,") && runtime.contains("Description,ทดสอบ,comma"),"runtime credentials removed; preferences preserved");
                check(!seed.contains("User,") && !seed.contains("Password,"),"seed credentials removed");
            }
            check(!read(home.resolve("modules/approve/settings.csv")).contains("Password,") && read(home.resolve("modules/approve/settings.csv")).contains("Description,current,comma"),"credentials removed from current preferences without losing comma values");
            check(!read(tools.resolve("approve/seed/settings.csv")).contains("User,") && read(tools.resolve("approve/seed/settings.csv")).contains("Description,current seed"),"current seed preferences are also cleaned");
            SettingsFiles.migrate(home);
            for(String id:new String[]{"approve","approve-ref","delete"})check(!Files.exists(home.resolve("modules/"+id+"/Login.txt")) && Files.isRegularFile(home.resolve("modules/"+id+"/settings.csv")),"encrypted account migration permits the settings filename change: "+id);
            check(read(home.resolve("modules/approve/settings-legacy.csv")).contains("Description,ทดสอบ,comma") && !read(home.resolve("modules/approve/settings-legacy.csv")).contains("Password,"),"conflicting settings backup contains preferences only");
            check(Arrays.equals(tuarPrefs,Files.readAllBytes(prefs)) && Arrays.equals(tuarAccount,Files.readAllBytes(old)) && Arrays.equals(tuarLegacy,Files.readAllBytes(tuarLogin)) && Arrays.equals(tuarSettings,Files.readAllBytes(tuarCsv)) && Arrays.equals(tuarSeedBytes,Files.readAllBytes(tuarSeed)),"the separate TUAR application's account and files are untouched");
            byte[] encrypted=Files.readAllBytes(SharedCredentials.file(home));SharedCredentialsMigration.migrate(home,tools);
            SettingsFiles.migrate(home);
            check(Arrays.equals(encrypted,Files.readAllBytes(SharedCredentials.file(home))),"migration is idempotent");
            write(home.resolve("modules/approve/Login.txt"),"User,stale-user\nPassword,stale-secret\nSearch,SAMPLE\n");
            write(home.resolve("modules/approve/settings.csv"),"User,current-recovery\nPassword,current-recovery-secret\nSearch,KEEP\n");
            byte[] damaged=encrypted.clone();damaged[damaged.length-1]^=1;Files.write(SharedCredentials.file(home),damaged);
            boolean rejected=false;try{SharedCredentialsMigration.migrate(home,tools);}catch(IOException expected){rejected=true;}
            check(rejected && read(home.resolve("modules/approve/Login.txt")).contains("stale-secret") && read(home.resolve("modules/approve/settings.csv")).contains("current-recovery-secret"),"failed vault validation keeps both legacy and current data for recovery");
            check(Arrays.equals(damaged,Files.readAllBytes(SharedCredentials.file(home))),"failed migration never overwrites damaged vault");
            Path separateOnly=temporary.resolve("separate-tuar-only");write(separateOnly.resolve("modules/tuar/Login.txt"),"User,separate-only\nPassword,separate-only-secret\n");write(separateOnly.resolve("modules/tuar/User/login.properties"),"username=separate-only\npassword=separate-only-secret\n");
            SharedCredentialsMigration.migrate(separateOnly);SettingsFiles.migrate(separateOnly);
            check(!Files.exists(SharedCredentials.file(separateOnly)) && read(separateOnly.resolve("modules/tuar/Login.txt")).contains("separate-only-secret") && Files.exists(separateOnly.resolve("modules/tuar/User/login.properties")),"TUAR cannot supply a fallback account and its files are not renamed");
            System.out.println("SHARED CREDENTIAL MIGRATION PASS");
        } finally {try(Stream<Path> paths=Files.walk(temporary)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static void write(Path path,String value) throws IOException {Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    private static String read(Path path) throws IOException {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void check(boolean passed,String label){if(!passed)throw new AssertionError(label);System.out.println("PASS: "+label);}
}
