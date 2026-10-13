package trueopstoolkit;

import java.awt.Color;
import java.nio.file.Path;

public enum ModuleSpec {
    APPROVE("approve", "Approve PROMS", "Approve records that match your search criteria", "TrueOps-Approve.jar", "com.java.myapp.ApproveProms_TrueCorp_V3", new Color(29,136,108), new String[]{"settings.csv"}),
    REF("approve-ref", "Approve by Ref", "Approve records by reference number", "TrueOps-ApproveByRef.jar", "com.java.myapp.ApproveProms_Ref", new Color(114,85,187), new String[]{"settings.csv", "Ref_No.csv"}),
    DELETE("delete", "Delete Site", "Delete sites using the references in a CSV file", "TrueOps-DeleteSite.jar", "com.java.myapp.Delete_Site_TrueCorp", new Color(186,82,65), new String[]{"settings.csv", "Ref_No.csv"}),
    TK07("tk07", "TK07 Review", "Review TK07 requests and supporting documents", "TrueOps-TK07.jar", "com.java.myapp.TK07_V3_TrueCorp", new Color(35,119,162), new String[]{"settings.csv"});

    public final String id, title, description, jar, mainClass;
    public final Color color;
    public final String[] dataFiles;
    ModuleSpec(String id, String title, String description, String jar, String mainClass, Color color, String[] dataFiles) {
        this.id=id; this.title=title; this.description=description; this.jar=jar; this.mainClass=mainClass; this.color=color; this.dataFiles=dataFiles;
    }
    public Path directory(Path home) { return home.resolve("modules").resolve(id); }
    public Path driver(Path home) { return SharedChromeDriver.path(home); }
    public static ModuleSpec byId(String id) {
        for (ModuleSpec m: values()) if (m.id.equals(id)) return m;
        throw new IllegalArgumentException("Unknown module: " + id);
    }
}
