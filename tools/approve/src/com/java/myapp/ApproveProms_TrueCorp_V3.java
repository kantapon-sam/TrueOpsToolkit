package com.java.myapp;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ApproveProms_TrueCorp_V3 {

    public static void main(String[] args) throws IOException {
        Dialog.setLAF();
        PathFolder Folder = new PathFolder();
        PromsSettings settings = PromsSettings.readLogin(Folder.getLogin(), true);
        String Search = settings.get("Search");
        String Description = settings.get("Description");
        int index_page = 0;
        boolean shouldStop = false;
        final AtomicReference<WebDriver> ownedDriver = new AtomicReference<>();
        Thread cleanupHook = new Thread(() -> closeOwnedDriver(ownedDriver),
                "approve-proms-browser-cleanup");
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
            //  while (true) {

            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
            WebDriverWait wait = new WebDriverWait(driver, 200);
            Thread.sleep(5000);

            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));

            System.out.println("Loading complete, proceeding with next steps.");

            WebElement searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));

            if (Search.contains("&&")) {
                searchInput.sendKeys(Search.split("&&")[0]);
            } else {
                searchInput.sendKeys(Search); // ดำเนินการต่อเมื่อ element พร้อมแล้ว
            }
            Thread.sleep(1500);
            By errorMessageLocator = By.cssSelector(".pull-left.ng-binding[data-ng-show='searchErrorMessage != null']");
            WebElement errorMessage = driver.findElement(errorMessageLocator);
            /*     if (errorMessage.isDisplayed()) {
                    WebElement closeButton = errorMessage.findElement(By.cssSelector(".fa-times"));
                    closeButton.click();
                    break;
                }*/
            searchInput.sendKeys(Keys.ENTER);
            Thread.sleep(1000);
            int index = 0;
            String projectName = "";
            String requester = "";
            String requestNo = "";
            String requestType = "";
            try {
                WebElement lastPageElement = driver.findElement(By.xpath("//a[@ng-switch-when='last']//span"));

                // ดึงหมายเลขหน้าสุดท้าย
                String lastPageNumber = lastPageElement.getText();

                System.out.println("Last page number: " + lastPageNumber);
                String[] index_No = new String[Integer.parseInt(lastPageNumber) * 10];

                System.out.println(lastPageNumber);
                for (int page = 1; page <= Integer.parseInt(lastPageNumber); page++) {
                    // System.out.println(page);

                    if (page >= 1) {
                        if (page == 1) {
                            WebElement firstPageElement = driver.findElement(By.xpath("//a[@ng-switch-when='first']//span[text()='1']"));
                            firstPageElement.click();
                            //   index_page = 1;
                            List<WebElement> projectNameElements = driver.findElements(By.xpath("//td[@data-title-text='Project Name']"));
                            List<WebElement> requesterElements = driver.findElements(By.xpath("//td[@data-title-text='Requester']"));
                            List<WebElement> requestNoElements = driver.findElements(By.xpath("//td[@data-title-text='Request No.']//a"));
                            List<WebElement> requestTypeElements = driver.findElements(By.xpath("//td[@title=\"'Request Type'\"]//div"));

                            for (int i = 0; i < projectNameElements.size() && !shouldStop; i++) {
                                projectName = projectNameElements.get(i).getText();  // ดึงค่า Project Name
                                requester = requesterElements.get(i).getText();  // ดึงค่า Requester
                                requestNo = requestNoElements.get(i).getText();      // ดึงค่า Request No.
                                requestType = requestTypeElements.get(i).getText();      // ดึงค่า Request
                                index_No[index] = requestNo + "," + projectName + "," + requester + "," + requestType;
                                // System.out.println(index_No[index]);
                                index++;
                                System.out.println(requestNo + "," + projectName + "," + requester + "," + requestType);
                            }

                        } else if (page == Integer.parseInt(lastPageNumber)) {
                            WebElement lastPageElement1 = driver.findElement(By.xpath("//a[@ng-switch-when='last']//span[text()='" + lastPageNumber + "']"));
                            lastPageElement1.click();
                            index_page = Integer.parseInt(lastPageNumber);
                            List<WebElement> projectNameElements = driver.findElements(By.xpath("//td[@data-title-text='Project Name']"));
                            List<WebElement> requesterElements = driver.findElements(By.xpath("//td[@data-title-text='Requester']"));
                            List<WebElement> requestNoElements = driver.findElements(By.xpath("//td[@data-title-text='Request No.']//a"));
                            List<WebElement> requestTypeElements = driver.findElements(By.xpath("//td[@title=\"'Request Type'\"]//div"));

                            for (int i = 0; i < projectNameElements.size() && !shouldStop; i++) {
                                projectName = projectNameElements.get(i).getText();  // ดึงค่า Project Name
                                requester = requesterElements.get(i).getText();  // ดึงค่า Requester
                                requestNo = requestNoElements.get(i).getText();      // ดึงค่า Request No.
                                requestType = requestTypeElements.get(i).getText();      // ดึงค่า Request
                                index_No[index] = requestNo + "," + projectName + "," + requester + "," + requestType;
                                //  System.out.println(index_No[index]);
                                index++;
                                System.out.println(requestNo + "," + projectName + "," + requester + "," + requestType);
                            }

                        } else {
                            WebElement pageNumberElement = driver.findElement(By.xpath("//a[@ng-switch-when='page']//span[text()='" + page + "']"));
                            pageNumberElement.click();
                            // index_page = page;

                            List<WebElement> projectNameElements = driver.findElements(By.xpath("//td[@data-title-text='Project Name']"));
                            List<WebElement> requesterElements = driver.findElements(By.xpath("//td[@data-title-text='Requester']"));
                            List<WebElement> requestNoElements = driver.findElements(By.xpath("//td[@data-title-text='Request No.']//a"));
                            List<WebElement> requestTypeElements = driver.findElements(By.xpath("//td[@title=\"'Request Type'\"]//div"));

                            for (int i = 0; i < projectNameElements.size() && !shouldStop; i++) {
                                projectName = projectNameElements.get(i).getText();  // ดึงค่า Project Name
                                requester = requesterElements.get(i).getText();  // ดึงค่า Requester
                                requestNo = requestNoElements.get(i).getText();      // ดึงค่า Request No.
                                requestType = requestTypeElements.get(i).getText();      // ดึงค่า Request
                                index_No[index] = requestNo + "," + projectName + "," + requester + "," + requestType;
                                //  System.out.println(index_No[index]);
                                index++;
                                System.out.println(requestNo + "," + projectName + "," + requester + "," + requestType);
                            }

                        }
                    }

                }

                for (int i = 0; i < index; i++) {
                    if (Search.contains("&&")) {
                        if (index_No[i].contains(Search.split("&&")[0]) && index_No[i].contains(Search.split("&&")[1])) {
                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);
                            //WebElement checkbox = driver.findElement(By.cssSelector("input[type='checkbox'][ng-model='row.checked']"));
                            WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='checkbox'][ng-model='row.checked']")));
                            Thread.sleep(1000);
                            checkbox.click();
                            Thread.sleep(1000);
                            // WebElement approveButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]"));
                            WebElement approveButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]")));
                            approveButton.click();
                            Thread.sleep(1000);
                            //WebElement textArea = driver.findElement(By.cssSelector("textarea[ng-model='commentDesc']"));
                            WebElement textArea = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("textarea[ng-model='commentDesc']")));
                            textArea.sendKeys(Description);
                            Thread.sleep(1000);
                            //WebElement okButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'paOkModal()')]"));
                            WebElement okButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'paOkModal()')]")));
                            okButton.click();
                            Thread.sleep(1000);
                            //WebElement Success = driver.findElement(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button"));
                            WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")));
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                            Success.click();
                            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                        }
                    } else {
                        if (index_No[i].contains(Search)) {
                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);

                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);
                            //WebElement checkbox = driver.findElement(By.cssSelector("input[type='checkbox'][ng-model='row.checked']"));
                            WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='checkbox'][ng-model='row.checked']")));
                            Thread.sleep(1000);
                            checkbox.click();
                            Thread.sleep(1000);
                            // WebElement approveButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]"));
                            WebElement approveButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]")));
                            approveButton.click();
                            Thread.sleep(1000);
                            //WebElement textArea = driver.findElement(By.cssSelector("textarea[ng-model='commentDesc']"));
                            WebElement textArea = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("textarea[ng-model='commentDesc']")));
                            textArea.sendKeys(Description);
                            Thread.sleep(1000);
                            //WebElement okButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'paOkModal()')]"));
                            WebElement okButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'paOkModal()')]")));
                            okButton.click();
                            Thread.sleep(1000);
                            //WebElement Success = driver.findElement(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button"));
                            WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")));
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                            Success.click();
                            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                        }
                    }

                }
            } catch (NoSuchElementException ex) {
                String[] index_No = new String[10];

                List<WebElement> projectNameElements = driver.findElements(By.xpath("//td[@data-title-text='Project Name']"));
                List<WebElement> requesterElements = driver.findElements(By.xpath("//td[@data-title-text='Requester']"));
                List<WebElement> requestNoElements = driver.findElements(By.xpath("//td[@data-title-text='Request No.']//a"));
                List<WebElement> requestTypeElements = driver.findElements(By.xpath("//td[@title=\"'Request Type'\"]//div"));

                for (int i = 0; i < projectNameElements.size() && !shouldStop; i++) {
                    projectName = projectNameElements.get(i).getText();  // ดึงค่า Project Name
                    requester = requesterElements.get(i).getText();  // ดึงค่า Requester
                    requestNo = requestNoElements.get(i).getText();      // ดึงค่า Request No.
                    requestType = requestTypeElements.get(i).getText();      // ดึงค่า Request
                    index_No[index] = requestNo + "," + projectName + "," + requester + "," + requestType;
                    //  System.out.println(index_No[index]);
                    index++;
                    System.out.println(requestNo + "," + projectName + "," + requester + "," + requestType);
                }
                for (int i = 0; i < index; i++) {
                    if (Search.contains("&&")) {
                        if (index_No[i].contains(Search.split("&&")[0]) && index_No[i].contains(Search.split("&&")[1])) {
                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);

                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);
                            //WebElement checkbox = driver.findElement(By.cssSelector("input[type='checkbox'][ng-model='row.checked']"));
                            WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='checkbox'][ng-model='row.checked']")));
                            Thread.sleep(1000);
                            checkbox.click();
                            Thread.sleep(1000);
                            // WebElement approveButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]"));
                            WebElement approveButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]")));
                            approveButton.click();
                            Thread.sleep(1000);
                            //WebElement textArea = driver.findElement(By.cssSelector("textarea[ng-model='commentDesc']"));
                            WebElement textArea = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("textarea[ng-model='commentDesc']")));
                            textArea.sendKeys(Description);
                            Thread.sleep(1000);
                            //WebElement okButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'paOkModal()')]"));
                            WebElement okButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'paOkModal()')]")));
                            okButton.click();
                            Thread.sleep(1000);
                            //WebElement Success = driver.findElement(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button"));
                            WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")));
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                            Success.click();
                            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                        }
                    } else {
                        if (index_No[i].contains(Search)) {
                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);

                            searchInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("txtSearch")));
                            searchInput.clear();
                            Thread.sleep(1000);
                            searchInput.sendKeys(index_No[i].split(",")[0]);
                            Thread.sleep(2000);
                            //WebElement checkbox = driver.findElement(By.cssSelector("input[type='checkbox'][ng-model='row.checked']"));
                            WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("input[type='checkbox'][ng-model='row.checked']")));
                            Thread.sleep(1000);
                            checkbox.click();
                            Thread.sleep(1000);
                            // WebElement approveButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]"));
                            WebElement approveButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'approvalAction') and contains(., 'Approve')]")));
                            approveButton.click();
                            Thread.sleep(1000);
                            //WebElement textArea = driver.findElement(By.cssSelector("textarea[ng-model='commentDesc']"));
                            WebElement textArea = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("textarea[ng-model='commentDesc']")));
                            textArea.sendKeys(Description);
                            Thread.sleep(1000);
                            //WebElement okButton = driver.findElement(By.xpath("//button[contains(@ng-click, 'paOkModal()')]"));
                            WebElement okButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(@ng-click, 'paOkModal()')]")));
                            okButton.click();
                            Thread.sleep(1000);
                            //WebElement Success = driver.findElement(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button"));
                            WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")));
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                            Success.click();
                            driver.get("https://proms.truecorp.co.th/proms/pendingapprove");
                            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("img.swal2-image")));
                        }
                    }

                }
            }
        } catch (Exception ex) {
            String advice = ownedDriver.get() == null
                    ? " Check Chrome and update the shared ChromeDriver in TrueOps Toolkit settings." : "";
            throw new IOException("Approve PROMS failed." + advice + " See the cause for details.", ex);
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
