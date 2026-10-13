package com.java.myapp;

import approve.MockPromsPortal;
import approve.MockPromsPortal.Row;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Real Selenium tests confined to a loopback portal with synthetic approval requests. */
public final class ApproveWorkflowTest {
    private static int checks;
    private static Path fixture;
    private static ChromeDriver driver;
    private static final String DESCRIPTION = "Local approval fixture, synthetic only";
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected ChromeDriver path and report path.");
        fixture = args.length > 2 ? Paths.get(args[2]) : Paths.get("test", "approve", "mock-proms.html");
        System.setProperty("webdriver.chrome.driver", args[0]);
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1200,900", "--no-first-run",
                "--no-default-browser-check", "--disable-background-networking",
                "--disable-component-update", "--disable-sync", "--disable-extensions",
                "--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost");
        report.append("Test: real Selenium client and Chrome against loopback-only synthetic portal\n");
        try {
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(20, TimeUnit.SECONDS);
            if (args.length > 3 && "--confirmation-only".equals(args[3])) {
                noPagerAndExactReference();
                responseIdentityAndLifecycle();
                ambiguousFormsFailBeforeConfirmation();
                ambiguousAcknowledgementFailsWithoutRetry();
                missingConfirmationFails();
                serverFailureIsNotSuccess();
                successWithoutPendingRemovalFails();
                report.append("Checks: ").append(checks).append("\nResult: PASS\n");
                return;
            }
            readOnlyPreview();
            obsoleteDelayDoesNotControlReadiness();
            slowFilterReadsFreshRows();
            slowPendingAbsenceCheckWaitsForFreshRows();
            unchangedRowsStillHaveConfirmedFilterResponse();
            knownAngularReloadCompletesUnchangedAndEmptyRows();
            categoryCodeMatchesTypeOnly();
            fourCriteriaRequireEveryMatch();
            delayedPagingAndMatching();
            noPagerAndExactReference();
            responseIdentityAndLifecycle();
            exactReferenceOnLaterFilteredPage();
            laterPagePendingReferenceIsNotSuccess();
            zeroPendingAndNoMatches();
            duplicatePageFailsBeforeApproving();
            duplicateExactReferencesFailBeforeApproving();
            ambiguousFormsFailBeforeConfirmation();
            ambiguousAcknowledgementFailsWithoutRetry();
            missingConfirmationFails();
            serverFailureIsNotSuccess();
            successWithoutPendingRemovalFails();
            report.append("Checks: ").append(checks).append("\nResult: PASS\n");
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            report.append("Result: FAIL\n").append(trace.toString());
            throw failure;
        } finally {
            if (driver != null) driver.quit();
            Path destination = Paths.get(args[1]);
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.write(destination, report.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(report.toString());
    }

    private static void readOnlyPreview() throws Exception {
        List<Row> rows = Arrays.asList(row("REF-PREVIEW-1", "TK09", "ATTS"),
                row("REF-PREVIEW-2", "TK09", "Unrelated"), row("REF-PREVIEW-3", "TK09", "Other"));
        try (MockPromsPortal portal = portal(rows, "normal", 10, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.preview(driver, "tk09 && atts", 5);
            equal(3, result.getScanned(), "Read-only preview scanned filtered rows");
            equal(1, result.getMatched(), "Read-only preview matches both terms");
            equal(Arrays.asList("REF-PREVIEW-1"), result.getMatchedReferences(), "Read-only preview reports exact references");
            equal(0, result.getApproved(), "Read-only preview never counts approvals");
            equal(0, portal.attempts().size(), "Read-only preview never sends approval requests");
            equal(0L, number("return window.fixture.modalOpens"), "Read-only preview never opens approval modal");
            equal(0L, number("return document.querySelectorAll('#rows input:checked').length"), "Read-only preview never selects checkboxes");
            report.append("PASS: read-only preview lists matching synthetic references without selecting or approving\n");
        }
    }

    private static void delayedPagingAndMatching() throws Exception {
        List<Row> rows = new ArrayList<>();
        Set<String> expected = new LinkedHashSet<>();
        for (int page = 1; page <= 7; page++) {
            rows.add(row("REF-" + page + "-OTHER", "TK09 OTHER", "Unrelated"));
            String ref = "REF-" + page + "-MATCH";
            rows.add(row(ref, "TK09 project " + page, page % 2 == 0 ? "aTtS synthetic" : "ATTS synthetic"));
            expected.add(ref);
        }
        try (MockPromsPortal portal = portal(rows, "normal", 2, true)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(),
                    "  tk09 && atts  ", DESCRIPTION, 8);
            equal(14, result.getScanned(), "Every delayed page scanned exactly once");
            equal(7, result.getMatched(), "Case-insensitive AND matches");
            equal(7, result.getApproved(), "All matching approvals confirmed");
            equal(0, result.getFailed(), "Successful approvals have no failures");
            equal(expected, portal.approvals().keySet(), "Exact matches include the final page");
            equal(new ArrayList<>(expected), portal.attempts(), "No nonmatching or duplicate approval attempts");
            for (String description : portal.approvals().values()) equal(DESCRIPTION, description,
                    "Comment replaced stale modal text");
            equal(7L, number("return window.fixture.modalOpens"), "Approval modal opened for each exact request");
            equal(7L, number("return window.fixture.confirmationClicks"), "Confirmation clicked for each request");
            equal(7L, number("return window.fixture.successClicks"), "Success callback acknowledged for each request");
            equal(0L, number("return window.fixture.wrongSelections.length"), "No ambiguous checkbox selection");
            report.append("PASS: uppercase rows/lowercase AND search, delayed seven-page traversal, narrow pager, final page, modal comment and success\n");
        }
    }

    private static void obsoleteDelayDoesNotControlReadiness() throws Exception {
        String previous = System.getProperty("trueops.search.delay.ms");
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-DELAY", "TK09", "ATTS")), "normal", 10, false)) {
            for (String value : new String[] {"10000", "not-an-integer"}) {
                System.setProperty("trueops.search.delay.ms", value);
                long started = System.nanoTime();
                equal(1, new PromsApprovalSupport(driver, 8).collect("tk09", message -> { }).size(), "Old delay does not block automatic readiness");
                equal(true, (System.nanoTime() - started) / 1000000L < 8000, "Ready page does not wait for obsolete ten-second delay");
            }
            equal(0, portal.attempts().size(), "Readiness checks never submit approvals");
            report.append("PASS: legacy delay is ignored; page/result completion determines readiness\n");
        } finally {
            if (previous == null) System.clearProperty("trueops.search.delay.ms");
            else System.setProperty("trueops.search.delay.ms", previous);
        }
    }

    private static void fourCriteriaRequireEveryMatch() throws Exception {
        List<Row> rows = Arrays.asList(row("REF-FOUR-MATCH", "ALPHA project", "ATTS BETA synthetic"),
                row("REF-FOUR-MISSING", "ALPHA project", "ATTS OTHER synthetic"),
                new Row("REF-FOUR-WRONGTYPE", "TK09 ALPHA project", "ATTS BETA synthetic", "NT_IE_TK10(PAC) : SITE"));
        try (MockPromsPortal portal = portal(rows, "normal", 10, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(),
                    " tk09 && alpha && atts && beta ", DESCRIPTION, 8);
            equal(3, result.getScanned(), "Coarse first-term search scans every candidate");
            equal(1, result.getMatched(), "All four criteria required case-insensitively");
            equal(1, result.getApproved(), "Only all-four match confirmed");
            equal(Arrays.asList("REF-FOUR-MATCH"), portal.attempts(), "Missing fourth criterion and wrong category never approved");
            report.append("PASS: four criteria require category plus all project/requester terms, including the fourth condition\n");
        }
        try (MockPromsPortal portal = portal(rows, "normal", 10, false)) {
            for (String search : new String[] {"", "tk09&&", "tk09&&alpha&&atts&&beta&&fifth"}) {
                try {
                    ApprovePromsWorkflow.run(driver, portal.url(), search, DESCRIPTION, 8);
                    throw new AssertionError("At least one and at most four nonempty criteria required.");
                } catch (IOException expected) { checks++; }
            }
            equal(0, portal.attempts().size(), "Invalid criterion counts or empty terms never approve");
        }
    }

    private static List<Row> slowRows() {
        return Arrays.asList(new Row("UNRELATED-1", "OTHER", "Synthetic", "NT_IE_TK10(PAC) : OTHER-SITE"),
                row("REF-100", "TK09 TARGET", "ATTS"));
    }

    private static void slowFilterReadsFreshRows() throws Exception {
        try (MockPromsPortal portal = portal(slowRows(), "slow-search", 1, true)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(), "tk09", DESCRIPTION, 8);
            equal(1, result.getScanned(), "2300 ms filter returns fresh rows instead of stale first page");
            equal(1, result.getMatched(), "Slow filter discovers target formerly on page2");
            equal(1, result.getApproved(), "Slow initial filter actually approves the exact match");
            equal(Arrays.asList("REF-100"), portal.attempts(), "Slow search makes no unrelated request attempt");
            report.append("PASS: initial 2300 ms filter without spinner waits for fresh rows and finds the matching request\n");
        }
    }

    private static void slowPendingAbsenceCheckWaitsForFreshRows() throws Exception {
        try (MockPromsPortal portal = portal(slowRows(), "slow-search", 1, true)) {
            try {
                new PromsApprovalSupport(driver, 8).verifyNoLongerPending(portal.url(), "REF-100");
                throw new AssertionError("Slow filter's stale unrelated rows must not imply the exact pending request disappeared.");
            } catch (IOException expected) {
                contains(expected.getMessage(), "still lists", "Slow absence check reads actual pending target");
            }
            equal(0, portal.approvals().size(), "Slow absence probe is read-only");
            equal(0, portal.attempts().size(), "Slow absence probe submits no operation");
            report.append("PASS: pending-removal check cannot accept old rows before a 2300 ms filter response\n");
        }
    }

    private static void unchangedRowsStillHaveConfirmedFilterResponse() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")),
                "slow-search-reused-rows", 10, false)) {
            WebElement before = new WebDriverWait(driver, 5).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#rows tr")));
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.preview(driver, "tk09", 8);
            equal(1, result.getScanned(), "Fresh no-op filter scans unchanged row");
            equal(1, result.getMatched(), "Fresh no-op filter retains matching request");
            equal(before, driver.findElement(By.cssSelector("#rows tr")), "No-op filter deliberately reuses its DOM row");
            equal(1L, number("return window.fixture.filterResponses"), "2300 ms no-op network response completed");
            equal(1L, number("return window.fixture.noiseResponses"), "Earlier unrelated background response cannot satisfy freshness");
            equal(0, portal.attempts().size(), "No-op read-only preview never approves");
            report.append("PASS: fresh network response permits unchanged matching rows with reused DOM nodes\n");
        }
    }

    private static void categoryCodeMatchesTypeOnly() throws Exception {
        List<Row> rows = Arrays.asList(new Row("REF-CATEGORY", "Generic project", "Synthetic", "NT_IE_TK09(PAC) : SITE-001"),
                new Row("REF-PROJECT-ONLY", "TK09 project", "Synthetic", "NT_IE_TK10(PAC) : SITE-001"),
                new Row("REF-NUMERIC-PREFIX", "Other", "Synthetic", "NT_IE_TK090(PAC) : SITE-001"),
                new Row("REF-TEXT-PREFIX", "Other", "Synthetic", "NT_IE_TK09X(PAC) : SITE-001"),
                new Row("REF-SITE-ONLY", "Other", "Synthetic", "NT_IE_TK10(PAC) : TK09-SITE"));
        try (MockPromsPortal portal = portal(rows, "normal", 10, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(), "tK09", DESCRIPTION, 8);
            equal(5, result.getScanned(), "Coarse portal search includes all category-like records");
            equal(1, result.getMatched(), "TK category term matches a whole Request Type token only");
            equal(1, result.getApproved(), "Only actual TK09 category is approved");
            equal(Arrays.asList("REF-CATEGORY"), portal.attempts(), "Project/code-prefix/site-suffix decoys never submitted");
            equal(new LinkedHashSet<>(Arrays.asList("REF-CATEGORY")), portal.approvals().keySet(), "Category-only result is exact and case-insensitive");
            report.append("PASS: TK category search excludes project-only matches, TK090/TK09X and site-suffix codes\n");
        }
    }

    private static void knownAngularReloadCompletesUnchangedAndEmptyRows() throws Exception {
        for (boolean empty : new boolean[] {false, true}) {
            List<Row> rows = empty ? new ArrayList<Row>() : Arrays.asList(row("REF-1", "TK09", "ATTS"));
            try (MockPromsPortal portal = portal(rows, "angular-reused-rows", 10, false)) {
                new WebDriverWait(driver, 5).until(browser -> Boolean.TRUE.equals(((JavascriptExecutor) browser)
                        .executeScript("return !!(window.angular && window.fixtureScope)")));
                WebElement before = driver.findElement(By.cssSelector("#rows tr"));
                ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.preview(driver, "tk09", 8);
                equal(empty ? 0 : 1, result.getScanned(), "Known Angular reload returns fresh unchanged/empty data");
                equal(empty ? 0 : 1, result.getMatched(), "Known Angular reload keeps legitimate match count");
                equal(before, driver.findElement(By.cssSelector("#rows tr")), "Angular reload deliberately reuses identical DOM rows");
                equal(1L, number("return window.fixture.angularReloads"), "Query-bound Angular reload promise completed");
                equal(0L, number("return window.fixture.filterResponses"), "Client-side reload makes no HTTP filter request");
                equal(0, portal.attempts().size(), "Client-side read-only preview makes no approval attempt");
            }
        }
        report.append("PASS: query-bound Angular searchTablePA/reload promise permits unchanged matching and empty rows without HTTP or DOM replacement\n");
    }

    private static void noPagerAndExactReference() throws Exception {
        List<Row> rows = Arrays.asList(row("REF-100-EXTRA", "OTHER", "Synthetic"),
                new Row("REF-100", "TK09 TARGET, synthetic", "ATTS", "NT_IE_TK10(PAC) : SITE"), row("REF-101", "OTHER", "Synthetic"));
        try (MockPromsPortal portal = portal(rows, "normal", 20, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(),
                    "target", DESCRIPTION, 8);
            equal(1, result.getScanned(), "Single filtered page requires no pager or optional search error element");
            equal(1, result.getMatched(), "Single page matched request");
            equal(1, result.getApproved(), "Exact reference approved");
            equal(new LinkedHashSet<>(Arrays.asList("REF-100")), portal.approvals().keySet(),
                    "Partial reference before target never selected");
            equal(Arrays.asList("REF-100"), portal.attempts(), "Misleading first checkbox never clicked");
            report.append("PASS: no pager, unrelated column list, partial reference ahead of target, row-bound exact checkbox\n");
        }
    }

    private static void zeroPendingAndNoMatches() throws Exception {
        try (MockPromsPortal portal = portal(new ArrayList<Row>(), "normal", 10, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(), "tk09", DESCRIPTION, 5);
            equal(0, result.getScanned(), "Empty pending list scanned count");
            equal(0, result.getMatched(), "Empty pending list matched count");
            equal(0, result.getApproved(), "Empty pending list cannot report approvals");
            contains(result.getSummary().toLowerCase(), "no", "Empty pending result is explicit");
            equal(0, portal.attempts().size(), "No synthetic request sent when empty");
        }
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09 OTHER", "Synthetic")), "normal", 10, false)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(), "tk09&&missing", DESCRIPTION, 5);
            equal(1, result.getScanned(), "Portal's first-term match is still scanned");
            equal(0, result.getMatched(), "No false case-insensitive match");
            equal(0, result.getApproved(), "No-match result does not imply approval");
            contains(result.getSummary().toLowerCase(), "no", "No-match result is explicit");
            equal(0, portal.attempts().size(), "Nonmatched request never submitted");
        }
        report.append("PASS: empty pending list and zero matching rows have clear counts and no approval attempts\n");
    }

    private static List<Row> prefixPages() {
        return Arrays.asList(new Row("REF-100-0-EXTRA", "OTHER", "Synthetic", "NT_IE_TK10(PAC) : OTHER-SITE"),
                row("REF-100", "TK09 TARGET", "ATTS"),
                new Row("REF-100-Z-EXTRA", "OTHER", "Synthetic", "NT_IE_TK10(PAC) : OTHER-SITE"));
    }

    private static void exactReferenceOnLaterFilteredPage() throws Exception {
        try (MockPromsPortal portal = portal(prefixPages(), "normal", 1, true)) {
            ApprovePromsWorkflow.Result result = ApprovePromsWorkflow.run(driver, portal.url(),
                    "target", DESCRIPTION, 8);
            equal(1, result.getScanned(), "Initial Search captures the matching request only");
            equal(1, result.getMatched(), "Target on second reference-search page matched");
            equal(1, result.getApproved(), "Exact middle-page request approved");
            equal(Arrays.asList("REF-100"), portal.attempts(), "No prefix-decoy approval attempts");
            equal(new LinkedHashSet<>(Arrays.asList("REF-100")), portal.approvals().keySet(),
                    "Exact second-page target is approved after all filtered reference pages checked");
            equal(0L, number("return window.fixture.wrongSelections.length"), "Paging leaves the exact row available for selection");
            report.append("PASS: exact reference on page 2 of 3 is found behind and ahead of prefix decoys\n");
        }
    }

    private static void laterPagePendingReferenceIsNotSuccess() throws Exception {
        try (MockPromsPortal portal = portal(prefixPages(), "false-success", 1, true)) {
            expectFailure(portal, 5, "Still-pending exact reference on later page must not count as approved");
            equal(Arrays.asList("REF-100"), portal.attempts(), "Later-page false-success operation attempted once");
            equal(0, portal.approvals().size(), "Later-page still-pending request not approved");
            contains(System.getProperty("trueops.approve.result", ""), "approved=0", "Later-page false-success published approved count");
            contains(System.getProperty("trueops.approve.result", ""), "failed=1", "Later-page absence-check failure reaches UI result");
            report.append("PASS: pending-removal verification scans every filtered page and rejects later-page false success\n");
        }
    }

    private static void duplicateExactReferencesFailBeforeApproving() throws Exception {
        List<Row> rows = Arrays.asList(row("REF-100", "TK09 TARGET", "ATTS"),
                new Row("ref-100", "OTHER", "Synthetic", "NT_IE_TK10(PAC) : OTHER-SITE"));
        try (MockPromsPortal portal = portal(rows, "normal", 1, true)) {
            expectFailure(portal, 5, "Duplicate casefold-exact refs on separate pages must abort");
            equal(0, portal.attempts().size(), "Duplicate exact reference ambiguity never submits");
            equal(0, portal.approvals().size(), "Duplicate exact reference ambiguity never approves");
            report.append("PASS: duplicate exact references across filtered pages abort before confirmation\n");
        }
    }

    private static void ambiguousFormsFailBeforeConfirmation() throws Exception {
        for (String mode : new String[] {"ambiguous-form", "ambiguous-comment", "ambiguous-confirm", "stale-response"}) {
            try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")), mode, 10, false)) {
                IOException failure = expectFailure(portal, 3, "Ambiguous visible approval controls must fail closed: " + mode);
                contains(failure.getMessage(), "No approval confirmation was sent", "Pre-submission errors are explicit");
                equal(0, portal.attempts().size(), "Ambiguous form sends no confirmation request: " + mode);
                equal(0, portal.approvals().size(), "Ambiguous form has no server approval: " + mode);
                equal(0L, number("return window.fixture.confirmationClicks"), "Ambiguous form never clicks paOkModal: " + mode);
                contains(System.getProperty("trueops.approve.result", ""), "failed=1", "Ambiguous form reports failure to UI: " + mode);
            }
        }
        report.append("PASS: duplicate modal forms, comment fields and confirmation buttons abort before any approval RPC\n");
    }

    private static void ambiguousAcknowledgementFailsWithoutRetry() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")), "ambiguous-ack", 10, false)) {
            expectFailure(portal, 3, "Multiple visible response dialogs must not count confirmed success");
            equal(Arrays.asList("REF-1"), portal.attempts(), "Ambiguous response never causes duplicate submission");
            equal(1, portal.approvals().size(), "Fake server committed once before ambiguous acknowledgement");
            equal(0L, number("return window.fixture.successClicks"), "Ambiguous response never acknowledges an arbitrary dialog");
            contains(System.getProperty("trueops.approve.result", ""), "approved=0", "Unconfirmed outcome is not counted approved");
            contains(System.getProperty("trueops.approve.result", ""), "failed=1", "Unconfirmed outcome reports failure");
            report.append("PASS: multiple visible success dialogs report an unconfirmed outcome without retry or arbitrary dismissal\n");
        }
    }

    private static void responseIdentityAndLifecycle() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-LIFECYCLE", "TK09", "ATTS")), "replaced-comment", 10, false)) {
            equal(1, ApprovePromsWorkflow.run(driver, portal.url(), "tk09", DESCRIPTION, 8).getApproved(), "Success also works after comment modal replacement");
            equal(1L, number("return window.fixture.confirmationClicks"), "Confirmation with checkmark clicked once");
            equal(1L, number("return window.fixture.successClicks"), "Success callback clicked once");
        }
        for (String mode : new String[] {"generic-checkmark", "duplicate-response-button"}) {
            try (MockPromsPortal portal = portal(Arrays.asList(row("REF-IDENTITY", "TK09", "ATTS")), mode, 10, false)) {
                IOException failure = expectFailure(portal, 3, "Unverified response is not accepted");
                contains(failure.getMessage(), "waiting for the PROMS success response", "Failure names response phase");
                contains(failure.getMessage(), "unconfirmed", "Post-submission errors retain uncertain outcome");
                equal(1, portal.attempts().size(), "No automatic resubmission");
                equal(0L, number("return window.fixture.successClicks"), "Unknown or ambiguous acknowledgement never clicked");
            }
        }
        report.append("PASS: observed PROMS checkmark confirmation and stacked/replaced response lifecycle; unknown/duplicate acknowledgements remain unconfirmed\n");
    }

    private static void duplicatePageFailsBeforeApproving() throws Exception {
        List<Row> rows = Arrays.asList(row("REF-1", "TK09", "ATTS"), row("REF-2", "TK09", "ATTS"),
                row("REF-3", "TK09", "ATTS"), row("REF-4", "TK09", "ATTS"));
        try (MockPromsPortal portal = portal(rows, "duplicate-pages", 2, true)) {
            expectFailure(portal, 3, "Duplicate page must abort rather than silently reuse stale rows");
            equal(0, portal.attempts().size(), "Unreliable scan aborts before any approval");
            equal(0, portal.approvals().size(), "Duplicate page cannot falsely approve a partial list");
            report.append("PASS: duplicate row-page signature aborts before approval\n");
        }
    }

    private static void missingConfirmationFails() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")), "missing-confirm", 10, false)) {
            IOException failure = expectFailure(portal, 3, "Missing confirmation must surface a workflow failure");
            contains(failure.getMessage().toLowerCase(), "fail", "Missing-confirm error has failure details");
            equal(0, portal.attempts().size(), "Missing confirm never submits approval");
            equal(0, portal.approvals().size(), "Missing confirm not a false success");
            report.append("PASS: missing confirmation reports failure without submitting\n");
        }
    }

    private static void serverFailureIsNotSuccess() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")), "server-failure", 10, false)) {
            IOException failure = expectFailure(portal, 3, "Server rejection must not produce a successful result");
            contains(failure.getMessage().toLowerCase(), "fail", "Rejected approval exposes failure counts");
            equal(Arrays.asList("REF-1"), portal.attempts(), "Synthetic approval submitted once");
            equal(0, portal.approvals().size(), "Rejected request not marked approved");
            report.append("PASS: rejected callback reports failure and never counts an approval\n");
        }
    }

    private static void successWithoutPendingRemovalFails() throws Exception {
        try (MockPromsPortal portal = portal(Arrays.asList(row("REF-1", "TK09", "ATTS")), "false-success", 10, false)) {
            expectFailure(portal, 3, "Success glyph alone must not count a still-pending request");
            equal(Arrays.asList("REF-1"), portal.attempts(), "False-success operation attempted only once");
            equal(0, portal.approvals().size(), "Still-pending request never counted approved");
            report.append("PASS: success popup with request still pending is treated as failure\n");
        }
    }

    private static IOException expectFailure(MockPromsPortal portal, int timeout, String label) throws Exception {
        try {
            ApprovePromsWorkflow.run(driver, portal.url(), "tk09", DESCRIPTION, timeout);
            throw new AssertionError(label);
        } catch (IOException expected) {
            checks++;
            return expected;
        }
    }

    private static MockPromsPortal portal(List<Row> rows, String mode, int pageSize, boolean pager) throws Exception {
        MockPromsPortal portal = new MockPromsPortal(fixture, rows, mode, pageSize, pager);
        try {
            driver.get(portal.url());
            return portal;
        } catch (Exception error) {
            portal.close();
            throw error;
        }
    }

    private static Row row(String ref, String project, String requester) { return new Row(ref, project, requester, "NT_IE_TK09(PAC) : SYNTHETIC-SITE"); }

    private static Long number(String javascript) { return ((Number) ((JavascriptExecutor) driver).executeScript(javascript)).longValue(); }

    private static void contains(String actual, String expected, String label) {
        if (actual == null || !actual.contains(expected)) throw new AssertionError(label + ": " + actual);
        checks++;
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
