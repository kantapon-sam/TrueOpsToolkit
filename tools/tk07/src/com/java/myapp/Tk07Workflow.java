package com.java.myapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** TK07 RFS review, retaining canonical project eligibility and approve/reject decisions. */
public final class Tk07Workflow {
    private static final By REQUEST_LINK = By.cssSelector("td[data-title-text='Request No.'] a");
    private static final By ACTIVITY_HEADER = By.xpath("//h2[normalize-space()='Activity/Milestone']");
    private static final By ACTIVITY = By.xpath("//a[normalize-space()='NT_IE_TK07']");
    private static final By ATTACHMENT = By.xpath("//a[@data-target='#activity_attachment' and normalize-space()='Attachment']");
    private static final By ATTACHMENT_PANEL = By.id("activity_attachment");
    private static final By ATTACHMENT_ROWS = By.xpath("//tr[contains(@ng-repeat,'attachmentsAct')]");
    private static final By BUSY = By.cssSelector("img.swal2-image");
    private static final By APPROVE = By.xpath("//button[@ng-click=\"approvalAction('APPROVE_ALT')\"]");
    private static final By REJECT = By.xpath("//button[@ng-click=\"approvalAction('REJECT_ALT')\"]");

    private Tk07Workflow() { }

    public static Result preview(WebDriver driver, String pendingUrl, String search, int seconds) throws IOException {
        clearPublishedResult();
        driver.get(pendingUrl);
        Result result = new Result();
        List<PromsApprovalSupport.Row> rows = new PromsApprovalSupport(driver, seconds).collect(search, Tk07Workflow::progress);
        result.scanned = rows.size();
        for (PromsApprovalSupport.Row row : rows) if (eligible(row, search)) result.matched++;
        publish(result, "Read-only TK07 preview completed. No approvals or rejections were submitted.");
        return result;
    }

    public static Result run(WebDriver driver, String pendingUrl, String search,
            String approvalDescription, String rejectionDescription, Path downloadDirectory, int seconds) throws IOException {
        clearPublishedResult();
        Result result = new Result();
        PromsApprovalSupport support = new PromsApprovalSupport(driver, seconds);
        WebDriverWait wait = new WebDriverWait(driver, seconds);
        wait.pollingEvery(100, TimeUnit.MILLISECONDS);
        try {
            Path downloads = downloadDirectory.toAbsolutePath().normalize();
            Files.createDirectories(downloads);
            if (Files.isSymbolicLink(downloads)) throw new IOException("The TK07 download directory must be a regular current-run folder.");
            driver.get(pendingUrl);
            List<PromsApprovalSupport.Row> rows = support.collect(search, Tk07Workflow::progress);
            result.scanned = rows.size();
            List<PromsApprovalSupport.Row> candidates = new ArrayList<>();
            for (PromsApprovalSupport.Row row : rows) if (eligible(row, search)) candidates.add(row);
            result.matched = candidates.size();
            progress("Found " + candidates.size() + " TK07 requests with an eligible project. Reviewing RFS evidence...");
            for (int index = 0; index < candidates.size(); index++) {
                PromsApprovalSupport.Row candidate = candidates.get(index);
                progress("Reviewing TK07 request " + (index + 1) + " of " + candidates.size() + "...");
                driver.get(pendingUrl);
                PromsApprovalSupport.Row current = null;
                for (PromsApprovalSupport.Row row : support.searchReference(candidate.getReference())) {
                    if (PromsApprovalSupport.sameReference(candidate.getReference(), row.getReference())) {
                        if (current != null) throw new IOException("Duplicate exact TK07 references. No decision was submitted.");
                        current = row;
                    }
                }
                if (current == null || !eligible(current, search)) {
                    result.skipped++;
                    progress("TK07 request no longer matches the review criteria. " + result.counts());
                    continue;
                }
                String site;
                try { site = Tk07PdfReview.siteFromRequestType(current.getRequestType()); }
                catch (IOException invalidSite) {
                    result.skipped++;
                    progress("Skipped TK07 request: its site identifier could not be verified. " + result.counts());
                    continue;
                }
                WebElement pendingRow = support.findExactRow(current.getReference());
                List<WebElement> links = pendingRow.findElements(REQUEST_LINK);
                if (links.size() != 1) throw new IOException("The exact TK07 pending row has no unique request link.");
                wait.until(ExpectedConditions.elementToBeClickable(links.get(0))).click();
                Evidence evidence = inspectRfs(driver, wait, downloads, current.getReference(), site, seconds);
                if (evidence.decision == Decision.SKIP) {
                    result.skipped++;
                    progress("Skipped TK07 request: " + evidence.reason + " " + result.counts());
                    continue;
                }
                closeAttachmentPanel(driver, wait);
                boolean approve = evidence.decision == Decision.APPROVE;
                progress((approve ? "Approving" : "Rejecting") + " TK07 request " + (index + 1)
                        + " of " + candidates.size() + ": " + evidence.reason);
                wait.until(ExpectedConditions.elementToBeClickable(approve ? APPROVE : REJECT)).click();
                support.submitAndConfirm(approve ? approvalDescription : rejectionDescription);
                support.verifyNoLongerPending(pendingUrl, current.getReference());
                if (approve) result.approved++; else result.rejected++;
                publish(result, null);
                progress("TK07 decision confirmed. " + result.counts());
            }
            publish(result, result.approved == 0 && result.rejected == 0
                    ? "No requests were approved or rejected." : "TK07 review completed.");
            progress(result.summary);
            return result;
        } catch (Exception failure) {
            result.scanned = Math.max(result.scanned, support.getScannedCount());
            result.failed++;
            publish(result, "TK07 review stopped. " + (failure instanceof PromsApprovalSupport.SubmissionException ? failure.getMessage() : "Check the affected request before retrying; no automatic resubmission was made."));
            throw new IOException(result.summary, failure);
        }
    }

