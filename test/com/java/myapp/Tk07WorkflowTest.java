package com.java.myapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tk07.MockTk07Portal;
import tk07.MockTk07Portal.Row;

/** Synthetic PDF and loopback Selenium checks for the managed TK07 module. */
public final class Tk07WorkflowTest {
    private static final AtomicInteger assertions = new AtomicInteger();
    private static Path reports, fixture;

    public static void main(String[] args) throws Exception {
        reports = Files.createDirectories(Paths.get("build/test-reports/tk07").toAbsolutePath());
        if (args.length > 2 && "--confirmation-only".equals(args[2])) {
            System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
            fixture = Paths.get(args[1]).toAbsolutePath();
            normalReview();
            unconfirmedDecisions();
            System.out.println("PASS: " + assertions + " TK07 confirmation checks; only synthetic localhost was used.");
            return;
        }
        if (args.length > 2 && "--actual-activity-only".equals(args[2])) {
            System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
            fixture = Paths.get(args[1]).toAbsolutePath();
            actualActivityChecks();
            System.out.println("PASS: " + assertions + " focused TK07 selected-activity/actId checks; only synthetic localhost was used.");
            return;
        }
        if (args.length > 2 && "--late-render-only".equals(args[2])) {
            System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
            fixture = Paths.get(args[1]).toAbsolutePath();
            delayedAttachmentCase("pre-response-empty-delayed-rfs");
            System.out.println("PASS: " + assertions + " focused TK07 post-response rendering checks.");
            return;
        }
        if (args.length > 2 && "--attachments-only".equals(args[2])) {
            System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
            fixture = Paths.get(args[1]).toAbsolutePath();
            delayedAndUnknownAttachments();
            emptyAndImageOnlyEvidence();
            queuedBeforeRequestIsNotFresh();
            System.out.println("PASS: " + assertions + " focused TK07 attachment/PDF loading checks.");
            return;
        }
        ruleAndSettingsChecks();
        downloadIsolationChecks();
        sharedVaultChecksIfAvailable();
        if (args.length == 0) {
            System.out.println("PASS: " + assertions + " TK07 settings/PDF checks (browser checks not requested).");
            return;
        }
        System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
        fixture = Paths.get(args.length > 1 ? args[1] : "test/tk07/mock-tk07.html").toAbsolutePath();
        normalReview();
        delayedAndUnknownAttachments();
        emptyAndImageOnlyEvidence();
        queuedBeforeRequestIsNotFresh();
        actualActivityChecks();
        readOnlyPreview();
        unconfirmedDecisions();
        System.out.println("PASS: " + assertions + " TK07 workflow checks; only synthetic local requests/PDFs were used.");
    }

