package trueopstoolkit;

import java.awt.Color;
import java.nio.file.Path;

public enum ModuleSpec {
    TUAR("tuar", "Request TUAR", "Create TUAR requests from an Excel workbook", "TrueOps-TUAR.jar", "autorequesttuar.AutoRequestTUAR", new Color(32,122,170), new String[]{"requests.xlsx"}),
    APPROVE("approve", "Approve PROMS", "Approve records that match your search criteria", "TrueOps-Approve.jar", "com.java.myapp.ApproveProms_TrueCorp_V3", new Color(29,136,108), new String[]{"Login.txt"}),
    REF("approve-ref", "Approve by Ref", "Approve records by reference number", "TrueOps-ApproveByRef.jar", "com.java.myapp.ApproveProms_Ref", new Color(114,85,187), new String[]{"Login.txt", "Ref_No.csv"}),
    DELETE("delete", "Delete Site", "Delete sites using the references in a CSV file", "TrueOps-DeleteSite.jar", "com.java.myapp.Delete_Site_TrueCorp", new Color(186,82,65), new String[]{"Login.txt", "Ref_No.csv"});

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