    private static boolean eligible(PromsApprovalSupport.Row row, String search) throws IOException {
        return ReferenceMatcher.sameType("TK07", row.getRequestType())
                && Tk07PdfReview.eligibleProject(row.getProject()) && PromsApprovalSupport.matchesSearch(row, search);
    }

    private static Evidence inspectRfs(WebDriver driver, WebDriverWait wait, Path directory,
            String reference, String site, int seconds) throws IOException {
        WebElement header = wait.until(ExpectedConditions.visibilityOfElementLocated(ACTIVITY_HEADER));
        long load = Tk07AttachmentLoad.begin(driver, reference);
        if (visible(driver.findElements(ACTIVITY)).isEmpty()) {
            List<WebElement> toggles = header.findElements(By.xpath("./ancestor::div[contains(concat(' ',normalize-space(@class),' '),' box-header ')][1]//button[@data-widget='collapse']"));
            if (toggles.size() != 1) return Evidence.skip("Activity/Milestone could not be expanded safely.");
            scrollAndClick(driver, wait, toggles.get(0));
        }
        scrollAndClick(driver, wait, wait.until(ExpectedConditions.visibilityOfElementLocated(ACTIVITY)));
        scrollAndClick(driver, wait, wait.until(ExpectedConditions.visibilityOfElementLocated(ATTACHMENT)));
        AttachmentSnapshot snapshot;
        try { snapshot = stableAttachments(driver, wait, load); }
        catch (RuntimeException unreadableList) { return Evidence.skip("the attachment list did not provide a confirmed loaded result."); }
        List<AttachmentEntry> rfs = new ArrayList<>();
        for (AttachmentEntry entry : snapshot.entries) {
            if (entry.name.toLowerCase(Locale.ROOT).contains("rfs")) rfs.add(entry);
        }
        if (rfs.isEmpty()) return Evidence.reject("no RFS attachment was found in the loaded attachment list.");
        // The legacy program inspected whichever PDF arrived last. Do not guess
        // which evidence controls a decision when several RFS files are present.
        if (rfs.size() != 1) return Evidence.skip("multiple RFS attachments require manual review.");
        AttachmentEntry attachment = rfs.get(0);
        if (!attachment.name.toLowerCase(Locale.ROOT).endsWith(".pdf")) return Evidence.skip("the RFS attachment is not a PDF.");
        Set<Path> before = pdfFiles(directory);
        List<WebElement> download = attachment.row.findElements(By.xpath(".//a[i[contains(@class,'fa-download')]]"));
        if (download.size() != 1) return Evidence.skip("the RFS attachment has no unique download link.");
        scrollAndClick(driver, wait, download.get(0));
        Path pdf;
        try { pdf = waitForNewPdf(directory, before, Math.max(30, seconds)); }
        catch (IOException unreadableDownload) {
            // A late download could otherwise appear during the next request's
            // snapshot window. Stop this run before inspecting another request.
            throw new IOException("The current RFS PDF download is unconfirmed. Review stopped before the next request; no decision was submitted.", unreadableDownload);
        }
        try {
            return Tk07PdfReview.pdfContainsSite(pdf, site)
                    ? Evidence.approve("the current RFS PDF contains the normalized site identifier.")
                    : Evidence.reject("the current RFS PDF does not contain the normalized site identifier.");
        } catch (IOException unreadablePdf) { return Evidence.skip("the current RFS PDF could not be read."); }
    }

