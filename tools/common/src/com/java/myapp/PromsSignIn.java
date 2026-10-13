package com.java.myapp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

/** Submits one automatic sign-in attempt; CAPTCHA continues in the user's Chrome window. */
public final class PromsSignIn {
    private static final By USERNAME = By.id("username");
    private static final By PASSWORD = By.id("password");
    private static final By SIGN_IN = By.cssSelector("#btn_signin, button[data-ng-click='login()'], button[ng-click='login()']");
    private static final By CAPTCHA = By.cssSelector("[id*='captcha' i], [name*='captcha' i], .g-recaptcha, .h-captcha, iframe[src*='recaptcha' i], iframe[src*='hcaptcha' i], [data-sitekey]");
    private static final By ERRORS = By.cssSelector(".alert-danger, .login-error, #loginError");

    private PromsSignIn() { }

    public static void signIn(WebDriver driver,String loginUrl,
            String username,String password,int seconds,Consumer<String> progress) throws IOException {
        boolean clickAttempted = false;
        try {
            driver.get(loginUrl);
            driver.manage().window().maximize();
            WebDriverWait wait = new WebDriverWait(driver, seconds);
            wait.pollingEvery(100, TimeUnit.MILLISECONDS);
            WebElement user = wait.until(ExpectedConditions.visibilityOfElementLocated(USERNAME));
            WebElement secret = wait.until(ExpectedConditions.visibilityOfElementLocated(PASSWORD));
            user.clear();
            user.sendKeys(username);
            secret.clear();
            secret.sendKeys(password);
            WebElement submit = wait.until(browser -> {
                try {
                    List<WebElement> buttons = visible(browser, SIGN_IN);
                    if (buttons.size() > 1) throw new AmbiguousSignInException();
                    return buttons.size() == 1 && buttons.get(0).isEnabled() ? buttons.get(0) : null;
                } catch (StaleElementReferenceException changed) { return null; }
            });
            progress.accept("Signing in automatically...");
            clickAttempted = true;
            submit.click();
            final boolean[] captchaShown = {false}, errorShown = {false};
            wait.until(browser -> {
                try {
                    String path = URI.create(browser.getCurrentUrl()).getPath();
                    if (path != null && !path.startsWith("/proms/login") && visible(browser, USERNAME).isEmpty()) return true;
                    if (!visible(browser, CAPTCHA).isEmpty() && !captchaShown[0]) {
                        captchaShown[0] = true;
                        progress.accept("Complete CAPTCHA in the Chrome window, then click Sign in. Your account is already filled; no automatic retry will be made.");
                    } else if (!visible(browser, ERRORS).isEmpty() && !errorShown[0]) {
                        errorShown[0] = true;
                        progress.accept("Sign in was not accepted. Review the Chrome window; no automatic retry will be made.");
                    }
                    return false;
                } catch (StaleElementReferenceException changed) { return false; }
            });
            progress.accept("Signed in. Loading the selected portal...");
        } catch (AmbiguousSignInException ambiguous) {
            throw new IOException("The Sign in controls are ambiguous (multiple visible buttons). Nothing was submitted.");
        } catch (RuntimeException failure) {
            throw new IOException(clickAttempted
                    ? "Portal sign-in did not complete after one automatic attempt. Complete CAPTCHA or review the login page before trying again."
                    : "Portal sign-in was not submitted. Check the login fields and Sign in button.", failure);
        }
    }

    /** Temporary compatibility alias for module entry points during migration. */
    public static void fillAndAwaitManualSignIn(WebDriver driver,String loginUrl,
            String username,String password,int seconds,Consumer<String> progress) throws IOException {
        signIn(driver, loginUrl, username, password, seconds, progress);
    }

    private static List<WebElement> visible(WebDriver driver, By selector) {
        List<WebElement> found = new ArrayList<>();
        for (WebElement element : driver.findElements(selector)) if (element.isDisplayed()) found.add(element);
        return found;
    }

    private static final class AmbiguousSignInException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
    }
}
