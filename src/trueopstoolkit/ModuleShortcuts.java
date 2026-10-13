package trueopstoolkit;

/** Stable module JAR shortcuts always enter the shared Toolkit environment. */
public final class ModuleShortcuts {
    private ModuleShortcuts() { }
    public static final class Approve { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","approve"});} }
    public static final class Ref { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","approve-ref"});} }
    public static final class Tk07 { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","tk07"});} }
    public static final class Delete { public static void main(String[] args) throws Exception {Main.main(new String[]{"--module","delete"});} }
}