    private static AttachmentSnapshot stableAttachments(WebDriver driver, WebDriverWait wait, long load) {
        final String[] previous = { null };
        final long[] since = { 0 };
        final long start = System.nanoTime();
        return wait.until(ignored -> {
            try {
                if (!visible(driver.findElements(BUSY)).isEmpty()) return null;
                List<AttachmentEntry> entries = new ArrayList<>();
                StringBuilder signature = new StringBuilder();
                for (WebElement row : visible(driver.findElements(ATTACHMENT_ROWS))) {
                    List<WebElement> names = row.findElements(By.xpath(".//td[2]"));
                    if (names.size() != 1) return null;
                    String name = names.get(0).getText().trim();
                    if (name.isEmpty()) return null;
                    entries.add(new AttachmentEntry(row, name));
                    signature.append(name).append('\n');
                }
                if (entries.isEmpty() && !explicitEmptyAttachments(driver)) return null;
                if (!Tk07AttachmentLoad.complete(driver, load, entries.isEmpty())) return null;
                long now = System.nanoTime();
                String value = signature.toString();
                if (!value.equals(previous[0])) { previous[0] = value; since[0] = now; }
                return now - start >= TimeUnit.SECONDS.toNanos(1)
                        && now - since[0] >= TimeUnit.MILLISECONDS.toNanos(500) ? new AttachmentSnapshot(entries) : null;
            } catch (StaleElementReferenceException replaced) { previous[0] = null; return null; }
        });
    }

    private static boolean explicitEmptyAttachments(WebDriver driver) {
        List<WebElement> panels = visible(driver.findElements(ATTACHMENT_PANEL));
        if (panels.size() != 1) return false;
        String text = panels.get(0).getText().toLowerCase(Locale.ROOT);
        return text.contains("no attachments") || text.contains("no attachment")
                || text.contains("no records") || text.contains("no data");
    }

