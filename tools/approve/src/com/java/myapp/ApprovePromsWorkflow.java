package com.java.myapp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Approves only the complete captured Search matches, one exact reference at a time. */
public final class ApprovePromsWorkflow {
    private static final By APPROVE = By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]");

    private ApprovePromsWorkflow() { }

    /** Read-only inspection: never selects requests or invokes approval controls. */
    public static Result preview(WebDriver driver, String search, int timeoutSeconds) throws IOException {
        clearPublishedResult();
        Result result = new Result();
        PromsApprovalSupport support = new PromsApprovalSupport(driver, timeoutSeconds);
        List<PromsApprovalSupport.Row> rows = support.collect(search, ApprovePromsWorkflow::progress);
        result.scanned = rows.size();
        for (PromsApprovalSupport.Row row : rows) {
            if (PromsApprovalSupport.matchesSearch(row, search)) {
                result.references.add(row.getReference());
            }
        }
        result.matched = result.references.size();
        publish(result, "Read-only preview completed. Nothing was approved.");
        return result;
    }

    public static Result run(WebDriver driver, String pendingUrl, String search,
            String description, int timeoutSeconds) throws IOException {
        clearPublishedResult();
        Result result = new Result();
        PromsApprovalSupport support = new PromsApprovalSupport(driver, timeoutSeconds);
        try {
            List<PromsApprovalSupport.Row> rows = support.collect(search, message -> {
                progress(message);
            });
            result.scanned = rows.size();
            List<PromsApprovalSupport.Row> matches = new ArrayList<>();
            for (PromsApprovalSupport.Row row : rows) {
                if (PromsApprovalSupport.matchesSearch(row, search)) {
                    matches.add(row);
                    result.references.add(row.getReference());
                }
            }
            result.matched = matches.size();
            publish(result, null);
            if (matches.isEmpty()) {
                String reason = result.scanned == 0
                        ? "No pending requests were found for Search. Nothing was approved."
                        : "No requests matched all Search terms (case-insensitive). Nothing was approved.";
                publish(result, reason);
                progress(result.summary);
                return result;
            }
            progress("Found " + result.matched + " matching requests. Approving each exact reference separately.");
            for (PromsApprovalSupport.Row request : matches) {
                String reference = request.getReference();
                progress("Approving " + (result.approved + result.skipped + 1) + " of "
                        + result.matched + ": " + reference + ".");
                boolean submitted = false;
                try {
                    PromsApprovalSupport.Row selected = null;
                    for (PromsApprovalSupport.Row row : support.searchReference(reference)) {
                        if (PromsApprovalSupport.sameReference(reference, row.getReference())) {
                            if (selected != null) {
                                throw new IOException("More than one pending row has reference " + reference + ". No approval was submitted.");
                            }
                            selected = row;
                        }
                    }
                    if (selected == null || !PromsApprovalSupport.matchesSearch(selected, search)) {
                        result.skipped++;
                        System.out.println("Skipped " + reference + ": no longer pending or no longer matches Search.");
                        publish(result, null);
                        continue;
                    }
                    support.selectExactReference(reference);
                    new WebDriverWait(driver, timeoutSeconds).until(ExpectedConditions.elementToBeClickable(APPROVE)).click();
                    support.verifyOnlySelectedReference(reference);
                    submitted = true;
                    support.submitAndConfirm(description);
                    support.verifyNoLongerPending(pendingUrl, reference);
                    result.approved++;
                    System.out.println("Confirmed approval: " + reference);
                    publish(result, null);
                } catch (Exception ex) {
                    result.failed++;
                    String advice = submitted
                            ? "The approval outcome for " + reference + " is unconfirmed. Check this reference in PROMS before retrying; no automatic retry was made."
                            : "The approval stopped at " + reference + " before a confirmed submission. Check the PROMS page and the log.";
                    publish(result, advice);
                    throw new IOException(result.summary, ex);
                }
            }
            publish(result, "Approval run completed.");
            progress(result.summary);
            return result;
        } catch (IOException ex) {
            result.scanned = Math.max(result.scanned, support.getScannedCount());
            if (result.failed == 0) {
                result.failed++;
                publish(result, ex.getMessage());
            }
            throw ex;
        } catch (Exception ex) {
            result.scanned = Math.max(result.scanned, support.getScannedCount());
            result.failed++;
            publish(result, "Cannot complete the pending-request scan. Check the PROMS page and log before retrying.");
            throw new IOException(result.summary, ex);
        }
    }

    public static void clearPublishedResult() {
        System.clearProperty("trueops.approve.summary");
        System.clearProperty("trueops.approve.result");
        System.clearProperty("trueops.approve.progress");
    }

    public static void progress(String message) {
        System.setProperty("trueops.approve.progress", message);
        System.out.println("TRUEOPS_APPROVE_PROGRESS " + message);
    }

    private static void publish(Result result, String reason) {
        result.summary = (reason == null ? "Approval results." : reason) + " Scanned: " + result.scanned
                + "; matched: " + result.matched + "; approved: " + result.approved
                + "; skipped: " + result.skipped + "; failed: " + result.failed + ".";
        System.setProperty("trueops.approve.summary", result.summary);
        System.setProperty("trueops.approve.result", result.getMachineSummary());
        System.out.println("TRUEOPS_APPROVE_RESULT " + result.getMachineSummary());
    }

    public static final class Result {
        private int scanned;
        private int matched;
        private int approved;
        private int skipped;
        private int failed;
        private String summary = "";
        private final List<String> references = new ArrayList<>();

        public int getScanned() { return scanned; }
        public int getMatched() { return matched; }
        public int getApproved() { return approved; }
        public int getSkipped() { return skipped; }
        public int getFailed() { return failed; }
        public String getSummary() { return summary; }
        public List<String> getMatchedReferences() { return Collections.unmodifiableList(references); }
        public String getMachineSummary() {
            return "scanned=" + scanned + " matched=" + matched + " approved=" + approved
                    + " skipped=" + skipped + " failed=" + failed;
        }
    }
}