    private static void ruleAndSettingsChecks() throws Exception {
        Path directory = Files.createTempDirectory(reports, "rules-");
        Path settings = directory.resolve("settings.csv");
        write(settings, "\uFEFFUser,synthetic-legacy-account\nPassword,synthetic-legacy-secret\nSearch, tk07 && single grid \nDescription_Approve,Approve, with comma\nDescription_Reject,\n");
        Tk07Settings prefs = Tk07Settings.readPreferences(settings);
        equal("tk07 && single grid", prefs.search(), "trimmed nonempty Search");
        equal("Approve, with comma", prefs.approvalDescription(), "comment comma preserved");
        equal("", prefs.rejectionDescription(), "empty rejection comment supported");
        equal(null, prefs.username(), "plaintext account ignored");
        String previousHome = System.getProperty("trueops.home");
        try {
            System.clearProperty("trueops.home");
            expectIOException(() -> Tk07Settings.read(settings), "standalone plaintext account is never used");
        } finally {
            if (previousHome == null) System.clearProperty("trueops.home"); else System.setProperty("trueops.home", previousHome);
        }
        write(settings, "Search,tk07\n");
        prefs = Tk07Settings.readPreferences(settings);
        equal("", prefs.approvalDescription(), "missing approval comment defaults empty");
        equal("", prefs.rejectionDescription(), "missing rejection comment defaults empty");
        for (String bad : Arrays.asList("", "tk07&&", "&&grid", "tk07&&grid&&extra")) {
            write(settings, "Search," + bad + "\n");
            expectIOException(() -> Tk07Settings.readPreferences(settings), "invalid search rejects before browser");
        }
        for (String phrase : Arrays.asList("SINGLE GRID", "Consolidation", "BLACKCELL GREY", "ASG CARD EXPANSION", "GEO B2B", "SUPPORT OLT SMALL POCKET", "IP CLOCK")) {
            check(Tk07PdfReview.eligibleProject("Synthetic " + phrase + " project"), "canonical project keyword eligible");
        }
        check(!Tk07PdfReview.eligibleProject("Unrelated project"), "unrelated project excluded");
        equal("ABC001", Tk07PdfReview.siteFromRequestType("NT_IE_TK07 : DABC001"), "leading D normalization");
        equal("ABC002", Tk07PdfReview.siteFromRequestType("NT_IE_TK07 : ABC002Z"), "trailing Z normalization");
        equal("ABC003", Tk07PdfReview.siteFromRequestType("nt_ie_tk07 : abc003a"), "casefold trailing A normalization");
        equal("ABC004", Tk07PdfReview.siteFromRequestType("NT_IE_TK07 : ABC004T"), "trailing T normalization");
        equal("ABC005Z", Tk07PdfReview.siteFromRequestType("NT_IE_TK07 : DABC005Z"), "leading D retains canonical branch priority");
        expectIOException(() -> Tk07PdfReview.siteFromRequestType("NT_IE_TK07"), "missing site rejects");
        expectIOException(() -> Tk07PdfReview.siteFromRequestType("NT_IE_TK07 : D"), "empty normalized site rejects");
        Path good = directory.resolve("site.pdf");
        Files.write(good, MockTk07Portal.pdfBytes("RFS confirmation for abc001"));
        check(Tk07PdfReview.pdfContainsSite(good, "ABC001"), "PDF site matching ignores case");
        Files.write(good, MockTk07Portal.pdfBytes("RFS confirmation for ABC0012"));
        check(!Tk07PdfReview.pdfContainsSite(good, "ABC001"), "PDF site prefix is not sufficient evidence");
        Files.write(good, MockTk07Portal.pdfBytes("RFS confirmation for ABC001-OTHER and ABC001_X"));
        check(!Tk07PdfReview.pdfContainsSite(good, "ABC001"), "hyphen/underscore identifier suffix is not sufficient evidence");
        Files.write(good, MockTk07Portal.pdfBytes(""));
        expectIOException(() -> Tk07PdfReview.pdfContainsSite(good, "ABC001"), "image-only or blank PDF text is unverifiable");
        Files.write(good, "Synthetic corrupt PDF".getBytes(StandardCharsets.UTF_8));
        expectIOException(() -> Tk07PdfReview.pdfContainsSite(good, "ABC001"), "invalid PDF never produces a decision");
    }

    private static void downloadIsolationChecks() throws Exception {
        Path directory = Files.createTempDirectory(reports, "download-isolation-");
        Path old = directory.resolve("existing-user.pdf");
        byte[] preserved = MockTk07Portal.pdfBytes("Existing unrelated document");
        Files.write(old, preserved);
        Set<Path> before = Tk07Workflow.pdfFiles(directory);
        expectIOException(() -> Tk07Workflow.waitForNewPdf(directory, before, 1), "old PDF cannot satisfy current request");
        equal(preserved.length, (int) Files.size(old), "old PDF remains untouched");
        Path fresh = directory.resolve("current.pdf"), partial = directory.resolve("current.pdf.crdownload");
        Files.write(fresh, MockTk07Portal.pdfBytes("Current evidence"));
        Files.write(partial, new byte[]{1});
        Thread finish = new Thread(() -> { try { Thread.sleep(300); Files.delete(partial); } catch (Exception failure) { throw new RuntimeException(failure); } });
        finish.start();
        equal(fresh, Tk07Workflow.waitForNewPdf(directory, before, 3), "waits for current download completion");
        finish.join();
        Files.write(directory.resolve("ambiguous.pdf"), MockTk07Portal.pdfBytes("Another current PDF"));
        expectIOException(() -> Tk07Workflow.waitForNewPdf(directory, before, 2), "multiple new PDFs do not select arbitrary latest");
    }

