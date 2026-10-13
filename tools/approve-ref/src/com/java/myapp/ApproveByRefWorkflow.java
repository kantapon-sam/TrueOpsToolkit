package com.java.myapp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** The reference workflow shares search/approval synchronization with Approve PROMS. */
public final class ApproveByRefWorkflow {
    private static final By REQUEST_LINK = By.xpath(".//td[@data-title-text='Request No.']//a");
    private static final By BOQ_TITLE = By.xpath("//h2[contains(concat(' ',normalize-space(@class),' '),' box-title ') and normalize-space()='Work Order BOQ']");
    private static final By TURNKEY_TAB = By.id("linkTurnkey");
    private static final By FBOQ_LINK = By.xpath("//a[@ng-click='loadFboqTkDetail(row)']");
    private static final By PART_HEADING = By.xpath("//table[contains(@class,'ng-table')]//th//span[normalize-space()='C-Part Type']");
    private static final By PART_CELLS = By.xpath(".//td[@title=\"'C-Part Type'\"]//div");
    private static final By APPROVE = By.xpath("//button[contains(@ng-click,'approvalAction') and contains(.,'Approve')]");
    private static final By LOADING = By.cssSelector("img.swal2-image");

    private ApproveByRefWorkflow() { }

    static List<PromsApprovalSupport.Row> matchingReferences(
            List<PromsApprovalSupport.Row> rows, String reference, String type) {
        List<PromsApprovalSupport.Row> matched = new ArrayList<>();
        for (PromsApprovalSupport.Row row : rows) {
            if (ReferenceMatcher.sameReference(reference, row.getReference())
                    && ReferenceMatcher.sameType(type, row.getRequestType())) matched.add(row);
        }
        return matched;
    }

    /** Read-only preview: searches and counts eligible rows without opening approval forms. */
    public static List<PromsApprovalSupport.Row> preview(WebDriver driver, String pendingUrl,
            String reference, String type, int seconds) throws IOException {
        driver.get(pendingUrl);
        return matchingReferences(new PromsApprovalSupport(driver, seconds)
                .searchReference(reference), reference, type);
    }

    /** Opens only the request/BOQ read-only views; never clicks Approve or its confirmation. */
    public static Eligibility previewEligibility(WebDriver driver, String pendingUrl,
            String reference, String type, int seconds) throws IOException {
        driver.get(pendingUrl);
        PromsApprovalSupport support = new PromsApprovalSupport(driver, seconds);
        List<PromsApprovalSupport.Row> rows = support.searchReference(reference);
        int exact = countExactReferences(rows, reference);
        List<PromsApprovalSupport.Row> matched = matchingReferences(rows, reference, type);
        if (exact != 1 || matched.size() != 1) {
            return new Eligibility(exact, matched.size(), 0, false,
                    exact == 0 ? "No exact pending reference." : exact > 1
                    ? "Duplicate exact references." : "Request type does not match.");
        }
        WebDriverWait wait = new WebDriverWait(driver, seconds);
        openExactRequest(support, wait, matched.get(0).getReference());
        List<String> parts = readPartTypes(driver, wait);
        boolean eligible = onlyServices(parts);
        return new Eligibility(exact, matched.size(), parts.size(), eligible,
                eligible ? "Non-empty FBOQ contains only Services. Nothing was approved."
                        : parts.isEmpty() ? "FBOQ has no C-Part Type rows. Nothing was approved."
                        : "FBOQ contains a non-Service C-Part Type. Nothing was approved.");
    }

    public static Result run(WebDriver driver, String pendingUrl, List<String[]> references,
            int seconds) throws IOException {
        clearPublishedResult();
        return run(driver, pendingUrl, references, seconds, new Reporter(null));
    }

