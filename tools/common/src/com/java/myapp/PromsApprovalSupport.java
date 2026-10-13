package com.java.myapp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Shared synchronization and exact-reference approval checks for both PROMS approval tools. */
public final class PromsApprovalSupport {
    private static final By SEARCH = By.id("txtSearch");
    private static final By ROWS = By.xpath("//tr[.//td[@data-title-text='Request No.']//a]");
    private static final By REF = By.cssSelector("td[data-title-text='Request No.'] a");
    private static final By CHECKBOX = By.cssSelector("input[type='checkbox'][ng-model='row.checked']");
    private static final By COMMENT = By.cssSelector("textarea[ng-model='commentDesc']");
    private static final By CONFIRM = By.xpath("//button[contains(@ng-click, 'paOkModal()')]");
    private static final By CONFIRM_IN_DIALOG = By.xpath(".//button[contains(@ng-click, 'paOkModal()')]");
    private static final By SUCCESS = By.xpath("//i[contains(concat(' ', normalize-space(@class), ' '), ' glyphicon-ok ')]/ancestor::button[1]");
    private static final By SUCCESS_IN_DIALOG = By.xpath(".//i[contains(concat(' ', normalize-space(@class), ' '), ' glyphicon-ok ')]/ancestor::button[1]");
    private static final By SUCCESS_ICON = By.cssSelector(".swal2-icon.swal2-success");
    private static final By DIALOGS = By.cssSelector(".modal, .modal-content, [role='dialog'], .swal2-popup");
    private static final By ANCESTOR_DIALOGS = By.xpath("ancestor::*[contains(concat(' ',normalize-space(@class),' '),' modal ') or contains(concat(' ',normalize-space(@class),' '),' modal-content ') or @role='dialog' or contains(concat(' ',normalize-space(@class),' '),' swal2-popup ')]");
    private static final By BUSY = By.cssSelector("img.swal2-image");
    private static final By ERRORS = By.cssSelector(".pull-left.ng-binding[data-ng-show='searchErrorMessage != null'], .swal2-icon.swal2-error, .alert-danger");
    private static final long STABLE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final WebDriver driver;
    private final WebDriverWait wait;
    private final long minimumSettleNanos;
    private int scannedCount;
    private long responseGeneration;

    public PromsApprovalSupport(WebDriver driver, int timeoutSeconds) {
        if (timeoutSeconds < 2) {
            throw new IllegalArgumentException("The approval page timeout must be at least two seconds.");
        }
        this.driver = driver;
        this.wait = new WebDriverWait(driver, timeoutSeconds);
        this.wait.pollingEvery(100, TimeUnit.MILLISECONDS);
        this.minimumSettleNanos = TimeUnit.MILLISECONDS.toNanos(searchDelayMillis());
    }

    private static int searchDelayMillis() {
        String configured = System.getProperty("trueops.search.delay.ms", "2000").trim();
        try {
            if (!configured.matches("[0-9]+")) throw new NumberFormatException("Not an integer");
            int delay = Integer.parseInt(configured);
            if (delay < 1000 || delay > 10000) throw new NumberFormatException("Out of range");
            return delay;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Search delay must be an integer from 1000 to 10000 milliseconds. Set it in Toolkit settings before running.", invalid);
        }
    }

    /** Search first term on the server, then snapshot every filtered page before changing any request. */
    public List<Row> collect(String search, Consumer<String> progress) throws IOException {
        scannedCount = 0;
        String[] rawTerms = parseTerms(search, false);
        progress.accept("Loading pending requests. No approvals have been submitted.");
        return scanFiltered(rawTerms[0], progress, true, null);
    }