    private static void sharedVaultChecksIfAvailable() throws Exception {
        Class<?> store;
        try { store = Class.forName("trueopstoolkit.SharedCredentials"); }
        catch (ClassNotFoundException notOnClasspath) { return; }
        Path home = Files.createTempDirectory(reports, "synthetic-vault-");
        Path settings = home.resolve("settings.csv");
        write(settings, "User,ignored-local-account\nPassword,ignored-local-secret\nSearch,tk07\n");
        String previous = System.getProperty("trueops.home");
        char[] secret = "synthetic-tk07-secret".toCharArray();
        try {
            store.getMethod("save", Path.class, String.class, char[].class).invoke(null, home, "synthetic-tk07-account", secret);
            System.setProperty("trueops.home", home.toString());
            Tk07Settings shared = Tk07Settings.read(settings);
            equal("synthetic-tk07-account", shared.username(), "TK07 reads the shared username");
            equal("synthetic-tk07-secret", shared.password(), "TK07 reads the shared password");
            String plaintext = new String(Files.readAllBytes(home.resolve("config/credentials.properties")), StandardCharsets.UTF_8);
            check(plaintext.contains("synthetic-tk07-account") && plaintext.contains("synthetic-tk07-secret"), "TK07 uses the plaintext shared account");
            store.getMethod("save", Path.class, String.class, char[].class).invoke(null, home, "updated-shared-account", "updated-shared-secret".toCharArray());
            equal("updated-shared-account", Tk07Settings.read(settings).username(), "next TK07 run reloads the latest shared account");
        } finally {
            Arrays.fill(secret, '\0');
            if (previous == null) System.clearProperty("trueops.home"); else System.setProperty("trueops.home", previous);
            store.getMethod("delete", Path.class).invoke(null, home);
        }
    }

