package com.java.myapp;

import approve.MockPromsPortal;
import approve.MockPromsPortal.Row;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Real browser, loopback-only reference approvals; never visits the real portal. */
public final class ApproveByRefWorkflowTest {
    private static int assertions;
    private static Path fixture;

    public static void main(String[] args) throws Exception {
        matcherChecks();
        if (args.length == 0) {
            System.out.println("PASS: " + assertions + " Approve by Ref matching checks (browser checks not requested).");
            return;
        }
        System.setProperty("webdriver.chrome.driver", Paths.get(args[0]).toAbsolutePath().toString());
        fixture = Paths.get(args.length > 1 ? args[1] : "test/approve/mock-proms.html").toAbsolutePath();
        confirmedDescriptionsAndExactReferences();
        skipIneligibleReferences();
        duplicateReferencesAndInput();
        exactReferenceAfterPrefixPages();
        readOnlyPreview();
        readOnlyEligibility();
        paginatedBoqStopsBeforeApproval();
        singlePageBoqAllowsApproval();
        unconfirmedResponses();
        System.out.println("PASS: " + assertions + " Approve by Ref workflow checks; only synthetic loopback records were used.");
    }

    private static void matcherChecks() {
        check(ReferenceMatcher.sameReference(" ref-100 ", "REF-100"), "reference ignores case and surrounding whitespace");
        check(!ReferenceMatcher.sameReference("REF100", "REF100-EXTRA"), "reference prefix cannot match");
        check(!ReferenceMatcher.sameReference("", ""), "empty reference cannot match");
        check(ReferenceMatcher.sameReference("REF\u00a0100", "ref 100"), "reference nonbreaking space normalized");
        check(ReferenceMatcher.sameType("tk09", "NT_IE_TK09(PAC) : SITE"), "category code ignores case");
        check(ReferenceMatcher.sameType(" TK09 ", "nt_ie_tk09(pac) : SITE"), "category code trims");
        check(ReferenceMatcher.sameType("NT_IE_TK09(PAC)", "nt_ie_tk09(pac) : SITE"), "full category without site suffix");
        check(ReferenceMatcher.sameType("nt_ie_tk09(pac) : site", "NT_IE_TK09(PAC) : SITE"), "full request label ignores case");
        check(!ReferenceMatcher.sameType("TK09", "NT_IE_TK090(PAC) : SITE"), "category rejects longer numeric code");
        check(!ReferenceMatcher.sameType("TK09", "NT_IE_TK09X(PAC) : SITE"), "category rejects longer alphabetic code");
        check(!ReferenceMatcher.sameType("TK09", "NT_IE_XTK09(PAC) : SITE"), "category rejects suffix code");
        check(!ReferenceMatcher.sameType("TK09", "NT_IE_TK10(PAC) : TK09"), "site cannot supply category match");
        check(!ReferenceMatcher.sameType("", "TK09"), "empty type rejects");
        check(!ReferenceMatcher.sameType("Services", "TK09"), "request category distinct from BOQ service type");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check(ReferenceMatcher.sameReference("INPUT", "input"), "reference matching independent of Turkish locale");
            check(ReferenceMatcher.sameType("IE", "NT_IE_TK09"), "type matching independent of Turkish locale");
        } finally { Locale.setDefault(original); }
    }

    private static void confirmedDescriptionsAndExactReferences() throws Exception {
        List<Row> rows = Arrays.asList(row("REF100-EXTRA", "NT_IE_TK09(PAC) : SITE"),
                row("REF100", "NT_IE_TK09(PAC) : SITE"), row("REF200", "NT_IE_TK09(PAC) : SITE"));
        try (MockPromsPortal portal = portal(rows, "ref-detail")) {
            WebDriver driver = driver();
            try {
                List<String> statuses = new ArrayList<>();
                List<String[]> requested = Arrays.asList(reference("ref100", "tk09", "First comment, with a comma"),
                        reference("Ref200", "TK09", "Second comment \u0e17\u0e14\u0e2a\u0e2d\u0e1a"));
                ApproveByRefWorkflow.Result result = ApproveByRefWorkflow.run(driver, portal.url(), requested, 8,
                        new ApproveByRefWorkflow.Reporter(statuses::add));
                equal(2, result.getScanned(), "review both CSV rows");
                equal(2, result.getMatched(), "match both reference and type");
                equal(2, result.getApproved(), "count both genuine acknowledgements");
                equal(0, result.getSkipped(), "no valid references skipped");
                equal(0, result.getFailed(), "no valid reference failed");
                Map<String, String> approvals = portal.approvals();
                equal(2, approvals.size(), "server received two exact approvals");
                equal(requested.get(0)[2], approvals.get("REF100"), "first per-row description preserved");
                equal(requested.get(1)[2], approvals.get("REF200"), "second per-row description preserved");
                check(!approvals.containsKey("REF100-EXTRA"), "prefix row never approved");
                equal(Arrays.asList("REF100", "REF200"), portal.attempts(), "correct reference order only");
                equal(Arrays.asList("REF100", "REF200"), ((JavascriptExecutor) driver)
                        .executeScript("return window.fixture.detailVisits;"), "next reference starts from pending list");
                check(statuses.stream().anyMatch(status -> status.startsWith("Checking FBOQ")), "BOQ progress visible");
                check(statuses.stream().anyMatch(status -> status.startsWith("Submitting approval")), "approval progress visible");
                check(System.getProperty("trueops.approve-ref.summary", "").contains("approved: 2"), "human summary exact count");
                check(System.getProperty("trueops.approve-ref.result", "").contains("approved=2"), "machine summary exact count");
            } finally { driver.quit(); }
        }
    }

    private static void skipIneligibleReferences() throws Exception {
        List<Row> rows = Arrays.asList(row("REF300", "NT_IE_TK090(PAC) : SITE"),
                new Row("REF400", "Synthetic project", "Synthetic requester", "NT_IE_TK09(PAC) : SITE", new String[0]),
                new Row("REF500", "Synthetic project", "Synthetic requester", "NT_IE_TK09(PAC) : SITE", new String[]{"Services", "Materials"}));
        try (MockPromsPortal portal = portal(rows, "ref-detail")) {
            WebDriver driver = driver();
            try {
                ApproveByRefWorkflow.Result result = ApproveByRefWorkflow.run(driver, portal.url(), Arrays.asList(
                        reference("REF300", "tk09", "Wrong type"), reference("MISSING", "tk09", "Missing"),
                        reference("REF400", "tk09", "Empty BOQ"), reference("REF500", "tk09", "Mixed BOQ")), 8);
                equal(4, result.getScanned(), "all ineligible inputs reviewed");
                equal(2, result.getMatched(), "BOQ skipped after exact ref/type matches");
                equal(0, result.getApproved(), "empty/mixed BOQ never approved");
                equal(4, result.getSkipped(), "wrong type, missing ref, empty BOQ, mixed BOQ all skipped");
                equal(0, portal.attempts().size(), "no server mutation for ineligible refs");
                check(result.getSummary().startsWith("No requests were approved."), "zero success explicit");
            } finally { driver.quit(); }
        }
    }

    private static void duplicateReferencesAndInput() throws Exception {
        List<Row> rows = Arrays.asList(row("DUP-REF", "NT_IE_TK09(PAC) : SITE"), row("DUP-REF", "NT_IE_TK10(PAC) : SITE"));
        try (MockPromsPortal portal = portal(rows, "ref-detail")) {
            WebDriver driver = driver();
            try {
                boolean ambiguousStopped = false;
                try {
                    ApproveByRefWorkflow.run(driver, portal.url(),
                            Collections.singletonList(reference("DUP-REF", "tk09", "Must not select arbitrarily")), 8);
                } catch (IOException expected) { ambiguousStopped = true; }
                check(ambiguousStopped, "duplicate exact refs stop without arbitrary selection");
                check(System.getProperty("trueops.approve-ref.result", "").contains("approved=0"), "duplicate ref never approved");
                equal(0, portal.attempts().size(), "duplicate reference did not submit");
                boolean rejected = false;
                try {
                    ApproveByRefWorkflow.run(driver, portal.url(), Arrays.asList(
                            reference("DUP-REF", "tk09", "First"), reference("dup-ref", "TK09", "Second")), 8);
                } catch (IOException expected) { rejected = true; }
                check(rejected, "duplicate casefold CSV identity rejected before any approval");
                equal(0, portal.attempts().size(), "ambiguous descriptions do not submit");
                check(System.getProperty("trueops.approve-ref.result", "").contains("failed=1"), "validation failure summary published");
            } finally { driver.quit(); }
        }
    }

    private static void readOnlyPreview() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-PREVIEW", "NT_IE_TK09(PAC) : SITE")), "ref-detail")) {
            WebDriver driver = driver();
            try {
                List<PromsApprovalSupport.Row> matches = ApproveByRefWorkflow.preview(driver, portal.url(), "ref-preview", "tk09", 8);
                equal(1, matches.size(), "preview uses case-insensitive ref/type");
                equal(0, portal.attempts().size(), "preview submits no approval");
                equal(0L, ((JavascriptExecutor) driver).executeScript("return window.fixture.modalOpens;"), "preview opens no approval modal");
                equal(0L, ((JavascriptExecutor) driver).executeScript("return window.fixture.detailVisits.length;"), "preview leaves detail unchanged");
            } finally { driver.quit(); }
        }
    }

    private static void exactReferenceAfterPrefixPages() throws Exception {
        List<Row> rows = Arrays.asList(row("REF700-A", "NT_IE_TK09(PAC) : SITE"),
                row("REF700-B", "NT_IE_TK09(PAC) : SITE"), row("REF700-C", "NT_IE_TK09(PAC) : SITE"),
                row("REF700", "NT_IE_TK09(PAC) : SITE"));
        try (MockPromsPortal portal = new MockPromsPortal(fixture, rows, "ref-detail", 2, true)) {
            WebDriver driver = driver();
            try {
                ApproveByRefWorkflow.Result result = ApproveByRefWorkflow.run(driver, portal.url(),
                        Collections.singletonList(reference("ref700", "tk09", "")), 8);
                equal(1, result.getScanned(), "one CSV row reviewed across filtered pages");
                equal(1, result.getApproved(), "exact reference on later filtered page approved");
                equal(Collections.singletonList("REF700"), portal.attempts(), "prefix pages never select a wrong record");
                equal("", portal.approvals().get("REF700"), "blank description clears stale comment");
            } finally { driver.quit(); }
        }
    }

    private static void readOnlyEligibility() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-ELIGIBLE", "NT_IE_TK09(PAC) : SITE"),
                new Row("REF-EMPTY", "Synthetic project", "Synthetic requester", "NT_IE_TK09(PAC) : SITE", new String[0])), "ref-detail")) {
            WebDriver driver = driver();
            try {
                ApproveByRefWorkflow.Eligibility eligible = ApproveByRefWorkflow.previewEligibility(driver, portal.url(),
                        "ref-eligible", "tk09", 8);
                equal(1, eligible.getReferenceMatches(), "read-only eligibility exact reference");
                equal(1, eligible.getTypeMatches(), "read-only eligibility exact category token");
                equal(2, eligible.getPartCount(), "read-only eligibility reports stable BOQ count");
                check(eligible.isEligible(), "nonempty all-services BOQ eligible");
                ApproveByRefWorkflow.Eligibility empty = ApproveByRefWorkflow.previewEligibility(driver, portal.url(),
                        "ref-empty", "tk09", 8);
                equal(0, empty.getPartCount(), "empty BOQ preview has zero parts");
                check(!empty.isEligible(), "empty BOQ preview refuses eligibility");
                ApproveByRefWorkflow.Eligibility mismatch = ApproveByRefWorkflow.previewEligibility(driver, portal.url(),
                        "ref-eligible", "tk090", 8);
                equal(0, mismatch.getTypeMatches(), "preview type mismatch rejected");
                check(!mismatch.isEligible(), "mismatched type preview ineligible");
                equal(0, portal.attempts().size(), "eligibility inspection makes no mutation");
                equal(0L, ((JavascriptExecutor) driver).executeScript("return window.fixture.modalOpens;"), "eligibility never opens approval modal");
            } finally { driver.quit(); }
        }
    }

    private static void unconfirmedResponses() throws Exception {
        for (String mode : Arrays.asList("ref-detail-missing-confirm", "ref-detail-server-failure", "ref-detail-false-success")) {
            try (MockPromsPortal portal = portal(Arrays.asList(row("REF-FAIL", "NT_IE_TK09(PAC) : SITE")), mode)) {
                WebDriver driver = driver();
                try {
                    boolean stopped = false;
                    try {
                        ApproveByRefWorkflow.run(driver, portal.url(), Collections.singletonList(reference("REF-FAIL", "tk09", "Failure fixture")), 5);
                    } catch (IOException expected) { stopped = true; }
                    check(stopped, mode + " stops without retry");
                    equal(0, portal.approvals().size(), mode + " confirms no real synthetic success");
                    equal(mode.contains("missing-confirm") ? 0 : 1, portal.attempts().size(), mode + " no automatic retry");
                    check(System.getProperty("trueops.approve-ref.result", "").contains("approved=0"), mode + " reports no approval");
                    check(System.getProperty("trueops.approve-ref.result", "").contains("failed=1"), mode + " reports failure");
                } finally { driver.quit(); }
            }
        }
    }

    private static void paginatedBoqStopsBeforeApproval() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-PAGED-BOQ", "NT_IE_TK09(PAC) : SITE")), "ref-detail-paged-boq")) {
            WebDriver driver = driver();
            try {
                boolean stopped = false;
                try {
                    ApproveByRefWorkflow.run(driver, portal.url(), Collections.singletonList(
                            reference("REF-PAGED-BOQ", "tk09", "Must check complete FBOQ")), 8);
                } catch (IOException expected) { stopped = true; }
                check(stopped, "paginated FBOQ stops before trusting visible Services");
                equal(0, portal.attempts().size(), "paginated BOQ sends no server approval");
                equal(0L, ((JavascriptExecutor) driver).executeScript("return window.fixture.modalOpens;"), "paginated BOQ opens no approval dialog");
                check(System.getProperty("trueops.approve-ref.result", "").contains("approved=0"), "paginated BOQ reports zero approvals");
                check(System.getProperty("trueops.approve-ref.result", "").contains("failed=1"), "paginated BOQ reports review failure");
            } finally { driver.quit(); }
        }
    }

    private static void singlePageBoqAllowsApproval() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-SINGLE-BOQ", "NT_IE_TK09(PAC) : SITE")), "ref-detail-single-page-boq")) {
            WebDriver driver = driver();
            try {
                ApproveByRefWorkflow.Result result = ApproveByRefWorkflow.run(driver, portal.url(), Collections.singletonList(
                        reference("REF-SINGLE-BOQ", "tk09", "Single complete page")), 8);
                equal(1, result.getApproved(), "single-page complete FBOQ allowed");
                equal(1, portal.attempts().size(), "single-page BOQ submits exactly once");
            } finally { driver.quit(); }
        }
    }

    private static MockPromsPortal portal(List<Row> rows, String mode) throws IOException {
        return new MockPromsPortal(fixture, rows, mode, 10, false);
    }
    private static Row row(String ref, String type) {
        return new Row(ref, "Synthetic project", "Synthetic requester", type, new String[]{"services", " Services "});
    }
    private static String[] reference(String ref, String type, String description) { return new String[]{ref, type, description}; }
    private static WebDriver driver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--remote-allow-origins=*");
        return new ChromeDriver(options);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message + ": expected " + expected + ", actual " + actual);
    }
}
