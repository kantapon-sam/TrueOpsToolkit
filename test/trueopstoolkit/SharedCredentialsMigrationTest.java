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
            Path prefs=home.resolve("modules/tuar/User/settings.properties"),old=home.resolve("modules/tuar/User/login.properties");
            write(prefs,"username=old-user\npassword=old-secret\nrememberLogin=true\nheadless=true\n");
            write(old,"username=old-user\npassword=legacy-data\nserverIp=192.0.2.50\nloginType=CLLS\n");
            SharedCredentialsMigration.migrate(home,tools);
            try(SharedCredentials.Credentials account=SharedCredentials.load(home).get()) {
                char[] password=account.copyPassword();try{check("approve-user".equals(account.getUsername()) && Arrays.equals(password,"approve-secret,ไทย".toCharArray()),"Approve account imported and verified");}finally{Arrays.fill(password,'\0');}
            }
            for(String id:new String[]{"approve","approve-ref","delete"}) {
                String runtime=read(home.resolve("modules/"+id+"/Login.txt")),seed=read(tools.resolve(id+"/seed/Login.txt"));
                check(!runtime.contains("User,") && !runtime.contains("Password,") && runtime.contains("Description,ทดสอบ,comma"),"runtime credentials removed; preferences preserved");
                check(!seed.contains("User,") && !seed.contains("Password,"),"seed credentials removed");
            }
            check(!Files.exists(old),"obsolete TUAR credential file removed");
            Properties properties=new Properties();try(InputStream input=Files.newInputStream(prefs)){properties.load(input);}
            check(!properties.containsKey("username") && !properties.containsKey("password") && "192.0.2.50".equals(properties.getProperty("serverIp")) && "CLLS".equals(properties.getProperty("loginType")) && "true".equals(properties.getProperty("headless")),"TUAR endpoint and preferences preserved without account data");
            byte[] encrypted=Files.readAllBytes(SharedCredentials.file(home));SharedCredentialsMigration.migrate(home,tools);
            check(Arrays.equals(encrypted,Files.readAllBytes(SharedCredentials.file(home))),"migration is idempotent");
            write(home.resolve("modules/approve/Login.txt"),"User,stale-user\nPassword,stale-secret\nSearch,SAMPLE\n");
            byte[] damaged=encrypted.clone();damaged[damaged.length-1]^=1;Files.write(SharedCredentials.file(home),damaged);
            boolean rejected=false;try{SharedCredentialsMigration.migrate(home,tools);}catch(IOException expected){rejected=true;}
            check(rejected && read(home.resolve("modules/approve/Login.txt")).contains("stale-secret"),"failed vault validation keeps legacy data for recovery");
            check(Arrays.equals(damaged,Files.readAllBytes(SharedCredentials.file(home))),"failed migration never overwrites damaged vault");
            System.out.println("SHARED CREDENTIAL MIGRATION PASS");
        } finally {try(Stream<Path> paths=Files.walk(temporary)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static void write(Path path,String value) throws IOException {Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    private static String read(Path path) throws IOException {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void check(boolean passed,String label){if(!passed)throw new AssertionError(label);System.out.println("PASS: "+label);}
}