    private static void normalReview() throws Exception {
        List<Row> rows = Arrays.asList(row("TK-A", "Single Grid project", "DABC001", "RFS accepts ABC001", 1, true),
                row("TK-B", "Consolidation project", "ABC002Z", "RFS accepts OTHER999", 1, true),
                row("TK-C", "IP Clock rollout", "ABC003T", "Unused", -1, true),
                row("TK-D", "Blackcell Grey project", "ABC004A", "RFS accepts ABC004", 2, true),
                row("TK-E", "GEO B2B project", "ABC005T", "Unreadable", 1, false),
                row("TK-F", "Unrelated project", "ABC006Z", "RFS accepts ABC006", 1, true),
                new Row("TK-G", "Single Grid TK07 project", "NT_IE_TK070 : ABC007Z", "RFS accepts ABC007", 1, true));
        try (MockTk07Portal portal = new MockTk07Portal(fixture, rows, "normal")) {
            Path downloads = Files.createTempDirectory(reports, "browser-downloads-");
            Path original = downloads.resolve("existing-user.pdf");
            byte[] originalBytes = MockTk07Portal.pdfBytes("An unrelated pre-existing document");
            Files.write(original, originalBytes);
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Approve, exact evidence", "Reject, missing evidence", downloads, 8);
                equal(7, result.getScanned(), "pending rows captured before decisions");
                equal(5, result.getMatched(), "TK07 category and project allowlist gate");
                equal(1, result.getApproved(), "matching current RFS site approved");
                equal(2, result.getRejected(), "mismatching PDF and missing RFS rejected");
                equal(2, result.getSkipped(), "multiple RFS and unreadable PDF skipped");
                equal(0, result.getFailed(), "normal run has no unconfirmed outcome");
                equal("approve", portal.decisions().get("TK-A"), "matching site operation");
                equal("reject", portal.decisions().get("TK-B"), "mismatching site operation");
                equal("reject", portal.decisions().get("TK-C"), "missing RFS operation");
                equal("Approve, exact evidence", portal.comments().get("TK-A"), "approval comment retained");
                equal("Reject, missing evidence", portal.comments().get("TK-B"), "rejection comment retained");
                equal(Arrays.asList("TK-A", "TK-B", "TK-C"), portal.attempts(), "only eligible exact references submitted");
                equal(3, portal.downloads(), "only one attributable RFS per review downloaded");
                check(Arrays.equals(originalBytes, Files.readAllBytes(original)), "existing PDF preserved byte-for-byte");
                check(System.getProperty("trueops.tk07.summary", "").contains("rejected: 2"), "UI summary includes rejection count");
                check(System.getProperty("trueops.tk07.result", "").contains("approved=1 rejected=2"), "machine summary includes both outcomes");
            } finally { driver.quit(); }
        }
    }

    private static void readOnlyPreview() throws Exception {
        try (MockTk07Portal portal = new MockTk07Portal(fixture,
                Collections.singletonList(row("PREVIEW", "Single Grid project", "ABC001Z", "ABC001", 1, true)), "normal")) {
            Path downloads = Files.createTempDirectory(reports, "preview-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.preview(driver, portal.url(), "tk07", 8);
                equal(1, result.getMatched(), "read-only preview finds eligible TK07 row");
                equal(0, portal.attempts().size(), "read-only preview submits no operation");
                equal(0, portal.downloads(), "read-only list preview downloads no PDF");
            } finally { driver.quit(); }
        }
    }

    private static void delayedAndUnknownAttachments() throws Exception {
        for (String mode : Arrays.asList("slow-attachments", "slow-attachments-stale-empty", "pre-response-empty-delayed-rfs", "unknown-attachments")) {
            delayedAttachmentCase(mode);
        }
    }

    private static void delayedAttachmentCase(String mode) throws Exception {
        try (MockTk07Portal portal = new MockTk07Portal(fixture,
                    Collections.singletonList(row("CURRENT-REF", "Single Grid project", "ABC001Z", "ABC001", 1, true)), mode)) {
                Path downloads = Files.createTempDirectory(reports, "attachment-barrier-");
                WebDriver driver = driver(downloads);
                try {
                    Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Verified fresh RFS", "Must not trust stale list", downloads, 8);
                    equal(0, result.getRejected(), mode + " stale No-RFS/empty list never causes rejection");
                    equal(mode.equals("unknown-attachments") ? 0 : 1, result.getApproved(), mode + " approves only fresh attributable RFS evidence");
                    equal(mode.equals("unknown-attachments") ? 1 : 0, result.getSkipped(), mode + " unknown loading skips");
                    equal(mode.equals("unknown-attachments") ? 0 : 1, portal.attempts().size(), mode + " no premature operation");
                } finally { driver.quit(); }
        }
    }

    private static void emptyAndImageOnlyEvidence() throws Exception {
        try (MockTk07Portal portal = new MockTk07Portal(fixture, Arrays.asList(
                row("EMPTY-LIST", "Single Grid project", "ABC001Z", "Unused", 0, true),
                row("IMAGE-ONLY", "Single Grid project", "ABC002Z", "", 1, true)), "normal")) {
            Path downloads = Files.createTempDirectory(reports, "empty-evidence-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "", "Confirmed missing RFS", downloads, 8);
                equal(1, result.getRejected(), "fresh completed empty attachment list retains canonical rejection");
                equal(1, result.getSkipped(), "image-only PDF is unverifiable rather than rejection evidence");
                equal(Collections.singletonList("EMPTY-LIST"), portal.attempts(), "image-only PDF submits no decision");
            } finally { driver.quit(); }
        }
    }

    private static void queuedBeforeRequestIsNotFresh() throws Exception {
        try (MockTk07Portal portal = new MockTk07Portal(fixture,
                Collections.singletonList(row("QUEUED-OLD", "Single Grid project", "ABC001Z", "ABC001", 1, true)), "queued-stale-no-render")) {
            Path downloads = Files.createTempDirectory(reports, "queued-old-state-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Fresh evidence", "Never trust queued old markup", downloads, 5);
                equal(0, result.getRejected(), "pre-request queued mutation does not prove current No-RFS evidence");
                equal(0, result.getApproved(), "response without a fresh rendered list makes no approval");
                equal(1, result.getSkipped(), "unrendered current attachment response skips");
                equal(0, portal.attempts().size(), "queued old state produces no decision");
            } finally { driver.quit(); }
        }
    }

    private static void unconfirmedDecisions() throws Exception {
        for (String mode : Arrays.asList("missing-confirm", "server-failure", "false-success")) {
            try (MockTk07Portal portal = new MockTk07Portal(fixture,
                    Collections.singletonList(row("UNCONFIRMED", "Single Grid project", "ABC001Z", "ABC001", 1, true)), mode)) {
                Path downloads = Files.createTempDirectory(reports, "failure-");
                WebDriver driver = driver(downloads);
                try {
                    boolean stopped = false;
                    try { Tk07Workflow.run(driver, portal.url(), "tk07", "", "", downloads, 5); }
                    catch (IOException expected) { stopped = true; }
                    check(stopped, mode + " stops without resubmission");
                    equal(mode.equals("missing-confirm") ? 0 : 1, portal.attempts().size(), mode + " at most one submission");
                    equal(0, portal.decisions().size(), mode + " has no verified server decision");
                    check(System.getProperty("trueops.tk07.result", "").contains("approved=0 rejected=0"), mode + " counts no confirmed operation");
                    check(System.getProperty("trueops.tk07.result", "").contains("failed=1"), mode + " reports failure");
                } finally { driver.quit(); }
            }
        }
    }

    private static void actualActivityChecks() throws Exception {
        actualActivityValidEvidence();
        for (String mode : Arrays.asList("actual-activity-slow-attachments",
                "actual-activity-pre-response-empty-delayed-rfs", "actual-activity-xhr-pre-response-empty-delayed-rfs",
                "actual-activity-selected-clone")) {
            actualActivityDelayedEvidence(mode);
        }
        // Each negative scenario owns a separate loopback server, browser and download folder.
        // Two concurrent browsers keep the large context matrix practical without shared fixtures.
        ExecutorService checks = Executors.newFixedThreadPool(2);
        List<Future<?>> pending = new ArrayList<>();
        try {
            for (String suffix : Arrays.asList("wrong-route-act-id", "wrong-owner-wo", "wrong-data-wo",
                    "wrong-current-wo", "wrong-request-context", "wrong-selected-act-id", "wrong-code", "wrong-description",
                    "wrong-step-after-selection", "wrong-label", "wrong-activity-object",
                    "irrelevant-post", "query-route", "msg-code", "http-error", "invalid-body",
                    "stale-array", "stale-empty-stale-array", "dom-name-mismatch", "array-payload-mismatch",
                    "partial-response-list", "empty-subset-response", "no-render", "duplicate-id", "empty-file-name")) {
                pending.add(checks.submit(() -> { actualActivityUnverifiable("actual-activity-" + suffix); return null; }));
            }
            for (Future<?> check : pending) check.get();
        } finally { checks.shutdownNow(); }
    }

    private static void actualActivityValidEvidence() throws Exception {
        List<Row> rows = Arrays.asList(row("ACT-RFS", "Single Grid project", "ABC001Z", "ABC001", 1, true),
                row("ACT-DESIGN", "IP Clock project", "ABC002Z", "Unused", -1, true),
                row("ACT-EMPTY", "Consolidation project", "ABC003Z", "Unused", 0, true),
                row("ACT-MULTI", "Blackcell Grey project", "ABC004Z", "ABC004", 2, true),
                new Row("ACT-NONPDF", "GEO B2B project", "NT_IE_TK07 : ABC005Z", "ABC005", 1, true, new String[]{"RFS synthetic.xlsx"}),
                row("ACT-IMAGE", "ASG Card Expansion project", "ABC006Z", "", 1, true),
                row("ACT-INVALID", "Support OLT Small Pocket project", "ABC007Z", "Invalid", 1, false));
        try (MockTk07Portal portal = new MockTk07Portal(fixture, rows, "actual-activity")) {
            Path downloads = Files.createTempDirectory(reports, "actual-activity-valid-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Actual-shaped approval", "Actual-shaped missing RFS", downloads, 8);
                equal(7, result.getMatched(), "actual-shaped activity retains canonical project eligibility");
                equal(1, result.getApproved(), "fresh actId response with unique RFS PDF approves synthetic site");
                equal(2, result.getRejected(), "confirmed complete Design-only and empty actId arrays reject synthetic requests");
                equal(4, result.getSkipped(), "multiple RFS, non-PDF, image-only and unreadable evidence skip");
                equal(0, result.getFailed(), "valid actual-shaped response has no unconfirmed operations");
                equal(Arrays.asList("ACT-RFS", "ACT-DESIGN", "ACT-EMPTY"), portal.attempts(), "only fully verified synthetic evidence produces a decision");
                equal("Actual-shaped approval", portal.comments().get("ACT-RFS"), "actual-shaped approval comment preserved");
                equal("Actual-shaped missing RFS", portal.comments().get("ACT-EMPTY"), "actual-shaped rejection comment preserved");
                equal(3, portal.downloads(), "ambiguous and non-PDF actId evidence is never downloaded");
                equal(7, portal.activityAttachmentLoads(), "all activity attachment responses use the actId-only endpoint");
                equal(0, portal.legacyAttachmentLoads(), "actual-shaped test never calls legacy ref attachment endpoint");
            } finally { driver.quit(); }
        }
    }

    private static void actualActivityDelayedEvidence(String mode) throws Exception {
        try (MockTk07Portal portal = new MockTk07Portal(fixture,
                Collections.singletonList(row("ACT-DELAY", "Single Grid project", "ABC001Z", "ABC001", 1, true)), mode)) {
            Path downloads = Files.createTempDirectory(reports, "actual-activity-delayed-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Fresh actual-shaped RFS", "Never trust placeholder", downloads, 8);
                equal(1, result.getApproved(), mode + " waits for the selected actId response and its rendered RFS");
                equal(0, result.getRejected(), mode + " pre-response empty placeholder is not missing-RFS evidence");
                equal(0, result.getSkipped(), mode + " attributable delayed RFS remains usable");
                equal(1, portal.attempts().size(), mode + " submits exactly one verified synthetic decision");
                equal(1, portal.activityAttachmentLoads(), mode + " uses the selected actId-only API");
                equal(0, portal.legacyAttachmentLoads(), mode + " uses no ref query fallback");
            } finally { driver.quit(); }
        }
    }

    private static void actualActivityUnverifiable(String mode) throws Exception {
        String[] names = mode.contains("partial-response-list") || mode.contains("duplicate-id")
                ? new String[]{"Design.pdf", "RFS synthetic.pdf"} : null;
        int attachmentCount = mode.contains("stale-empty-stale-array") ? 0 : 1;
        Row candidate = new Row("ACT-UNKNOWN", "Single Grid project", "NT_IE_TK07 : ABC001Z", "ABC001", attachmentCount, true, names);
        try (MockTk07Portal portal = new MockTk07Portal(fixture, Collections.singletonList(candidate), mode)) {
            Path downloads = Files.createTempDirectory(reports, "actual-activity-unverifiable-");
            WebDriver driver = driver(downloads);
            try {
                Tk07Workflow.Result result = Tk07Workflow.run(driver, portal.url(), "tk07", "Never use cached RFS", "Never reject unknown list", downloads, 5);
                equal(0, result.getApproved(), mode + " unverifiable activity makes no approval");
                equal(0, result.getRejected(), mode + " unverifiable activity makes no rejection");
                equal(1, result.getSkipped(), mode + " conservatively skips unverified evidence");
                equal(0, result.getFailed(), mode + " uncertainty is a skipped review");
                equal(0, portal.attempts().size(), mode + " opens no confirmed business decision");
                equal(0, portal.downloads(), mode + " downloads no unverified RFS PDF");
                equal(1, portal.activityAttachmentLoads(), mode + " fixture completed one actual-shaped HTTP request");
                equal(0, portal.legacyAttachmentLoads(), mode + " cannot fall back to ref-scoped legacy API");
                equal("original-selectActivity-return", ((JavascriptExecutor) driver).executeScript("return window.fixture.activityReturn;"), mode + " wrapper preserves original activity callback return");
            } finally { driver.quit(); }
        }
    }

    private static Row row(String reference, String project, String site, String text, int attachments, boolean validPdf) {
        return new Row(reference, project, "NT_IE_TK07 : " + site, text, attachments, validPdf);
    }
    private static WebDriver driver(Path directory) {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("download.default_directory", directory.toAbsolutePath().toString());
        preferences.put("download.prompt_for_download", false);
        preferences.put("plugins.always_open_pdf_externally", true);
        ChromeOptions options = new ChromeOptions(); options.setExperimentalOption("prefs", preferences);
        options.addArguments("--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--remote-allow-origins=*");
        return new ChromeDriver(options);
    }
    private static void write(Path file, String text) throws IOException { Files.write(file, text.getBytes(StandardCharsets.UTF_8)); }
    private interface Checked { void run() throws Exception; }
    private static void expectIOException(Checked action, String message) throws Exception {
        boolean failed = false; try { action.run(); } catch (IOException expected) { failed = true; } check(failed, message);
    }
    private static void check(boolean condition, String message) { assertions.incrementAndGet(); if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { check(expected == null ? actual == null : expected.equals(actual), message + ": expected " + expected + ", actual " + actual); }
}
