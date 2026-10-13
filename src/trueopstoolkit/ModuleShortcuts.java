package trueopstoolkit;

/** Stable module JAR shortcuts always enter the shared Toolkit environment. */
public final class ModuleShortcuts {
    private ModuleShortcuts() { }
    /** Retired shortcut retained so installations of 1.0.0 can update normally. */
    public static final class Tuar { public static void main(String[] args) throws Exception {Main.main(new String[0]);} }
    public static final class Approve { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","approve"});} }
    public static final class Ref { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","approve-ref"});} }
    public static final class Delete { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","delete"});} }
}
