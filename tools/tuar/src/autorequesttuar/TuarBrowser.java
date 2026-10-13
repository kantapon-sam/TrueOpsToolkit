package autorequesttuar;

import java.nio.CharBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.UnhandledAlertException;
import org.openqa.selenium.UnexpectedAlertBehaviour;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

final class TuarBrowser {

    private static final int WAIT_SECONDS = 25;
    private static final int MAX_FILL_ATTEMPTS = 3;
    private static final long FORM_STABILITY_MILLIS = 1500L;
    private static final long AJAX_IDLE_STABILITY_MILLIS = 1000L;
    private static final String SUBMIT_DISABLED_PROPERTY =
            "autorequesttuar.submit.disabled";
    private static final String HEADLESS_PROPERTY =
            "autorequesttuar.browser.headless";

    private volatile WebDriver driver;
    private final Object driverLifecycleLock = new Object();
    private volatile boolean shuttingDown;
    private String baseUrl;
    private boolean headless = Boolean.getBoolean(HEADLESS_PROPERTY);

    synchronized void setHeadless(boolean value) {
        if (headless != value) {
            close();
            headless = value;
        }
    }

    synchronized FormOptions loginAndLoadOptions(
            String serverIp,
            String username,
            char[] password,
            String loginType,
            Consumer<String> status) throws Exception {
        baseUrl = TuarServer.baseUrl(serverIp);
        ensureDriver(status);
        status.accept("Opening TUAR " + baseUrl + "/login...");
        driver.get(baseUrl + "/login");

        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        WebElement usernameElement = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("username")));
        WebElement passwordElement = driver.findElement(By.id("password"));

        usernameElement.clear();
        usernameElement.sendKeys(username);
        passwordElement.clear();
        passwordElement.sendKeys(CharBuffer.wrap(password));

        String buttonId = "CLLS".equalsIgnoreCase(loginType)
                ? "btnCLLS" : "btnHR";
        status.accept("Submitting " + loginType + " login...");
        driver.findElement(By.id(buttonId)).click();

        wait.until(webDriver -> {
            String url = webDriver.getCurrentUrl();
            return url != null && !url.contains("/login");
        });
        if (driver.getCurrentUrl().contains("/login")) {
            throw new IllegalStateException(
                    "TUAR is still on the Login page. Check the username, "
                    + "password, and login type.");
        }

        status.accept("Login succeeded. Opening New Request...");
        driver.get(newRequestUrl());
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.id("requestTypeId")));
        status.accept("Waiting for New Request dropdowns...");
        waitForOptionCount("requestTypeId", 2);
        waitForOptionCount("applicationId", 2);
        waitForOptionCount("purposeId", 2);
        waitForOptionCount("typeofAccount", 2);

        List<OptionItem> requestTypes = readOptions("requestTypeId");
        List<OptionItem> services = readOptions("applicationId");
        List<OptionItem> purposes = readOptions("purposeId");
        List<OptionItem> accountTypes = readOptions("typeofAccount");
        status.accept("Loading Request for Vendor Purpose options...");
        setCheckbox("addIsVendor", true);
        waitForSelectOptionsStable("purposeId", 2);
        purposes = mergeOptionsByLabel(
                purposes, readOptions("purposeId"));
        setCheckbox("addIsVendor", false);
        waitForSelectOptionsStable("purposeId", 2);
        status.accept("Loading dependent Expiry options...");
        selectByValue(
                "typeofAccount",
                requiredOption(accountTypes, "2", "Type Of Account"));
        waitForOptionCount("expiry", 2);
        List<OptionItem> expiryOptions = readOptions("expiry");

        return new FormOptions(
                requestTypes,
                services,
                purposes,
                accountTypes,
                expiryOptions);
    }

    synchronized void fillNewRequest(
            FormData data, Consumer<String> status) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_FILL_ATTEMPTS; attempt++) {
            ensureRunning();
            try {
                fillNewRequestOnce(data, status);
                return;
            } catch (RuntimeException ex) {
                String alertMessage = null;
                try {
                    alertMessage = acceptPendingAlert();
                } catch (RuntimeException alertAccessFailure) {
                    alertMessage =
                            attachmentMessage(alertAccessFailure);
                }
                if (alertMessage == null) {
                    alertMessage = attachmentMessage(ex);
                }
                if (requiresAttachment(alertMessage)) {
                    throw new AttachmentRequiredException(
                            data.workbookRow,
                            data.service.getLabel(),
                            alertMessage.trim());
                }
                lastFailure = ex;
                if (!isRetryableBeforeSubmit(ex)
                        || attempt == MAX_FILL_ATTEMPTS) {
                    throw ex;
                }
                status.accept(
                        "TUAR refreshed the form while processing Excel row "
                        + data.workbookRow + ". Retrying before SUBMIT ("
                        + (attempt + 1) + "/" + MAX_FILL_ATTEMPTS + ")...");
            }
        }
        throw lastFailure;
    }

    private void fillNewRequestOnce(
            FormData data, Consumer<String> status) {
        try {
            prepareNewRequest();
        } catch (RuntimeException ex) {
            throw stageError(data, "opening New Request", ex);
        }

        try {
            status.accept("Selecting Request Type and Service...");
            selectByValue("requestTypeId", data.requestType);
            waitForAjax();
            WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
            wait.until(webDriver -> hasOptionValue(
                    "applicationId", data.service.getValue()));
            String serviceMessage = selectService(data.service);
            handleServiceMessage(data, serviceMessage, status);
            setCheckbox("addIsVendor", data.requestForVendor);
            waitForAjax();
            if (data.attachFile != null) {
                fillFile(
                        "file",
                        data.attachFile.toAbsolutePath().toString());
                status.accept("Attachment selected for Excel row "
                        + data.workbookRow + ".");
            }
        } catch (AttachmentRequiredException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw stageError(data, "selecting Request Type / Service", ex);
        }

        try {
            fillText("subject", data.subject);
            fillText("detail", data.detail);
        } catch (RuntimeException ex) {
            throw stageError(data, "filling Subject / Detail", ex);
        }

        try {
            status.accept(
                    "Selecting Purpose, Type Of Account and Expiry...");
            waitForSelectOptionsStable("purposeId", 2);
            if (!hasOptionLabel(
                    "purposeId", data.purpose.getLabel())) {
                throw new IllegalArgumentException(
                        "Purpose \"" + data.purpose.getLabel()
                        + "\" is not available when request_for_vendor is "
                        + (data.requestForVendor ? "Y" : "N")
                        + ". Choose a Purpose shown by TUAR for that "
                        + "Request for Vendor setting.");
            }
            selectByLabel("purposeId", data.purpose);
            waitForAjax();
            selectByValue("typeofAccount", data.accountType);
            waitForAjax();
            if (data.expiry != null) {
                WebDriverWait wait =
                        new WebDriverWait(driver, WAIT_SECONDS);
                wait.until(webDriver -> hasOptionValue(
                        "expiry", data.expiry.getValue()));
                selectByValue("expiry", data.expiry);
                waitForAjax();
            }
        } catch (RuntimeException ex) {
            throw stageError(
                    data,
                    "selecting Type Of Account / Expiry / Purpose",
                    ex);
        }

        try {
            fillText("contactNo", data.contactNumber);
            setRequireDate(data.requireDate);
            fillText("userNo", data.userAmount);

            ((JavascriptExecutor) driver).executeScript(
                    "var e=document.getElementById('btnSubmit');"
                    + "if(e){e.scrollIntoView({block:'center'});}");
        } catch (RuntimeException ex) {
            throw stageError(
                    data,
                    "filling Vendor / Contact / Date / User Amount / File",
                    ex);
        }
        status.accept(
                "Waiting for TUAR to retain every request field...");
        waitForAjax();
        waitForStableForm(data);
        status.accept(
                "All request fields were verified and remained stable.");
    }

    synchronized BatchResult submitBatch(
            List<FormData> requests, Consumer<String> status) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException(
                    "No enabled requests were found in requests.xlsx.");
        }

        int total = requests.size();
        int submittedCount = 0;
        List<BatchResult.SkippedRequest> skipped = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            ensureRunning();
            FormData request = requests.get(index);
            status.accept("Processing request " + (index + 1) + "/" + total
                    + " (Excel row " + request.workbookRow + ")");
            if (request.attachmentProblem != null) {
                skipped.add(new BatchResult.SkippedRequest(
                        request.workbookRow,
                        request.service.getLabel(),
                        request.attachmentProblem));
                status.accept(
                        "Skipped Excel row " + request.workbookRow
                        + ": " + request.attachmentProblem);
                continue;
            }
            try {
                fillNewRequest(request, status);
                if (Boolean.getBoolean(
                        SUBMIT_DISABLED_PROPERTY)) {
                    String reason = "Dry run completed: every field was "
                            + "verified, and SUBMIT was not clicked.";
                    skipped.add(new BatchResult.SkippedRequest(
                            request.workbookRow,
                            request.service.getLabel(),
                            reason));
                    status.accept(
                            "Dry run completed for Excel row "
                            + request.workbookRow
                            + ". SUBMIT was not clicked.");
                    continue;
                }
                submitCurrentRequest(request);
            } catch (AttachmentRequiredException ex) {
                skipped.add(new BatchResult.SkippedRequest(
                        ex.workbookRow,
                        ex.service,
                        ex.alertText));
                status.accept(
                        "Skipped Excel row " + ex.workbookRow
                        + ": Service requires an attachment.");
                continue;
            }
            submittedCount++;
            status.accept("Request " + (index + 1) + "/" + total
                    + " submitted successfully.");
        }
        return new BatchResult(submittedCount, skipped);
    }

    void close() {
        closeOwnedDriver(false);
    }

    /** Stops this browser without waiting for a synchronized batch to finish. */
    void shutdown() {
        closeOwnedDriver(true);
    }

    private void closeOwnedDriver(boolean permanent) {
        synchronized (driverLifecycleLock) {
            if (permanent) {
                shuttingDown = true;
            }
            WebDriver ownedDriver = driver;
            driver = null;
            if (ownedDriver != null) {
                try {
                    ownedDriver.quit();
                } catch (RuntimeException ignored) {
                    // Chrome may already have been closed by the user.
                }
            }
        }
    }

    private void ensureRunning() {
        if (shuttingDown) {
            throw new IllegalStateException("TUAR has been stopped.");
        }
    }

    private void prepareNewRequest() {
        ensureRunning();
        if (driver == null) {
            throw new IllegalStateException(
                    "Chrome is not ready. Log in again.");
        }
        // TUAR rebuilds dependent dropdowns with AJAX. A fresh page for every
        // row prevents elements left over from the previous request from being
        // reused after their DOM nodes have been replaced.
        driver.get(newRequestUrl());
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.id("requestTypeId")));
    }

    private String newRequestUrl() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "TUAR IP has not been configured. Log in again.");
        }
        return baseUrl + "/newrequest";
    }

    private void submitCurrentRequest(FormData request) {
        ensureRunning();
        try {
            WebElement submit = driver.findElement(By.id("btnSubmit"));
            ensureRunning();
            submit.click();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Excel row " + request.workbookRow
                    + ": TUAR changed the SUBMIT button before it could "
                    + "be clicked. This row was not retried automatically "
                    + "to prevent a duplicate request.",
                    ex);
        }

        String submitMessage = waitForAlertAndAccept(2);
        if (requiresAttachment(submitMessage)) {
            throw new AttachmentRequiredException(
                    request.workbookRow,
                    request.service.getLabel(),
                    submitMessage.trim());
        }

        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        try {
            wait.until(webDriver -> submissionFinished(webDriver));
        } catch (TimeoutException ex) {
            throw new IllegalStateException(
                    "Excel row " + request.workbookRow
                    + ": no result was detected after SUBMIT. "
                    + "Check the validation messages in Chrome.",
                    ex);
        }
        if (driver.getCurrentUrl().contains("/login")) {
            throw new IllegalStateException(
                    "The session expired during Excel processing. "
                    + "Log in again.");
        }
    }

    private boolean submissionFinished(WebDriver webDriver) {
        try {
            String url = webDriver.getCurrentUrl();
            if (url == null || !url.contains("/newrequest")) {
                return true;
            }
            List<WebElement> successes = webDriver.findElements(
                    By.cssSelector(".alert-success"));
            for (WebElement success : successes) {
                if (success.isDisplayed()) {
                    return true;
                }
            }
            List<WebElement> subjects =
                    webDriver.findElements(By.id("subject"));
            return subjects.isEmpty()
                    || subjects.get(0).getAttribute("value")
                            .trim().isEmpty();
        } catch (StaleElementReferenceException
                | NoSuchElementException ex) {
            return false;
        }
    }

    private void ensureDriver(Consumer<String> status) throws Exception {
        ensureRunning();
        if (driver != null) {
            try {
                driver.getWindowHandle();
                return;
            } catch (RuntimeException ex) {
                close();
            }
        }

        ensureRunning();
        Path driverPath = ChromeDriverManager.prepare(status);
        System.setProperty("webdriver.chrome.driver",
                driverPath.toAbsolutePath().toString());

        ChromeOptions options = chromeOptions(headless);

        status.accept("Starting Chrome...");
        synchronized (driverLifecycleLock) {
            ensureRunning();
            // Register creation under the lifecycle lock, so shutdown cannot
            // miss a browser while its constructor is still starting Chrome.
            driver = new ChromeDriver(options);
        }
        driver.manage().timeouts().implicitlyWait(0, TimeUnit.SECONDS);
        driver.manage().window().maximize();
    }

    static ChromeOptions chromeOptions() {
        return chromeOptions(Boolean.getBoolean(HEADLESS_PROPERTY));
    }

    private static ChromeOptions chromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-save-password-bubble");
        options.addArguments(
                "--disable-features=PasswordManagerOnboarding,"
                + "PasswordLeakDetection");
        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
        }
        options.setExperimentalOption(
                "excludeSwitches",
                java.util.Collections.singletonList("enable-automation"));
        options.setUnhandledPromptBehaviour(
                UnexpectedAlertBehaviour.IGNORE);

        Map<String, Object> preferences = new HashMap<>();
        preferences.put("credentials_enable_service", false);
        preferences.put("profile.password_manager_enabled", false);
        preferences.put("profile.password_manager_leak_detection", false);
        preferences.put("autofill.profile_enabled", false);
        options.setExperimentalOption("prefs", preferences);
        return options;
    }

    private List<OptionItem> readOptions(String selectId) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        return wait.until(webDriver -> {
            Object rows = ((JavascriptExecutor) webDriver).executeScript(
                    "var e=document.getElementById(arguments[0]);"
                    + "if(!e){return null;}"
                    + "return Array.prototype.map.call(e.options,"
                    + "function(o){return [o.value,o.text];});",
                    selectId);
            if (!(rows instanceof List)) {
                return null;
            }
            List<OptionItem> result = new ArrayList<>();
            for (Object rowObject : (List<?>) rows) {
                if (!(rowObject instanceof List)) {
                    continue;
                }
                List<?> row = (List<?>) rowObject;
                if (row.size() >= 2) {
                    result.add(new OptionItem(
                            String.valueOf(row.get(0)),
                            String.valueOf(row.get(1))));
                }
            }
            return result;
        });
    }

    private void waitForOptionCount(String selectId, int minimum) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            Object count = ((JavascriptExecutor) webDriver).executeScript(
                    "var e=document.getElementById(arguments[0]);"
                    + "return e ? e.options.length : 0;",
                    selectId);
            return count instanceof Number
                    && ((Number) count).intValue() >= minimum;
        });
    }

    private void waitForSelectOptionsStable(
            String selectId, int minimum) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        final AtomicReference<String> previousSignature =
                new AtomicReference<String>();
        final long[] stableSince = new long[]{-1L};
        wait.until(webDriver -> {
            Object raw = ((JavascriptExecutor) webDriver)
                    .executeScript(
                            "var e=document.getElementById(arguments[0]);"
                            + "if(!e||e.options.length<arguments[1]){"
                            + "return null;}"
                            + "return Array.prototype.map.call("
                            + "e.options,function(o){"
                            + "return String(o.value)+'='+String(o.text);"
                            + "}).join('\\u001f');",
                            selectId,
                            minimum);
            if (!(raw instanceof String)) {
                previousSignature.set(null);
                stableSince[0] = -1L;
                return false;
            }
            String signature = (String) raw;
            long now = System.currentTimeMillis();
            if (!signature.equals(previousSignature.get())) {
                previousSignature.set(signature);
                stableSince[0] = now;
                return false;
            }
            return now - stableSince[0]
                    >= AJAX_IDLE_STABILITY_MILLIS;
        });
    }

    private static OptionItem requiredOption(
            List<OptionItem> options,
            String value,
            String name) {
        for (OptionItem option : options) {
            if (value.equals(option.getValue())) {
                return option;
            }
        }
        throw new IllegalStateException(
                name + " option value " + value
                + " was not found on the TUAR page.");
    }

    private static List<OptionItem> mergeOptionsByLabel(
            List<OptionItem> first, List<OptionItem> second) {
        List<OptionItem> result = new ArrayList<>();
        addUniqueOptions(result, first);
        addUniqueOptions(result, second);
        return result;
    }

    private static void addUniqueOptions(
            List<OptionItem> target, List<OptionItem> source) {
        for (OptionItem candidate : source) {
            boolean found = false;
            for (OptionItem existing : target) {
                if (existing.getLabel().equals(candidate.getLabel())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                target.add(candidate);
            }
        }
    }

    private void selectByValue(String selectId, OptionItem item) {
        if (item == null) {
            return;
        }
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            Object selected = ((JavascriptExecutor) webDriver)
                    .executeScript(
                            "var e=document.getElementById(arguments[0]);"
                            + "if(!e){return false;}"
                            + "var value=String(arguments[1]);"
                            + "var found=false;"
                            + "for(var i=0;i<e.options.length;i++){"
                            + "if(String(e.options[i].value)===value){"
                            + "found=true;break;}}"
                            + "if(!found){return false;}"
                            + "e.value=value;"
                            + "e.dispatchEvent(new Event("
                            + "'input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event("
                            + "'change',{bubbles:true}));"
                            + "return true;",
                            selectId,
                            item.getValue());
            return Boolean.TRUE.equals(selected);
        });
    }

    private void selectByLabel(String selectId, OptionItem item) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            Object selected = ((JavascriptExecutor) webDriver)
                    .executeScript(
                            "var e=document.getElementById(arguments[0]);"
                            + "if(!e){return false;}"
                            + "var label=String(arguments[1]);"
                            + "var value=null;"
                            + "for(var i=0;i<e.options.length;i++){"
                            + "if(String(e.options[i].text)===label){"
                            + "value=String(e.options[i].value);break;}}"
                            + "if(value===null){return false;}"
                            + "e.value=value;"
                            + "e.dispatchEvent(new Event("
                            + "'input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event("
                            + "'change',{bubbles:true}));"
                            + "return true;",
                            selectId,
                            item.getLabel());
            return Boolean.TRUE.equals(selected);
        });
    }

    private boolean hasOptionLabel(
            String selectId, String label) {
        Object found = ((JavascriptExecutor) driver).executeScript(
                "var e=document.getElementById(arguments[0]);"
                + "if(!e){return false;}"
                + "for(var i=0;i<e.options.length;i++){"
                + "if(String(e.options[i].text)"
                + "===String(arguments[1])){return true;}}"
                + "return false;",
                selectId,
                label);
        return Boolean.TRUE.equals(found);
    }

    private String selectService(OptionItem service) {
        String message = null;
        try {
            selectByValue("applicationId", service);
            message = acceptPendingAlert();
            if (message == null) {
                try {
                    waitForAjax();
                } catch (UnhandledAlertException ex) {
                    message = ex.getAlertText();
                }
            }
        } catch (UnhandledAlertException ex) {
            message = ex.getAlertText();
        }

        String accepted = acceptPendingAlert();
        if (accepted != null) {
            return accepted;
        }
        if (message != null) {
            return message;
        }
        return waitForAlertAndAccept(2);
    }

    private void handleServiceMessage(
            FormData data,
            String message,
            Consumer<String> status) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        status.accept("TUAR Service message: " + message.trim());
        if (requiresAttachment(message)) {
            if (data.attachFile != null) {
                status.accept(
                        "This Service requires an attachment. "
                        + "Continuing with "
                        + data.attachFile.getFileName() + ".");
                return;
            }
            throw new AttachmentRequiredException(
                    data.workbookRow,
                    data.service.getLabel(),
                    message.trim());
        }
        throw new IllegalStateException(
                "Excel row " + data.workbookRow
                + ": TUAR displayed this message after selecting Service: "
                + message.trim());
    }

    private String acceptPendingAlert() {
        try {
            Alert alert = driver.switchTo().alert();
            String text = alert.getText();
            alert.accept();
            return text;
        } catch (NoAlertPresentException ex) {
            return null;
        }
    }

    private String waitForAlertAndAccept(int seconds) {
        try {
            Alert alert = new WebDriverWait(driver, seconds)
                    .until(ExpectedConditions.alertIsPresent());
            String text = alert.getText();
            alert.accept();
            return text;
        } catch (TimeoutException ex) {
            return null;
        }
    }

    private static boolean requiresAttachment(String message) {
        return message != null
                && message.toLowerCase(java.util.Locale.ROOT)
                        .contains("attach");
    }

    static String attachmentMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnhandledAlertException) {
                String alertText =
                        ((UnhandledAlertException) current)
                                .getAlertText();
                if (requiresAttachment(alertText)) {
                    return alertText;
                }
            }
            String message = current.getMessage();
            if (requiresAttachment(message)) {
                return message;
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class AttachmentRequiredException
            extends RuntimeException {

        private final int workbookRow;
        private final String service;
        private final String alertText;

        AttachmentRequiredException(
                int workbookRow, String service, String alertText) {
            super("Excel row " + workbookRow
                    + " requires an attachment and was skipped.");
            this.workbookRow = workbookRow;
            this.service = service;
            this.alertText = alertText;
        }
    }

    private boolean hasOptionValue(String selectId, String value) {
        Object found = ((JavascriptExecutor) driver).executeScript(
                "var e=document.getElementById(arguments[0]);"
                + "if(!e){return false;}"
                + "var value=String(arguments[1]);"
                + "return Array.prototype.some.call(e.options,"
                + "function(o){return String(o.value)===value;});",
                selectId,
                value);
        return Boolean.TRUE.equals(found);
    }

    private void fillText(String id, String value) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            try {
                WebElement element = webDriver.findElement(By.id(id));
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return false;
                }
                element.clear();
                if (value != null && !value.isEmpty()) {
                    element.sendKeys(value);
                }
                return true;
            } catch (StaleElementReferenceException
                    | NoSuchElementException ex) {
                return false;
            }
        });
    }

    private void setCheckbox(String id, boolean checked) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            try {
                WebElement checkbox = webDriver.findElement(By.id(id));
                if (checkbox.isSelected() != checked) {
                    checkbox.click();
                }
                return true;
            } catch (StaleElementReferenceException
                    | NoSuchElementException ex) {
                return false;
            }
        });
    }

    private void fillFile(String id, String file) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            try {
                webDriver.findElement(By.id(id)).sendKeys(file);
                return true;
            } catch (StaleElementReferenceException
                    | NoSuchElementException ex) {
                return false;
            }
        });
    }

    private void waitForAjax() {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        final long[] idleSince = new long[]{-1L};
        wait.until(webDriver -> {
            try {
                Object idle = ((JavascriptExecutor) webDriver)
                        .executeScript(
                                "return !window.jQuery "
                                + "|| window.jQuery.active === 0;");
                if (!Boolean.TRUE.equals(idle)) {
                    idleSince[0] = -1L;
                    return false;
                }
                long now = System.currentTimeMillis();
                if (idleSince[0] < 0L) {
                    idleSince[0] = now;
                    return false;
                }
                return now - idleSince[0]
                        >= AJAX_IDLE_STABILITY_MILLIS;
            } catch (StaleElementReferenceException ex) {
                idleSince[0] = -1L;
                return false;
            }
        });
    }

    private static IllegalStateException stageError(
            FormData data, String stage, RuntimeException cause) {
        String detail = cause.getMessage();
        return new IllegalStateException(
                "Excel row " + data.workbookRow
                + ": failed while " + stage
                + (detail == null || detail.trim().isEmpty()
                        ? "." : ": " + detail),
                cause);
    }

    private static boolean isRetryableBeforeSubmit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StaleElementReferenceException
                    || current instanceof NoSuchElementException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void setRequireDate(String value) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        wait.until(webDriver -> {
            Object matches = ((JavascriptExecutor) webDriver)
                    .executeScript(
                            "var e=document.getElementById('daterequire');"
                            + "if(!e){return false;}"
                            + "e.removeAttribute('disabled');"
                            + "e.value=String(arguments[0]);"
                            + "e.dispatchEvent(new Event("
                            + "'input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event("
                            + "'change',{bubbles:true}));"
                            + "return e.value===String(arguments[0]);",
                            value);
            return Boolean.TRUE.equals(matches);
        });
    }

    private void waitForStableForm(FormData data) {
        WebDriverWait wait = new WebDriverWait(driver, WAIT_SECONDS);
        final long[] stableSince = new long[]{-1L};
        final AtomicReference<List<String>> lastProblems =
                new AtomicReference<List<String>>(
                        new ArrayList<String>());
        try {
            wait.until(webDriver -> {
                try {
                    List<String> problems =
                            formProblems(webDriver, data);
                    lastProblems.set(problems);
                    if (!problems.isEmpty()) {
                        stableSince[0] = -1L;
                        return false;
                    }
                    long now = System.currentTimeMillis();
                    if (stableSince[0] < 0L) {
                        stableSince[0] = now;
                        return false;
                    }
                    return now - stableSince[0]
                            >= FORM_STABILITY_MILLIS;
                } catch (StaleElementReferenceException
                        | NoSuchElementException ex) {
                    stableSince[0] = -1L;
                    return false;
                }
            });
        } catch (TimeoutException ex) {
            List<String> problems = lastProblems.get();
            String detail = problems.isEmpty()
                    ? "the form did not remain stable"
                    : String.join("; ", problems);
            throw new IllegalStateException(
                    "TUAR did not retain these form values: "
                    + detail,
                    ex);
        }
    }

    private List<String> formProblems(
            WebDriver webDriver, FormData data) {
        Object raw = ((JavascriptExecutor) webDriver)
                .executeScript(
                        "function value(id){"
                        + "var e=document.getElementById(id);"
                        + "return e ? String(e.value) : null;}"
                        + "function check(id,expected,label,problems){"
                        + "var actual=value(id);"
                        + "if(actual!==String(expected)){"
                        + "problems.push(label+' expected \"'"
                        + "+String(expected)+'\" but found \"'"
                        + "+String(actual)+'\"');}}"
                        + "var p=[];"
                        + "if(window.jQuery&&window.jQuery.active!==0){"
                        + "p.push('AJAX is still running');}"
                        + "check('requestTypeId',arguments[0],"
                        + "'Request Type',p);"
                        + "check('applicationId',arguments[1],"
                        + "'Service',p);"
                        + "check('subject',arguments[2],'Subject',p);"
                        + "check('detail',arguments[3],'Detail',p);"
                        + "var purpose=document.getElementById("
                        + "'purposeId');"
                        + "var purposeText=purpose"
                        + "&&purpose.selectedIndex>=0"
                        + "?String(purpose.options["
                        + "purpose.selectedIndex].text):null;"
                        + "if(purposeText!==String(arguments[4])){"
                        + "p.push('Purpose expected \"'"
                        + "+String(arguments[4])+'\" but found \"'"
                        + "+String(purposeText)+'\"');}"
                        + "check('typeofAccount',arguments[5],"
                        + "'Type Of Account',p);"
                        + "if(arguments[6]!==null){"
                        + "check('expiry',arguments[6],'Expiry',p);}"
                        + "check('contactNo',arguments[7],"
                        + "'Contact Number',p);"
                        + "check('daterequire',arguments[8],"
                        + "'Require Date',p);"
                        + "check('userNo',arguments[9],"
                        + "'User Amount',p);"
                        + "var vendor=document.getElementById("
                        + "'addIsVendor');"
                        + "if(!vendor"
                        + "||vendor.checked!==arguments[10]){"
                        + "p.push('Request for Vendor is incorrect');}"
                        + "if(arguments[11]){"
                        + "var file=document.getElementById('file');"
                        + "if(!file||!file.files"
                        + "||file.files.length===0){"
                        + "p.push('Attachment is not selected');}}"
                        + "var submit=document.getElementById("
                        + "'btnSubmit');"
                        + "if(!submit||submit.disabled){"
                        + "p.push('SUBMIT is not ready');}"
                        + "return p;",
                        data.requestType.getValue(),
                        data.service.getValue(),
                        data.subject,
                        data.detail,
                        data.purpose.getLabel(),
                        data.accountType.getValue(),
                        data.expiry == null
                                ? null
                                : data.expiry.getValue(),
                        data.contactNumber,
                        data.requireDate,
                        data.userAmount,
                        data.requestForVendor,
                        data.attachFile != null);
        List<String> problems = new ArrayList<>();
        if (raw instanceof List) {
            for (Object value : (List<?>) raw) {
                problems.add(String.valueOf(value));
            }
        } else {
            problems.add("form state could not be read");
        }
        return problems;
    }
}