    /** The separate exact-reference scan must not overwrite the original Search scan count. */
    private List<Row> scanFiltered(String query, Consumer<String> progress,
            boolean initialScan, String exactReference) throws IOException {
        Snapshot snapshot = filter(query);
        int total = pageCount();
        if (total > 10000) {
            throw new IOException("The pending page count is too large. Narrow Search and try again.");
        }
        List<Row> loaded = nativeRows();
        if (loaded != null) {
            Set<String> references = new java.util.HashSet<>();
            int exact = 0;
            for (Row row : loaded) {
                if (!references.add(normalize(row.reference))) {
                    throw new IOException("The complete filtered request list contains duplicate references. No approval was submitted.");
                }
                if (exactReference != null && sameReference(exactReference, row.reference)) exact++;
            }
            if (initialScan) scannedCount = loaded.size();
            progress.accept("Loaded " + loaded.size() + " filtered requests. Reviewing the complete request list.");
            if (exact == 1 && !hasReference(snapshot, exactReference)) {
                boolean found = false;
                for (int page = 2; page <= total; page++) {
                    snapshot = advance(snapshot, page, total);
                    if (hasReference(snapshot, exactReference)) { found = true; break; }
                }
                if (!found) throw new IOException("The exact filtered reference could not be located on its pending page. No approval was submitted.");
                findExactRow(exactReference);
            }
            return loaded;
        }
        Map<String, Row> requests = new LinkedHashMap<>();
        int exactPage = 0;
        int exactCount = 0;
        for (int page = 1; page <= total; page++) {
            if (page > 1) {
                WebElement link = pageLink(page, total);
                if (link == null || !enabled(link)) {
                    throw new IOException("Cannot reach pending page " + page + " of " + total + ". No unscanned requests will be approved.");
                }
                String previous = snapshot.signature;
                link.click();
                snapshot = settledRows(previous, true);
            }
            int before = requests.size();
            for (Row row : snapshot.rows) {
                if (exactReference != null && sameReference(exactReference, row.reference)) {
                    exactPage = page;
                    if (++exactCount > 1) {
                        throw new IOException("More than one pending row has reference " + exactReference + ". No approval was submitted.");
                    }
                }
                String key = normalize(row.reference);
                if (!requests.containsKey(key)) {
                    requests.put(key, row);
                }
            }
            if (page > 1 && requests.size() == before) {
                throw new IOException("Pending page " + page + " repeated previously scanned requests. Try again after PROMS finishes loading.");
            }
            if (initialScan) {
                scannedCount = requests.size();
            }
            progress.accept("Scanning pending requests: page " + page + " of " + total
                    + ", " + requests.size() + " unique requests.");
        }
        if (exactPage > 0 && exactPage != total) {
            snapshot = goToFirst(snapshot, total);
            for (int page = 2; page <= exactPage; page++) {
                snapshot = advance(snapshot, page, total);
            }
            // Do not select a changed/reordered row using a stale remembered page number.
            findExactRow(exactReference);
        }
        return new ArrayList<>(requests.values());
    }

    private static boolean hasReference(Snapshot snapshot, String reference) {
        for (Row row : snapshot.rows) if (sameReference(reference, row.reference)) return true;
        return false;
    }

    private List<Row> nativeRows() throws IOException {
        Object value = PromsPageActivity.nativeRows(driver, responseGeneration);
        if (value == null) return null;
        if (!(value instanceof List)) throw new IOException("The complete pending request list could not be verified.");
        List<Row> result = new ArrayList<>();
        for (Object entry : (List<?>)value) {
            if (!(entry instanceof Map)) throw new IOException("The complete pending request list has an unsupported row.");
            Map<?,?> row = (Map<?,?>)entry;
            for (String field : new String[]{"reference","project","requester","requestType"}) {
                if (!(row.get(field) instanceof String)) throw new IOException("The complete pending request list has an unsupported field.");
            }
            result.add(new Row((String)row.get("reference"),(String)row.get("project"),
                    (String)row.get("requester"),(String)row.get("requestType")));
        }
        return result;
    }

    private Snapshot goToFirst(Snapshot snapshot, int total) throws IOException {
        WebElement first = pageLink(1, total);
        if (first == null || !enabled(first)) {
            throw new IOException("Cannot return to the exact reference's pending page. No approval was submitted.");
        }
        first.click();
        return settledRows(snapshot.signature, true);
    }

