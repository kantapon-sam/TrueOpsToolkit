package trueopstoolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/** Synthetic settings only: no real account or website is used. */
public final class ModuleSettingsTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("trueops-settings-test-");
        try {
            for(ModuleSpec module:new ModuleSpec[]{ModuleSpec.APPROVE,ModuleSpec.REF,ModuleSpec.DELETE}) {
                Path initial=module.directory(home).resolve("Login.txt");Files.createDirectories(initial.getParent());
                byte[] legacy="\uFEFFSearch,legacy&&second\r\nDescription,ไทย,comma\r\nExtra,keep me\r\n".getBytes(StandardCharsets.UTF_8);
                Files.write(initial,legacy);
                ModuleSettings s=new ModuleSettings(home,module);s.load();
                Path login=module.directory(home).resolve("settings.csv");
                check(!Files.exists(initial) && Arrays.equals(legacy,Files.readAllBytes(login)),"legacy preferences migrate to settings.csv without changing bytes: "+module.id);
                check("legacy&&second".equals(s.search) && "ไทย,comma".equals(s.description),"UTF-8 settings and comma values migrate correctly");
                s.search="sample&&second";s.description="ทดสอบ, comma";
                if(s.hasReferences())s.rows.add(module==ModuleSpec.REF?new String[]{"REF-1","Services","อนุมัติ,ทดสอบ"}:new String[]{"REF-1","เหตุผล,ทดสอบ"});
                s.validate(true);s.save();
                ModuleSettings reopened=new ModuleSettings(home,module);reopened.load();reopened.validate(true);
                String saved=new String(Files.readAllBytes(login),StandardCharsets.UTF_8);
                check(!saved.contains("User,") && !saved.contains("Password,"),"module preferences never retain credentials");
                if(s.hasReferences())check(Arrays.equals(s.rows.get(0),reopened.rows.get(0)),"reference description/reason preserves Thai and commas");
                byte[] bytes=Files.readAllBytes(login);
                s.description="invalid\nvalue";expectFailure(() -> s.save());
                check(Arrays.equals(bytes,Files.readAllBytes(login)),"invalid save leaves original untouched");
                s.load();Files.write(login,"Extra,keep me\r\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                expectFailure(() -> s.save());s.load();s.save();
                check(new String(Files.readAllBytes(login),StandardCharsets.UTF_8).contains("Extra,keep me"),"unknown settings survive editing");
                check(module.driver(home).equals(ModuleSpec.APPROVE.driver(home)),"one driver path for every module");
            }
            ModuleSettings approve=new ModuleSettings(home,ModuleSpec.APPROVE);approve.load();
            for(String invalid:new String[]{""," ","x&&","&&y","x&&y&&z"}){approve.search=invalid;expectFailure(() -> approve.validate(true));}
            ModuleSettings deletion=new ModuleSettings(home,ModuleSpec.DELETE);deletion.load();deletion.rows.clear();expectFailure(() -> deletion.validate(true));
            deletion.rows.add(new String[]{"REF-1",""});expectFailure(() -> deletion.save());
            Path ref=ModuleSpec.REF.directory(home).resolve("Ref_No.csv");Files.write(ref,"\uFEFFREF-2,Services,ไทย,มีจุลภาค\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            ModuleSettings bom=new ModuleSettings(home,ModuleSpec.REF);bom.load();check(bom.rows.size()==1 && "REF-2".equals(bom.rows.get(0)[0]),"BOM and blank lines load correctly");
            migrationCases(home.resolve("migration-cases"));
            System.out.println("MODULE SETTINGS PASS");
        } finally {try(Stream<Path> files=Files.walk(home)){files.sorted(Comparator.reverseOrder()).forEach(path -> {try{Files.deleteIfExists(path);}catch(IOException ex){throw new RuntimeException(ex);}});}}
    }
    private static void migrationCases(Path cases) throws Exception {
        Path overlay=cases.resolve("overlay");Files.createDirectories(overlay);
        byte[] legacy="Search,legacy&&keep\r\nDescription,ไทย,comma\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(overlay.resolve("Login.txt"),legacy);
        Files.write(overlay.resolve("settings.csv"),"\uFEFFSearch,\r\nDescription,  \r\n".getBytes(StandardCharsets.UTF_8));
        check(Arrays.equals(legacy,Files.readAllBytes(SettingsFiles.resolve(overlay))) && !Files.exists(overlay.resolve("Login.txt")),"an empty release template preserves meaningful legacy preferences");
        Path conflict=cases.resolve("conflict");Files.createDirectories(conflict);
        byte[] current="Search,current\r\nDescription,new,comma\r\n".getBytes(StandardCharsets.UTF_8),existing="Search,existing-backup\n".getBytes(StandardCharsets.UTF_8);
        Files.write(conflict.resolve("Login.txt"),legacy);Files.write(conflict.resolve("settings.csv"),current);Files.write(conflict.resolve("settings-legacy.csv"),existing);
        SettingsFiles.resolve(conflict);
        check(Arrays.equals(current,Files.readAllBytes(conflict.resolve("settings.csv"))) && !Files.exists(conflict.resolve("Login.txt")),"current preferences remain authoritative when both files contain settings");
        check(Arrays.equals(existing,Files.readAllBytes(conflict.resolve("settings-legacy.csv"))) && Arrays.equals(legacy,Files.readAllBytes(conflict.resolve("settings-legacy-1.csv"))),"a conflict preserves every legacy byte and never overwrites an existing backup");
        SettingsFiles.resolve(conflict);
        check(!Files.exists(conflict.resolve("settings-legacy-2.csv")),"filename migration is idempotent");
        Path duplicate=cases.resolve("duplicate");Files.createDirectories(duplicate);Files.write(duplicate.resolve("Login.txt"),current);Files.write(duplicate.resolve("settings.csv"),current);
        SettingsFiles.resolve(duplicate);check(!Files.exists(duplicate.resolve("Login.txt")) && !Files.exists(duplicate.resolve("settings-legacy.csv")),"identical legacy preferences need no duplicate backup");
        Path credentials=cases.resolve("credentials");Files.createDirectories(credentials);
        byte[] privateLegacy="User,synthetic-user\r\nPassword,synthetic-secret\r\nSearch,keep\r\n".getBytes(StandardCharsets.UTF_8);Files.write(credentials.resolve("Login.txt"),privateLegacy);
        expectFailure(() -> SettingsFiles.resolve(credentials));
        check(Arrays.equals(privateLegacy,Files.readAllBytes(credentials.resolve("Login.txt"))) && !Files.exists(credentials.resolve("settings.csv")),"unencrypted legacy credentials remain available for account migration");
        Files.delete(credentials.resolve("Login.txt"));Files.write(credentials.resolve("settings.csv"),privateLegacy);expectFailure(() -> SettingsFiles.resolve(credentials));
        check(Arrays.equals(privateLegacy,Files.readAllBytes(credentials.resolve("settings.csv"))),"unencrypted current credentials cannot be removed by editing preferences");
        Path symlink=cases.resolve("non-file");Files.createDirectories(symlink.resolve("Login.txt"));expectFailure(() -> SettingsFiles.resolve(symlink));
        check(Files.isDirectory(symlink.resolve("Login.txt")),"invalid settings paths remain untouched");
    }
    private interface Operation {void run() throws Exception;}
    private static void expectFailure(Operation operation) throws Exception {try{operation.run();throw new AssertionError("Expected validation failure");}catch(IOException expected){}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS: "+label);}
}
