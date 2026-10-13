package com.java.myapp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ApproveProms_Ref {

    public static void main(String[] args) throws IOException {
        Dialog.setLAF();
        PathFolder Folder = new PathFolder();
        PromsSettings settings = PromsSettings.readLogin(Folder.getSettings(), false);
        List<String[]> references = PromsSettings.readReferences(Folder.getRef_No(), 3);

        String ref = "";
        String type = "";
        String des = "";
        boolean shouldStop = false;
        final AtomicReference<WebDriver> ownedDriver = new AtomicReference<>();
        Thread cleanupHook = new Thread(() -> closeOwnedDriver(ownedDriver),
                "approve-proms-ref-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);

        try {
            System.setProperty("webdriver.chrome.driver", Folder.getChromedriver());
            WebDriver driver = new ChromeDriver();
            ownedDriver.set(driver);
            driver.get("https://proms.truecorp.co.th/proms/login/");
            driver.manage().window().maximize();

            WebElement username = driver.findElement(By.id("username"));
            WebElement password = driver.findElement(By.id("password"));
            username.sendKeys(settings.get("User"));
            password.sendKeys(settings.get("Password"));
            WebElement signInButton = driver.findElement(By.id("btn_signin"));
            new WebDriverWait(driver, 120).until(ExpectedConditions.stalenessOf(signInButton));
            System.out.println("The user submitted the sign-in form.");
            Thread.sleep(300);
            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
            WebDriverWait wait = new WebDriverWait(driver, 1000);

            Thread.sleep(5000);

            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
            System.out.println("Loading complete, proceeding with next steps.");



            // === ADD: เตรียมไฟล์ log แบบ append ===
            String dateTag = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            String logPath = Folder.getLog() + "\\" + dateTag + ".txt";

            // ✅ ถ้าไม่มีโฟลเดอร์ log ให้สร้าง
            File logFolder = new File(Folder.getLog());
            if (!logFolder.exists()) {
                boolean created = logFolder.mkdirs();
                if (created) {
                    System.out.println("Created log folder: " + Folder.getLog());
                } else {
                    System.out.println("Could not create log folder: " + Folder.getLog());
                }
            }
            try (BufferedWriter log = new BufferedWriter(new FileWriter(logPath, true))) {
                java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
                for (String[] reference : references) {
                        WebElement searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));

                    //       try {
                    ref = reference[0];
                    type = reference[1];
                    des = reference[2];

                    searchInput.clear();
                    searchInput.sendKeys(ref);
                    Thread.sleep(1500);

                    By errorMessageLocator = By.cssSelector(".pull-left.ng-binding[data-ng-show='searchErrorMessage != null']");
                    WebElement errorMessage = driver.findElement(errorMessageLocator);

                    searchInput.sendKeys(Keys.ENTER);
                    Thread.sleep(1000);

    // ✅ หาลิงก์ Request No. ที่ตรงกับ ref
                    By reqLinkBy = By.xpath("//td[@data-title-text='Request No.']//a[normalize-space(text())='" + ref + "']");

    // ✅ นับจำนวนลิงก์ Request No. ที่ตรงกับ ref
                    List<WebElement> reqLinks = driver.findElements(reqLinkBy);

                    if (reqLinks.size() == 1) {
                        // มีแค่ 1 รายการ → คลิกได้
                        WebElement reqLink = wait.until(ExpectedConditions.elementToBeClickable(reqLinks.get(0)));
                        reqLink.click();
                        System.out.println("Found one matching request: " + ref);
                    } else if (reqLinks.size() == 0) {
                        System.out.println("Request not found: " + ref + ". Skipping.");
                        // 🔹 LOG
                        log.write(ref + ",No ref," + java.time.LocalTime.now().format(TIME));
                        log.newLine();
                        log.flush();
                        continue;
                    } else {
                        System.out.println("Found " + reqLinks.size() + " requests matching " + ref + ". Skipping duplicates.");
                        // 🔹 LOG
                        log.write(ref + ",Duplicate ref," + java.time.LocalTime.now().format(TIME));
                        log.newLine();
                        log.flush();
                        continue;
                    }

                    wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));

                    WebElement boqTitle = wait.until(
                            ExpectedConditions.presenceOfElementLocated(By.xpath("//h2[@class='box-title' and normalize-space()='Work Order BOQ']"))
                    );

    // ✅ หาปุ่ม collapse ที่อยู่ใน div เดียวกัน (ancestor::div ... descendant::button)
                    WebElement collapseButton = driver.findElement(
                            By.xpath("//h2[@class='box-title' and normalize-space()='Work Order BOQ']/ancestor::div[contains(@class,'box-header')]//button[@data-widget='collapse']")
                    );

    // ✅ Scroll ให้เห็นปุ่ม
                    JavascriptExecutor js = (JavascriptExecutor) driver;
                    js.executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", collapseButton);
                    Thread.sleep(800);

    // ✅ คลิกด้วย JS (ป้องกัน element ถูกบัง)
                    js.executeScript("arguments[0].click();", collapseButton);

                    WebElement turnkeyTab = wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//a[@id='linkTurnkey' and contains(., 'FBOQ:Turnkey')]")
                    ));

                    js.executeScript("arguments[0].scrollIntoView({behavior:'smooth', block:'center'});", turnkeyTab);
                    Thread.sleep(800);

    // ✅ คลิกด้วย JavaScript (ป้องกัน ElementNotInteractableException)
                    js.executeScript("arguments[0].click();", turnkeyTab);

                    wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));

                    WebElement loadFboq = driver.findElement(By.xpath("//a[@ng-click='loadFboqTkDetail(row)']"));
                    loadFboq.click();
                    wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));

                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//table[contains(@class,'ng-table')]//th//span[normalize-space()='C-Part Type']")
                    ));

                    System.out.println("FBOQ Turnkey table loaded.");

    // ✅ ดึงข้อมูลในช่อง C-Part Type ของทุกแถว
                    List<WebElement> cPartTypeCells = driver.findElements(
                            By.xpath("//table[contains(@class,'ng-table')]//td[@title=\"'C-Part Type'\"]//div[@class='ng-binding']")
                    );

                    System.out.println("Found " + cPartTypeCells.size() + " C-Part Type rows.");
                    int Not_serveice = 0;
                    for (int rowIndex = 0; rowIndex < cPartTypeCells.size(); rowIndex++) {
                        String value = cPartTypeCells.get(rowIndex).getText().trim();
                        System.out.println("Row " + (rowIndex + 1) + ": C-Part Type = " + value);

                        if (!value.equalsIgnoreCase("Services")) {
                            Not_serveice = 1;
                            break;
                        }
                    }

                    if (Not_serveice == 0) {
                        WebElement approveButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]")));
                        approveButton.click();
                        Thread.sleep(1000);
                        //WebElement textArea = driver.findElement(By.cssSelector("textarea[ng-model='commentDesc']"));
                        WebElement textArea = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("textarea[ng-model='commentDesc']")));
                        textArea.sendKeys(des);
                        Thread.sleep(1000);
                        //WebElement okButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'paOkModal()')]"));
                        WebElement okButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'paOkModal()')]")));
                        okButton.click();
                        Thread.sleep(1000);
                        //WebElement Success = driver.findElement(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button"));
                        WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")));
                        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                        Success.click();
                          Thread.sleep(5000);
                        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));

                        log.write(ref + ",Approved," + des.replaceAll("[\\r\\n]+", " ").trim() + "," + java.time.LocalTime.now().format(TIME));
                        log.newLine();
                        log.flush();
                        // ✅ เตรียมสภาพหน้าสำหรับรอบถัดไป



                    } else {
                        // 🔹 LOG
                        log.write(ref + ",Skipped,Found non-Service," + java.time.LocalTime.now().format(TIME));
                        log.newLine();
                        log.flush();


                    }
                }
            }

        } catch (Exception ex) {
            String advice = ownedDriver.get() == null
                    ? " Check Chrome and update the shared ChromeDriver in TrueOps Toolkit settings." : "";
            throw new IOException("Approve PROMS by Ref failed." + advice + " See the cause for details.", ex);
        } finally {
            closeOwnedDriver(ownedDriver);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanupHook);
            } catch (IllegalStateException ignored) {
                // The shutdown hook owns cleanup while the JVM is stopping.
            }
        }
    }

    private static void closeOwnedDriver(AtomicReference<WebDriver> ownedDriver) {
        WebDriver driver = ownedDriver.getAndSet(null);
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The browser or its driver may already have been closed.
            }
        }
    }
}
