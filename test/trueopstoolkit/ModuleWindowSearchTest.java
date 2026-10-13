package trueopstoolkit;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.swing.*;

/** Isolated GUI/settings contract: no sign-in, driver or portal action. */
public final class ModuleWindowSearchTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("trueops-four-conditions-");
        ModuleWindow[] window={null};
        try {
            Path file=ModuleSpec.APPROVE.directory(home).resolve("settings.csv");Files.createDirectories(file.getParent());
            Files.write(file,"Search,TK08&&ATTS\nDescription,Synthetic only\n".getBytes(StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(()->window[0]=new ModuleWindow(home,ModuleSpec.APPROVE));
            SwingUtilities.invokeAndWait(()->{
                try {
                    ModuleWindow frame=window[0];ModuleSettings settings=(ModuleSettings)field("settings").get(frame);
                    String[] names={"search","searchSecond","searchThird","searchFourth"};
                    check("TK08".equals(text(frame,names[0])) && "ATTS".equals(text(frame,names[1])),"Legacy && settings load into separate fields");
                    check(text(frame,names[2]).isEmpty() && text(frame,names[3]).isEmpty(),"Unused optional fields are blank");
                    for(int index=0;index<4;index++)set(frame,names[index],new String[]{"tk08","atts","pac","true"}[index]);
                    collect(frame);settings.validate(true);settings.save();check("tk08&&atts&&pac&&true".equals(settings.search),"Four fields save as four legacy-compatible AND terms");
                    check(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).contains("Search,tk08&&atts&&pac&&true"),"Settings retain portable runtime syntax");
                    for(String name:names)set(frame,name,"");set(frame,names[3]," TK09 ");collect(frame);settings.validate(true);check("TK09".equals(settings.search),"Any one filled field satisfies minimum-one rule");
                    set(frame,names[3],"");collect(frame);try{settings.validate(true);throw new AssertionError("Empty conditions accepted");}catch(IOException expected){checks++;}
                    set(frame,names[0],"TK08&&ATTS");try{collect(frame);throw new AssertionError("Joined conditions accepted in one field");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IOException,"Joined text requests separate fields");}
                    set(frame,names[0],"TK08");collect(frame);settings.validate(true);check(!containsSpinner(frame),"No manual delay control remains in the GUI");
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
        }finally {SwingUtilities.invokeAndWait(()->{if(window[0]!=null)window[0].dispose();});ToolkitUpdater.deleteTree(home);}
        System.out.println("PASS: four-condition GUI and legacy settings ("+checks+" checks); at least one filled field and automatic page readiness.");
    }
    private static boolean containsSpinner(java.awt.Component component){if(component instanceof JSpinner)return true;if(component instanceof java.awt.Container)for(java.awt.Component child:((java.awt.Container)component).getComponents())if(containsSpinner(child))return true;return false;}
    private static Field field(String name)throws Exception{Field field=ModuleWindow.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static String text(ModuleWindow frame,String name)throws Exception{return ((JTextField)field(name).get(frame)).getText();}
    private static void set(ModuleWindow frame,String name,String text)throws Exception{((JTextField)field(name).get(frame)).setText(text);}
    private static void collect(ModuleWindow frame)throws Exception{Method method=ModuleWindow.class.getDeclaredMethod("collect");method.setAccessible(true);method.invoke(frame);}
    private static void check(boolean passed,String message){if(!passed)throw new AssertionError(message);checks++;}
}
