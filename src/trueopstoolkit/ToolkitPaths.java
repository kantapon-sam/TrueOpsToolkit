package trueopstoolkit;

import java.io.File;
import java.nio.file.*;
import java.util.*;

final class ToolkitPaths {
    static Path home() throws Exception {
        String override=System.getProperty("trueops.home");
        if (override!=null) return Paths.get(override).toAbsolutePath().normalize();
        Path location=Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        if (Files.isRegularFile(location)) return location.getParent();
        // NetBeans/Ant run from build/classes; modules always live in dist.
        Path p=location;
        for (int i=0; p!=null && i<4; i++,p=p.getParent()) {
            if (Files.isDirectory(p.resolve("dist/modules"))) return p.resolve("dist");
            if (Files.isDirectory(p.resolve("modules"))) return p;
        }
        throw new IllegalStateException("The modules folder was not found. Build the project first.");
    }
    static Path launcherJar(Path home) { return home.resolve("TrueOpsToolkit.jar"); }
    static String javaExecutable() {
        Path p=Paths.get(System.getProperty("java.home"),"bin","java.exe");
        if (!Files.isRegularFile(p)) p=Paths.get(System.getProperty("java.home"),"bin","java");
        return p.toString();
    }
    static List<String> missing(Path home, ModuleSpec m) {
        List<String> out=new ArrayList<String>();
        Path dir=m.directory(home);
        if (!Files.isRegularFile(dir.resolve(m.jar))) out.add(m.jar);
        if (!Files.isDirectory(dir.resolve("lib"))) out.add("lib/");
        // Editable inputs and the shared driver can be created from the UI.
        return out;
    }
    static List<String> childCommand(Path home, ModuleSpec m, Path smokeReport) {
        List<String> c=new ArrayList<String>();
        Collections.addAll(c,javaExecutable(),"-Dfile.encoding=UTF-8", "-Dautorequesttuar.update.disabled=true", "-Dautorequesttuar.autorun=false", "-cp",
            launcherJar(home)+File.pathSeparator+m.directory(home).resolve(m.jar)+File.pathSeparator+m.directory(home).resolve("lib").toString()+File.separator+"*",
            "trueopstoolkit.ModuleBootstrap",home.toString(),m.id);
        if(smokeReport!=null) {c.add("--ui-smoke"); c.add(smokeReport.toString());}
        return c;
    }
}