    static Result run(WebDriver driver, String pendingUrl, List<String[]> references,
            int seconds, Reporter reporter) throws IOException {
        Result result = new Result();
        PromsApprovalSupport support = new PromsApprovalSupport(driver, seconds);
        WebDriverWait wait = new WebDriverWait(driver, seconds);
        try {
            validateReferences(references);
            reporter.progress("Reviewing " + references.size() + " reference(s)...");
            for (int index = 0; index < references.size(); index++) {
                String[] requested = references.get(index);
                reporter.progress("Searching reference " + (index + 1) + " of " + references.size() + "...");
                // Every iteration starts from the pending list, never a previous BOQ detail page.
                driver.get(pendingUrl);
                result.scanned++;
                List<PromsApprovalSupport.Row> rows = support.searchReference(requested[0]);
                int exactReferences = countExactReferences(rows, requested[0]);
                List<PromsApprovalSupport.Row> matches = matchingReferences(rows, requested[0], requested[1]);
                if (exactReferences != 1 || matches.size() != 1) {
                    result.skipped++;
                    reporter.progress("Reference " + (index + 1) + " skipped: "
                            + (exactReferences == 0 ? "no exact pending reference."
                            : exactReferences > 1 ? "duplicate exact references."
                            : "request type does not match.") + " " + result.counts());
                    continue;
                }
                result.matched++;
                String actualReference = matches.get(0).getReference();
                // Reacquire the exact row after asynchronous search; no first-row selection.
                openExactRequest(support, wait, actualReference);
                reporter.progress("Checking FBOQ Services for reference " + (index + 1) + "...");
                if (!onlyServices(readPartTypes(driver, wait))) {
                    result.skipped++;
                    reporter.progress("Reference " + (index + 1)
                            + " skipped: FBOQ must contain at least one row and all C-Part Types must be Services. "
                            + result.counts());
                    continue;
                }
                reporter.progress("Submitting approval " + (result.approved + 1) + ": reference "
                        + (index + 1) + " of " + references.size() + "...");
                wait.until(ExpectedConditions.elementToBeClickable(APPROVE)).click();
                support.submitAndConfirm(requested[2]);
                // A success-shaped dialog alone is insufficient: the exact request must leave pending.
                support.verifyNoLongerPending(pendingUrl, actualReference);
                result.approved++;
                reporter.progress("Approval confirmed. " + result.counts());
            }
            result.status = "completed";
            result.message = result.approved == 0
                    ? "No requests were approved. " + result.counts()
                    : "Approval run complete. " + result.counts();
            reporter.finish(result);
            return result;
        } catch (Exception failure) {
            result.failed++;
            result.status = "failed";
            result.message = "Approve by Ref stopped. " + result.counts()
                    + " Review the log and pending requests before retrying.";
            reporter.finish(result);
            throw new IOException(result.message, failure);
        }
    }

    private static int countExactReferences(List<PromsApprovalSupport.Row> rows, String reference) {
        int exact = 0;
        for (PromsApprovalSupport.Row row : rows) {
            if (ReferenceMatcher.sameReference(reference, row.getReference())) exact++;
        }
        return exact;
    }

    private static void openExactRequest(PromsApprovalSupport support, WebDriverWait wait,
            String reference) throws IOException {
        WebElement row = support.findExactRow(reference);
        List<WebElement> links = row.findElements(REQUEST_LINK);
        if (links.size() != 1) throw new IOException("The exact pending row has no unique request link.");
        wait.until(ExpectedConditions.elementToBeClickable(links.get(0))).click();
    }

