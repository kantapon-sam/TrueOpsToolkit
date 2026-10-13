package com.java.myapp;

import approve.MockLoginPortal;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Automatic-login checks use only loopback pages and synthetic account values. */
public final class PromsSignInTest {
    private static final String USER = "synthetic shared user";
    private static final String PASSWORD = "synthetic-secret, only & test";
    private static int checks;
    private static ChromeDriver driver;
    private static Path fixture;
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected ChromeDriver path and report path.");
        fixture = args.length > 2 ? Paths.get(args[2]) : Paths.get("test/approve/mock-login.html");
        System.setProperty("webdriver.chrome.driver", args[0]);
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1200,900", "--disable-background-networking",
                "--disable-component-update", "--disable-sync", "--no-first-run", "--no-default-browser-check",
                "--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost");
        try {
            driver = new ChromeDriver(options);
            for (String layout : new String[] {"external", "internal", "ng"}) successfulLayout(layout);
            delayedSuccessWaitsForNavigation();
            failedLoginNeverRetries();
            ambiguousButtonsNeverClick();
            hiddenDuplicateIsIgnored();
            captchaRetainsAccountAndWaitsForHuman();
            compatibilityAliasSignsIn();
            report.append("Checks: ").append(checks).append("\nResult: PASS\n");
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            report.append("Result: FAIL\n").append(trace.toString());
            throw failure;
        } finally {
            if (driver != null) driver.quit();
            Path destination = Paths.get(args[1]);
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.write(destination, report.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(report.toString());
    }

    private static void successfulLayout(String layout) throws Exception {
        try (MockLoginPortal portal = portal(layout, "success")) {
            List<String> progress = new ArrayList<>();
            PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 8, progress::add);
            equal(1, portal.attempts().size(), "Layout submits exactly once: " + layout);
            assertAccount(portal.attempts().get(0));
            equal(0, driver.findElements(By.id("username")).size(), "Signed-in page has no login username");
            equal(true, driver.getCurrentUrl().endsWith("/proms/pendingapprove"), "Signed-in navigation completed");
            equal(true, progress.stream().anyMatch(message -> message.contains("automatically")), "Automatic sign-in status shown");
            equal(true, progress.stream().anyMatch(message -> message.startsWith("Signed in")), "Success status shown after navigation");
            report.append("PASS: ").append(layout).append(" login selector automatically clicks once and fills the shared account exactly\n");
        }
    }

    private static void delayedSuccessWaitsForNavigation() throws Exception {
        try (MockLoginPortal portal = portal("external", "delayed")) {
            long started = System.nanoTime();
            PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 8, message -> { });
            equal(true, (System.nanoTime() - started) / 1000000L >= 1700, "Sign-in waits for delayed navigation");
            equal(1, portal.attempts().size(), "Delayed sign-in makes no extra attempt");
            report.append("PASS: delayed successful navigation is awaited without another login click\n");
        }
    }

    private static void failedLoginNeverRetries() throws Exception {
        try (MockLoginPortal portal = portal("internal", "failure")) {
            List<String> progress = new ArrayList<>();
            try {
                PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 2, progress::add);
                throw new AssertionError("Rejected login must not report sign-in success.");
            } catch (IOException expected) {
                contains(expected.getMessage(), "one automatic attempt", "Rejected login has a clear single-attempt outcome");
                assertNoAccountInError(expected);
            }
            equal(1, portal.attempts().size(), "Rejected login never automatically retries");
            equal(true, driver.getCurrentUrl().endsWith("/proms/login"), "Rejected login remains on login page");
            equal(true, progress.stream().noneMatch(message -> message.startsWith("Signed in")), "Rejected login has no false success status");
            report.append("PASS: rejection stays on login, reports failure and makes only one automatic attempt\n");
        }
    }

    private static void ambiguousButtonsNeverClick() throws Exception {
        try (MockLoginPortal portal = portal("external", "ambiguous")) {
            try {
                PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 3, message -> { });
                throw new AssertionError("Multiple visible Sign-in controls must not be clicked.");
            } catch (IOException expected) { contains(expected.getMessage(), "ambiguous", "Button ambiguity is explicit"); }
            equal(0, portal.attempts().size(), "Ambiguous sign-in sends no login request");
            equal(0L, clicks(), "Ambiguous sign-in never clicks a button");
            report.append("PASS: multiple visible Sign-in buttons abort before any click/request\n");
        }
    }

    private static void hiddenDuplicateIsIgnored() throws Exception {
        try (MockLoginPortal portal = portal("external", "hidden-duplicate")) {
            PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 8, message -> { });
            equal(1, portal.attempts().size(), "Hidden duplicate does not block the one visible action");
            report.append("PASS: hidden duplicate controls are ignored; overlapping selectors identify one actual button\n");
        }
    }

    private static void captchaRetainsAccountAndWaitsForHuman() throws Exception {
        try (MockLoginPortal portal = portal("internal", "captcha")) {
            final boolean[] continued = {false};
            List<String> messages = new ArrayList<>();
            PromsSignIn.signIn(driver, portal.loginUrl(), USER, PASSWORD, 8, message -> {
                messages.add(message);
                if (message.startsWith("Complete CAPTCHA") && !continued[0]) {
                    continued[0] = true;
                    equal(1, portal.attempts().size(), "CAPTCHA follows exactly one automatic attempt");
                    equal(USER, driver.findElement(By.id("username")).getAttribute("value"), "Username preserved for human step");
                    equal(PASSWORD, driver.findElement(By.id("password")).getAttribute("value"), "Password preserved for human step");
                    equal("", driver.findElement(By.id("captchaInput")).getAttribute("value"), "Program never fills CAPTCHA");
                    try { Thread.sleep(1200); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    equal(1, portal.attempts().size(), "Waiting for human does not retry login");
                    equal(1L, clicks(), "Only automatic initial click occurred before human continuation");
                    // This is an explicit simulated human action on a synthetic loopback challenge.
                    driver.findElement(By.id("captchaInput")).sendKeys("HUMAN_VERIFIED");
                    driver.findElement(By.cssSelector("button[data-ng-click='login()']")).click();
                }
            });
            equal(true, continued[0], "CAPTCHA status requested human continuation");
            equal(2, portal.attempts().size(), "One automatic attempt plus one explicit simulated human attempt");
            for (Map<String,String> attempt : portal.attempts()) assertAccount(attempt);
            equal("", portal.attempts().get(0).get("captcha"), "Initial program attempt does not solve CAPTCHA");
            equal("HUMAN_VERIFIED", portal.attempts().get(1).get("captcha"), "Only simulated human supplies synthetic challenge");
            report.append("PASS: CAPTCHA remains untouched; account stays filled, no retry occurs, and explicit human continuation completes login\n");
        }
    }

    private static void compatibilityAliasSignsIn() throws Exception {
        try (MockLoginPortal portal = portal("external", "success")) {
            PromsSignIn.fillAndAwaitManualSignIn(driver, portal.loginUrl(), USER, PASSWORD, 8, message -> { });
            equal(1, portal.attempts().size(), "Compatibility alias uses one automatic sign-in");
            report.append("PASS: old helper alias routes to the new automatic sign-in behavior\n");
        }
    }

    private static MockLoginPortal portal(String layout, String mode) throws Exception { return new MockLoginPortal(fixture, layout, mode); }
    private static Long clicks() { return ((Number) ((JavascriptExecutor) driver).executeScript("return window.loginFixture.clicks")).longValue(); }
    private static void assertAccount(Map<String,String> fields) {
        equal(USER, fields.get("username"), "Backend receives exact shared username");
        equal(PASSWORD, fields.get("password"), "Backend receives exact shared password");
    }
    private static void assertNoAccountInError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && (cause.getMessage().contains(USER) || cause.getMessage().contains(PASSWORD))) {
                throw new AssertionError("Sign-in error exposed synthetic account values.");
            }
        }
        checks++;
    }
    private static void contains(String actual,String expected,String label) {
        if (actual == null || !actual.contains(expected)) throw new AssertionError(label + ": " + actual);
        checks++;
    }
    private static void equal(Object expected,Object actual,String label) {
        if (!expected.equals(actual)) throw new AssertionError(label);
        checks++;
    }
}