    private Snapshot advance(Snapshot snapshot, int page, int total) throws IOException {
        WebElement link = pageLink(page, total);
        if (link == null || !enabled(link)) {
            throw new IOException("Cannot reach pending page " + page + " of " + total + ". No approval was submitted.");
        }
        link.click();
        return settledRows(snapshot.signature, true);
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public List<Row> searchReference(String reference) throws IOException {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IOException("The pending reference must be non-empty.");
        }
        return scanFiltered(reference.trim(), message -> { }, false, reference.trim());
    }

    public static boolean matchesSearch(Row row, String search) throws IOException {
        String folded = normalize(row.text);
        for (String term : parseTerms(search, true)) {
            boolean matched = term.matches("tk[0-9]+")
                    ? ReferenceMatcher.sameType(term, row.requestType) : folded.contains(term);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    public static boolean sameReference(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    public WebElement findExactRow(String reference) throws IOException {
        WebElement found = null;
        for (WebElement row : driver.findElements(ROWS)) {
            if (row.isDisplayed() && sameReference(reference, row.findElement(REF).getText())) {
                if (found != null) {
                    throw new IOException("More than one pending row has reference " + reference + ". No approval was submitted.");
                }
                found = row;
            }
        }
        if (found == null) {
            throw new IOException("The exact pending reference " + reference + " is no longer available.");
        }
        return found;
    }

    public void selectExactReference(String reference) throws IOException {
        for (WebElement checkbox : driver.findElements(CHECKBOX)) {
            if (checkbox.isDisplayed() && checkbox.isSelected()) {
                checkbox.click();
            }
        }
        WebElement checkbox = findExactRow(reference).findElement(CHECKBOX);
        if (!checkbox.isSelected()) {
            checkbox.click();
        }
        verifyOnlySelectedReference(reference);
    }

    public void verifyOnlySelectedReference(String reference) throws IOException {
        int selected = 0;
        for (WebElement checkbox : driver.findElements(CHECKBOX)) {
            if (checkbox.isSelected()) {
                selected++;
                WebElement row = checkbox.findElement(By.xpath("ancestor::tr[1]"));
                if (!sameReference(reference, row.findElement(REF).getText())) {
                    throw new IOException("A different pending reference is selected. No approval was submitted.");
                }
            }
        }
        if (selected != 1) {
            throw new IOException("Expected exactly one selected pending reference. No approval was submitted.");
        }
    }

    /** No retry: a lost acknowledgement after paOkModal may already represent a successful server write. */
    public void submitAndConfirm(String description) throws IOException {
        try {
            ApprovalDialog approval = wait.until(d -> {
                try {
                    return approvalDialog();
                } catch (StaleElementReferenceException ex) {
                    return null;
                }
            });
            if (visible(SUCCESS) != null || visible(SUCCESS_ICON) != null) {
                throw new IOException("An earlier response dialog is still open. Close it in PROMS before retrying.");
            }
            approval.comment.clear();
            approval.comment.sendKeys(description == null ? "" : description);
            ApprovalDialog current = approvalDialog();
            if (current == null || !current.container.equals(approval.container)
                    || !current.comment.equals(approval.comment) || !current.confirm.equals(approval.confirm)) {
                throw new IOException("The approval dialog changed before submission. No approval was submitted.");
            }
            current.confirm.click();
            WebElement acknowledgement = wait.until(d -> {
                try {
                    checkErrors();
                    if (visible(BUSY) != null || visible(COMMENT) != null) {
                        return null;
                    }
                    return acknowledgement();
                } catch (StaleElementReferenceException ex) {
                    // The server response replaces the comment modal asynchronously; only reread, never resubmit.
                    return null;
                }
            });
            acknowledgement.click();
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("PROMS did not provide a confirmed approval response. Check the reference in PROMS before retrying; no automatic retry was made.", ex);
        }
    }

    /** Bind both input and action to one logical modal, allowing nested Bootstrap modal-content. */
    private ApprovalDialog approvalDialog() {
        List<WebElement> comments = visibleElements(driver, COMMENT);
        if (comments.isEmpty()) {
            return null;
        }
        if (comments.size() != 1) {
            throw new IllegalStateException("Multiple visible approval comments are present. No approval was submitted.");
        }
        WebElement container = singleVisibleDialog();
        if (container == null) {
            throw new IllegalStateException("The approval comment is outside a recognized dialog. No approval was submitted.");
        }
        List<WebElement> scopedComments = visibleElements(container, COMMENT);
        List<WebElement> scopedConfirms = visibleElements(container, CONFIRM_IN_DIALOG);
        List<WebElement> confirms = visibleElements(driver, CONFIRM);
        if (scopedComments.size() != 1 || !scopedComments.get(0).equals(comments.get(0))
                || scopedConfirms.size() > 1 || confirms.size() > 1) {
            throw new IllegalStateException("The visible approval dialog is ambiguous. No approval was submitted.");
        }
        if (confirms.isEmpty()) {
            return null;
        }
        if (scopedConfirms.size() != 1 || !scopedConfirms.get(0).equals(confirms.get(0))) {
            throw new IllegalStateException("The approval confirmation is outside its comment dialog. No approval was submitted.");
        }
        if (!confirms.get(0).isEnabled()) {
            return null;
        }
        return new ApprovalDialog(container, comments.get(0), confirms.get(0));
    }

    private WebElement acknowledgement() {
        List<WebElement> buttons = visibleElements(driver, SUCCESS);
        List<WebElement> icons = visibleElements(driver, SUCCESS_ICON);
        if (buttons.isEmpty() && icons.isEmpty()) {
            return null;
        }
        WebElement container = singleVisibleDialog();
        if (container == null || buttons.size() > 1 || icons.size() > 1) {
            throw new IllegalStateException("The approval response is ambiguous. Check PROMS before retrying.");
        }
        if (!buttons.isEmpty()) {
            List<WebElement> scoped = visibleElements(container, SUCCESS_IN_DIALOG);
            if (scoped.size() != 1 || !scoped.get(0).equals(buttons.get(0))) {
                throw new IllegalStateException("The success acknowledgement is outside the response dialog.");
            }
            return buttons.get(0).isEnabled() ? buttons.get(0) : null;
        }
        List<WebElement> scopedIcons = visibleElements(container, SUCCESS_ICON);
        List<WebElement> confirms = visibleElements(container, By.cssSelector("button.swal2-confirm"));
        if (scopedIcons.size() != 1 || confirms.size() != 1) {
            throw new IllegalStateException("The success response has no unique acknowledgement button.");
        }
        return confirms.get(0).isEnabled() ? confirms.get(0) : null;
    }

    private WebElement singleVisibleDialog() {
        List<WebElement> visible = visibleElements(driver, DIALOGS);
        List<WebElement> outermost = new ArrayList<>();
        for (WebElement dialog : visible) {
            boolean nested = false;
            for (WebElement ancestor : dialog.findElements(ANCESTOR_DIALOGS)) {
                if (visible.contains(ancestor)) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                outermost.add(dialog);
            }
        }
        if (outermost.size() > 1) {
            throw new IllegalStateException("Multiple visible approval or response dialogs are present. Check PROMS before retrying.");
        }
        return outermost.isEmpty() ? null : outermost.get(0);
    }

    private static List<WebElement> visibleElements(org.openqa.selenium.SearchContext context, By selector) {
        List<WebElement> result = new ArrayList<>();
        for (WebElement element : context.findElements(selector)) {
            if (element.isDisplayed()) {
                result.add(element);
            }
        }
        return result;
    }

    /** Count success only after the server response AND a fresh pending-list search excludes this exact reference. */
    public void verifyNoLongerPending(String pendingUrl, String reference) throws IOException {
        validatePendingUrl(pendingUrl);
        driver.get(pendingUrl);
        for (Row row : searchReference(reference)) {
            if (sameReference(reference, row.reference)) {
                throw new IOException("PROMS still lists " + reference + " as pending after its response. Check it before retrying.");
            }
        }
    }

    private static String[] parseTerms(String search, boolean folded) throws IOException {
        String[] terms = (search == null ? "" : search).split("&&", -1);
        if (terms.length > 4) {
            throw new IOException("Search supports at most four terms separated by &&.");
        }
        for (int i = 0; i < terms.length; i++) {
            terms[i] = folded ? normalize(terms[i]) : terms[i].trim();
            if (terms[i].isEmpty()) {
                throw new IOException("Every Search term must be non-empty. Nothing was approved.");
            }
        }
        return terms;
    }

    private static void validatePendingUrl(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            boolean portal = "https".equalsIgnoreCase(uri.getScheme())
                    && "proms.truecorp.co.th".equalsIgnoreCase(host)
                    && "/proms/pendingapprove".equals(uri.getPath());
            portal = portal || ("http".equalsIgnoreCase(uri.getScheme())
                    && "promsweb.true.th".equalsIgnoreCase(host)
                    && "/proms/pendingapprove".equals(uri.getPath()));
            if ((!portal && !loopback) || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Unexpected portal URL");
            }
        } catch (RuntimeException ex) {
            throw new IOException("The approval workflow URL must be the PROMS pending page or a local test fixture.", ex);
        }
    }

    private static String normalize(String value) {
        return (value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
    }

    private Snapshot filter(String value) throws IOException {
        try {
            WebElement input = wait.until(ExpectedConditions.elementToBeClickable(SEARCH));
            long bootstrapGeneration = PromsPageActivity.begin(driver, value);
            // Let the existing pending load settle before any clear/type response can be observed as this filter.
            wait.until(d -> PromsPageActivity.idle(driver, bootstrapGeneration));
            settledRows(null, false);
            responseGeneration = PromsPageActivity.begin(driver, value);
            input.clear();
            input.sendKeys(value);
            PromsPageActivity.submit(driver, responseGeneration, input);
            input.sendKeys(Keys.ENTER);
            Snapshot snapshot = settledRows(null, false, responseGeneration);
            int current = currentPage();
            if (current != 1) {
                WebElement first = pageLink(1, pageCount());
                if ((first == null || !enabled(first)) && current > 1) {
                    throw new IOException("Cannot return to the first pending page after searching.");
                }
                if (first != null && enabled(first)) {
                    String previous = snapshot.signature;
                    first.click();
                    snapshot = settledRows(previous, current > 1);
                }
            }
            return snapshot;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("PROMS did not finish loading the requested pending list. Check the page and log before retrying.", ex);
        }
    }

    /** Angular may reuse tr nodes: the actual rows must change and settle after page navigation. */
    private Snapshot settledRows(String previous, boolean requireChange) {
        return settledRows(previous, requireChange, null);
    }

    private Snapshot settledRows(String previous, boolean requireChange, Long responseGeneration) {
        final long started = System.nanoTime();
        final String[] last = {null};
        final long[] stableSince = {started};
        return wait.until(d -> {
            try {
                checkErrors();
                if (visible(BUSY) != null) {
                    last[0] = null;
                    return null;
                }
                if (responseGeneration != null && !PromsPageActivity.responseComplete(driver, responseGeneration)) {
                    last[0] = null;
                    return null;
                }
                Snapshot candidate = snapshot();
                if (requireChange && candidate.signature.equals(previous)) {
                    last[0] = null;
                    return null;
                }
                long now = System.nanoTime();
                if (!candidate.signature.equals(last[0])) {
                    last[0] = candidate.signature;
                    stableSince[0] = now;
                }
                if (now - started < minimumSettleNanos || now - stableSince[0] < STABLE_NANOS) {
                    return null;
                }
                return candidate;
            } catch (StaleElementReferenceException ex) {
                last[0] = null;
                return null;
            }
        });
    }

    private Snapshot snapshot() {
        List<Row> rows = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (WebElement element : driver.findElements(ROWS)) {
            if (!element.isDisplayed()) {
                continue;
            }
            String reference = element.findElement(REF).getText().trim();
            if (reference.isEmpty()) {
                continue;
            }
            String project = childText(element, By.cssSelector("td[data-title-text='Project Name']"));
            String requester = childText(element, By.cssSelector("td[data-title-text='Requester']"));
            String type = childText(element, By.xpath(".//td[@title=\"'Request Type'\"]//div"));
            Row row = new Row(reference, project, requester, type);
            rows.add(row);
            signature.append(row.text).append('\n');
        }
        return new Snapshot(rows, signature.toString());
    }

    private static String childText(WebElement row, By selector) {
        List<WebElement> elements = row.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText().trim();
    }

    private int pageCount() {
        int maximum = 1;
        for (WebElement link : driver.findElements(By.cssSelector("a[ng-switch-when='last'], a[ng-switch-when='page']"))) {
            if (link.isDisplayed()) {
                maximum = Math.max(maximum, pageNumber(link.getText()));
            }
        }
        return maximum;
    }

    private static int pageNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private int currentPage() {
        for (WebElement link : driver.findElements(By.cssSelector("li.active a[ng-switch-when], a[ng-switch-when][aria-current='page']"))) {
            if (link.isDisplayed()) {
                return pageNumber(link.getText());
            }
        }
        return 0;
    }

    private WebElement pageLink(int page, int total) {
        for (WebElement link : driver.findElements(By.cssSelector("a[ng-switch-when='page']"))) {
            if (link.isDisplayed() && pageNumber(link.getText()) == page && enabled(link)) {
                return link;
            }
        }
        if (page == 1) {
            return visible(By.cssSelector("a[ng-switch-when='first']"));
        }
        WebElement next = visible(By.cssSelector("a[ng-switch-when='next']"));
        if (next != null && enabled(next)) {
            return next;
        }
        return page == total ? visible(By.cssSelector("a[ng-switch-when='last']")) : null;
    }

    private static boolean enabled(WebElement element) {
        if (!element.isEnabled() || "true".equalsIgnoreCase(element.getAttribute("aria-disabled"))) {
            return false;
        }
        String own = " " + element.getAttribute("class") + " ";
        String parent = " " + element.findElement(By.xpath("..")).getAttribute("class") + " ";
        return !own.contains(" disabled ") && !parent.contains(" disabled ");
    }

    private WebElement visible(By locator) {
        for (WebElement element : driver.findElements(locator)) {
            if (element.isDisplayed()) {
                return element;
            }
        }
        return null;
    }

    private void checkErrors() {
        WebElement error = visible(ERRORS);
        if (error != null && (!error.getText().trim().isEmpty()
                || error.getAttribute("class").contains("swal2-error"))) {
            throw new IllegalStateException("PROMS reported an error: " + error.getText().trim());
        }
    }

    public static final class Row {
        private final String reference;
        private final String project;
        private final String requester;
        private final String requestType;
        private final String text;
        private Row(String reference, String project, String requester, String requestType) {
            this.reference = reference;
            this.project = project;
            this.requester = requester;
            this.requestType = requestType;
            this.text = reference + " " + project + " " + requester + " " + requestType;
        }
        public String getReference() { return reference; }
        public String getProject() { return project; }
        public String getRequester() { return requester; }
        public String getRequestType() { return requestType; }
        public String getText() { return text; }
    }

    private static final class Snapshot {
        private final List<Row> rows;
        private final String signature;
        private Snapshot(List<Row> rows, String signature) {
            this.rows = rows;
            this.signature = signature;
        }
    }

    private static final class ApprovalDialog {
        private final WebElement container;
        private final WebElement comment;
        private final WebElement confirm;
        private ApprovalDialog(WebElement container, WebElement comment, WebElement confirm) {
            this.container = container;
            this.comment = comment;
            this.confirm = confirm;
        }
    }
}