    static Path waitForNewPdf(Path directory, Set<Path> before, int seconds) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Path previous = null;
        long size = -1, stableSince = 0;
        while (System.nanoTime() < deadline) {
            Set<Path> files = pdfFiles(directory);
            files.removeAll(before);
            boolean partial;
            try (Stream<Path> children = Files.list(directory)) {
                partial = children.anyMatch(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".crdownload"));
            }
            if (files.size() > 1) throw new IOException("More than one new RFS PDF appeared for the current request.");
            if (files.size() == 1 && !partial) {
                Path candidate = files.iterator().next();
                long currentSize = Files.size(candidate), now = System.nanoTime();
                if (!candidate.equals(previous) || currentSize != size) { previous = candidate; size = currentSize; stableSince = now; }
                if (currentSize > 0 && now - stableSince >= TimeUnit.MILLISECONDS.toNanos(750)) return candidate;
            } else { previous = null; size = -1; }
            try { Thread.sleep(100); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("TK07 PDF wait was interrupted.", stopped); }
        }
        throw new IOException("The current RFS PDF did not finish downloading.");
    }

    static Set<Path> pdfFiles(Path directory) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .forEach(file -> files.add(file.toAbsolutePath().normalize()));
        }
        return files;
    }

    private static void closeAttachmentPanel(WebDriver driver, WebDriverWait wait) {
        List<WebElement> panels = visible(driver.findElements(ATTACHMENT_PANEL));
        if (panels.size() != 1) return;
        List<WebElement> close = visible(panels.get(0).findElements(By.cssSelector("[data-dismiss='modal']")));
        if (!close.isEmpty()) {
            close.get(0).click();
            wait.until(ExpectedConditions.invisibilityOf(panels.get(0)));
        }
    }

    private static List<WebElement> visible(List<WebElement> elements) {
        List<WebElement> shown = new ArrayList<>();
        for (WebElement element : elements) if (element.isDisplayed()) shown.add(element);
        return shown;
    }
    private static void scrollAndClick(WebDriver driver, WebDriverWait wait, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
        wait.until(ExpectedConditions.elementToBeClickable(element)).click();
    }

    private enum Decision { APPROVE, REJECT, SKIP }
    private static final class Evidence {
        final Decision decision; final String reason;
        Evidence(Decision decision, String reason) { this.decision = decision; this.reason = reason; }
        static Evidence approve(String reason) { return new Evidence(Decision.APPROVE, reason); }
        static Evidence reject(String reason) { return new Evidence(Decision.REJECT, reason); }
        static Evidence skip(String reason) { return new Evidence(Decision.SKIP, reason); }
    }
    private static final class AttachmentEntry {
        final WebElement row; final String name;
        AttachmentEntry(WebElement row, String name) { this.row = row; this.name = name; }
    }
    private static final class AttachmentSnapshot {
        final List<AttachmentEntry> entries;
        AttachmentSnapshot(List<AttachmentEntry> entries) { this.entries = entries; }
    }

    public static void clearPublishedResult() {
        System.clearProperty("trueops.tk07.summary");
        System.clearProperty("trueops.tk07.result");
        System.clearProperty("trueops.tk07.progress");
    }
    public static void progress(String message) {
        System.setProperty("trueops.tk07.progress", message);
        System.out.println("TRUEOPS_TK07_PROGRESS " + message);
    }
    private static void publish(Result result, String reason) {
        result.summary = (reason == null ? "TK07 results." : reason) + " " + result.counts();
        System.setProperty("trueops.tk07.summary", result.summary);
        System.setProperty("trueops.tk07.result", result.getMachineSummary());
        System.out.println("TRUEOPS_TK07_RESULT " + result.getMachineSummary());
    }
    public static final class Result {
        int scanned, matched, approved, rejected, skipped, failed;
        String summary = "";
        String counts() { return "Scanned: " + scanned + "; matched: " + matched + "; approved: " + approved
                + "; rejected: " + rejected + "; skipped: " + skipped + "; failed: " + failed + "."; }
        public int getScanned() { return scanned; }
        public int getMatched() { return matched; }
        public int getApproved() { return approved; }
        public int getRejected() { return rejected; }
        public int getSkipped() { return skipped; }
        public int getFailed() { return failed; }
        public String getSummary() { return summary; }
        public String getMachineSummary() { return "scanned=" + scanned + " matched=" + matched + " approved=" + approved
                + " rejected=" + rejected + " skipped=" + skipped + " failed=" + failed; }
    }
}