    private static void validateReferences(List<String[]> references) throws IOException {
        if (references == null || references.isEmpty()) throw new IOException("Add at least one reference before running.");
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            String[] row = references.get(index);
            if (row == null || row.length != 3 || ReferenceMatcher.normalize(row[0]).isEmpty()
                    || ReferenceMatcher.normalize(row[1]).isEmpty() || row[2] == null) {
                throw new IOException("Invalid reference row " + (index + 1) + ": Ref No. and Type are required.");
            }
            String identity = ReferenceMatcher.normalize(row[0]) + "\n" + ReferenceMatcher.normalize(row[1]);
            if (!identities.add(identity)) throw new IOException("Duplicate Ref No. and Type in the reference input (row "
                    + (index + 1) + "). Keep one description for each approval.");
        }
    }

    private static List<String> readPartTypes(WebDriver driver, WebDriverWait wait) throws IOException {
        WebElement title = wait.until(ExpectedConditions.visibilityOfElementLocated(BOQ_TITLE));
        if (!isDisplayed(driver, TURNKEY_TAB)) {
            WebElement collapse = title.findElement(By.xpath("./ancestor::div[contains(concat(' ',normalize-space(@class),' '),' box-header ')][1]//button[@data-widget='collapse']"));
            scrollAndClick(driver, wait, collapse);
        }
        scrollAndClick(driver, wait, wait.until(ExpectedConditions.visibilityOfElementLocated(TURNKEY_TAB)));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(LOADING));
        WebElement fboq = wait.until(ignored -> {
            List<WebElement> links = visible(driver.findElements(FBOQ_LINK));
            if (links.size() > 1) throw new IllegalStateException("FBOQ has more than one Turnkey detail. Approval was not submitted.");
            return links.isEmpty() ? null : links.get(0);
        });
        scrollAndClick(driver, wait, fboq);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(LOADING));
        wait.until(ExpectedConditions.visibilityOfElementLocated(PART_HEADING));
        List<String> parts = stablePartTypes(driver, wait);
        requireCompletePartTable(driver);
        return parts;
    }

    /** Never infer BOQ completeness from page one of an explicitly paginated C-Part table. */
    private static void requireCompletePartTable(WebDriver driver) throws IOException {
        List<WebElement> headings = visible(driver.findElements(PART_HEADING));
        if (headings.size() != 1) throw new IOException("The C-Part Type table is ambiguous. Approval was not submitted.");
        WebElement table = headings.get(0).findElement(By.xpath("./ancestor::table[1]"));
        // ng-table places its pager next to its table. Inspect only ancestors that
        // contain this sole table, stopping before any unrelated/pending tables.
        for (int level = 1; level <= 8; level++) {
            List<WebElement> holders = table.findElements(By.xpath("./ancestor::div[" + level + "]"));
            if (holders.isEmpty()) break;
            WebElement holder = holders.get(0);
            if (holder.findElements(By.tagName("table")).size() != 1) break;
            for (WebElement pager : visible(holder.findElements(By.cssSelector(
                    ".ng-table-pager, .ng-table-pagination, ul.pagination")))) {
                for (WebElement link : visible(pager.findElements(By.tagName("a")))) {
                    String text = link.getText().trim();
                    boolean numericPage = text.matches("[0-9]+");
                    if (numericPage && Integer.parseInt(text) > 1) {
                        throw new IOException("The C-Part Type table has additional pages. Verify the complete FBOQ manually; approval was not submitted.");
                    }
                    String kind = link.getAttribute("ng-switch-when");
                    if ("last".equals(kind) && numericPage) continue;
                    if (("next".equals(kind) || "last".equals(kind)) && pagerLinkEnabled(link)) {
                        throw new IOException("The C-Part Type table has additional pages. Verify the complete FBOQ manually; approval was not submitted.");
                    }
                }
            }
        }
    }

    private static boolean pagerLinkEnabled(WebElement link) {
        String own = " " + link.getAttribute("class") + " ";
        String parent = " " + link.findElement(By.xpath("..")).getAttribute("class") + " ";
        return link.isEnabled() && !"true".equalsIgnoreCase(link.getAttribute("aria-disabled"))
                && !own.contains(" disabled ") && !parent.contains(" disabled ");
    }

    private static boolean onlyServices(List<String> parts) {
        if (parts.isEmpty()) return false;
        for (String part : parts) if (!"services".equals(ReferenceMatcher.normalize(part))) return false;
        return true;
    }

    private static List<String> stablePartTypes(WebDriver driver, WebDriverWait wait) throws IOException {
        final String[] fingerprint = { null };
        final long[] stableSince = { 0 };
        final long started = System.nanoTime();
        try {
            return wait.until(ignored -> {
                try {
                    if (isDisplayed(driver, LOADING)) return null;
                    List<WebElement> headings = visible(driver.findElements(PART_HEADING));
                    if (headings.size() != 1) return null;
                    WebElement table = headings.get(0).findElement(By.xpath("./ancestor::table[1]"));
                    List<String> types = new ArrayList<>();
                    for (WebElement cell : table.findElements(PART_CELLS)) {
                        if (cell.isDisplayed()) types.add(cell.getText());
                    }
                    String current = types.toString();
                    long now = System.nanoTime();
                    if (!current.equals(fingerprint[0])) { fingerprint[0] = current; stableSince[0] = now; }
                    // Give Angular time to populate a newly visible table; an empty list is never eligible.
                    return now - started >= 1000000000L && now - stableSince[0] >= 500000000L ? types : null;
                } catch (StaleElementReferenceException stale) {
                    fingerprint[0] = null;
                    stableSince[0] = 0;
                    return null;
                }
            });
        } catch (RuntimeException failure) {
            throw new IOException("Could not verify a stable FBOQ C-Part Type table. Approval was not submitted.", failure);
        }
    }

    private static void scrollAndClick(WebDriver driver, WebDriverWait wait, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
        wait.until(ExpectedConditions.elementToBeClickable(element)).click();
    }

    private static boolean isDisplayed(WebDriver driver, By by) {
        return !visible(driver.findElements(by)).isEmpty();
    }

    private static List<WebElement> visible(List<WebElement> elements) {
        List<WebElement> visible = new ArrayList<>();
        for (WebElement element : elements) if (element.isDisplayed()) visible.add(element);
        return visible;
    }

    public static final class Result {
        int scanned, matched, approved, skipped, failed;
        String status = "running", message = "";
        String counts() { return "Scanned: " + scanned + "; matched: " + matched + "; approved: "
                + approved + "; skipped: " + skipped + "; failed: " + failed + "."; }
        public int getScanned() { return scanned; }
        public int getMatched() { return matched; }
        public int getApproved() { return approved; }
        public int getSkipped() { return skipped; }
        public int getFailed() { return failed; }
        public String getSummary() { return message; }
        public String getMachineSummary() { return "scanned=" + scanned + " matched=" + matched
                + " approved=" + approved + " skipped=" + skipped + " failed=" + failed; }
    }

    public static final class Eligibility {
        private final int referenceMatches, typeMatches, partCount;
        private final boolean eligible;
        private final String reason;
        private Eligibility(int referenceMatches, int typeMatches, int partCount, boolean eligible, String reason) {
            this.referenceMatches = referenceMatches;
            this.typeMatches = typeMatches;
            this.partCount = partCount;
            this.eligible = eligible;
            this.reason = reason;
        }
        public int getReferenceMatches() { return referenceMatches; }
        public int getTypeMatches() { return typeMatches; }
        public int getPartCount() { return partCount; }
        public boolean isEligible() { return eligible; }
        public String getReason() { return reason; }
    }

    public static void clearPublishedResult() {
        System.clearProperty("trueops.approve-ref.summary");
        System.clearProperty("trueops.approve-ref.result");
        System.clearProperty("trueops.approve-ref.progress");
    }

    public static void progress(String message) {
        new Reporter(null).progress(message);
    }

    static final class Reporter {
        private final Consumer<String> listener;
        Reporter(Consumer<String> listener) { this.listener = listener; }
        void progress(String message) {
            if (listener != null) listener.accept(message);
            System.setProperty("trueops.approve-ref.progress", message);
            System.out.println("TRUEOPS_APPROVE_REF_PROGRESS " + message);
        }
        void finish(Result result) {
            progress(result.message);
            System.setProperty("trueops.approve-ref.summary", result.message);
            System.setProperty("trueops.approve-ref.result", result.getMachineSummary());
            System.out.println("TRUEOPS_APPROVE_REF_RESULT " + result.getMachineSummary());
        }
    }
}
