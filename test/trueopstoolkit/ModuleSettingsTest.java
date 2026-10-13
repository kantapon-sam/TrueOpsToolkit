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
                Files.write(initial,"User,test-user\r\nPassword,synthetic-secret\r\n".getBytes(StandardCharsets.UTF_8));
                ModuleSettings s=new ModuleSettings(home,module);s.load();
                s.search="sample&&second";s.description="ทดสอบ, comma";
                if(s.hasReferences())s.rows.add(module==ModuleSpec.REF?new String[]{"REF-1","Services","อนุมัติ,ทดสอบ"}:new String[]{"REF-1","เหตุผล,ทดสอบ"});
                s.validate(true);s.save();
                ModuleSettings reopened=new ModuleSettings(home,module);reopened.load();reopened.validate(true);
                String saved=new String(Files.readAllBytes(initial),StandardCharsets.UTF_8);
                check(!saved.contains("test-user") && !saved.contains("synthetic-secret") && !saved.contains("Password,"),"module preferences never retain credentials");
                if(s.hasReferences())check(Arrays.equals(s.rows.get(0),reopened.rows.get(0)),"reference description/reason preserves Thai and commas");
                Path login=module.directory(home).resolve("Login.txt");
                byte[] bytes=Files.readAllBytes(login);
                s.description="invalid\nvalue";expectFailure(() -> s.save());
                check(Arrays.equals(bytes,Files.readAllBytes(login)),"invalid save leaves original untouched");
                s.load();Files.write(login,"Extra,keep me\r\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                expectFailure(() -> s.save());s.load();s.save();
                check(new String(Files.readAllBytes(login),StandardCharsets.UTF_8).contains("Extra,keep me"),"unknown settings survive editing");
                check(module.driver(home).equals(ModuleSpec.TUAR.driver(home)),"one driver path for every module");
            }
            ModuleSettings approve=new ModuleSettings(home,ModuleSpec.APPROVE);approve.load();
            for(String invalid:new String[]{""," ","x&&","&&y","x&&y&&z"}){approve.search=invalid;expectFailure(() -> approve.validate(true));}
            ModuleSettings deletion=new ModuleSettings(home,ModuleSpec.DELETE);deletion.load();deletion.rows.clear();expectFailure(() -> deletion.validate(true));
            deletion.rows.add(new String[]{"REF-1",""});expectFailure(() -> deletion.save());
            Path ref=ModuleSpec.REF.directory(home).resolve("Ref_No.csv");Files.write(ref,"\uFEFFREF-2,Services,ไทย,มีจุลภาค\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            ModuleSettings bom=new ModuleSettings(home,ModuleSpec.REF);bom.load();check(bom.rows.size()==1 && "REF-2".equals(bom.rows.get(0)[0]),"BOM and blank lines load correctly");
            System.out.println("MODULE SETTINGS PASS");
        } finally {try(Stream<Path> files=Files.walk(home)){files.sorted(Comparator.reverseOrder()).forEach(path -> {try{Files.deleteIfExists(path);}catch(IOException ex){throw new RuntimeException(ex);}});}}
    }
    private interface Operation {void run() throws Exception;}
    private static void expectFailure(Operation operation) throws Exception {try{operation.run();throw new AssertionError("Expected validation failure");}catch(IOException expected){}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS: "+label);}
}
