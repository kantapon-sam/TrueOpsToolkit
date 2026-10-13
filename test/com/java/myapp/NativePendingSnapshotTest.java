package com.java.myapp;

import approve.MockPromsPortal;
import approve.MockPromsPortal.Row;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Real Chrome tests of complete known Angular datasets; all requests stay on loopback. */
public final class NativePendingSnapshotTest {
    private static int checks;
    private static ChromeDriver driver;
    private static Path fixture;
    private static final StringBuilder report=new StringBuilder();

    public static void main(String[] args) throws Exception {
        fixture=args.length>2?Paths.get(args[2]):Paths.get("test/native-pending/native-pending.html");
        String delayBefore=System.getProperty("trueops.search.delay.ms");
        System.setProperty("trueops.search.delay.ms","1000");
        System.setProperty("webdriver.chrome.driver",args[0]);
        ChromeOptions options=new ChromeOptions();
        options.addArguments("--headless=new","--window-size=1200,900","--no-first-run","--no-default-browser-check",
                "--disable-background-networking","--disable-sync","--disable-extensions","--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost");
        try {
            driver=new ChromeDriver(options);driver.manage().timeouts().pageLoadTimeout(20,TimeUnit.SECONDS);
            completeFortyFiveWithoutPaging();decoratedProjectWithoutPaging();lateInitialLoadCannotOverwriteSearch();emptyAndPrefixAbsenceWithoutPaging();hiddenTargetOnlyVisitsNeededPage();
            duplicateExactHiddenFailsWithoutPaging();unsupportedSnapshotsUsePagedFallback();contradictoryEmptyOrHiddenDomFailsClosed();
            report.append("Checks: ").append(checks).append("\nResult: PASS\n");
        } catch(Throwable failure){report.append("Result: FAIL\n").append(failure.toString()).append('\n');throw failure;}
        finally {
            if(driver!=null)driver.quit();
            if(delayBefore==null)System.clearProperty("trueops.search.delay.ms");else System.setProperty("trueops.search.delay.ms",delayBefore);
            Path destination=Paths.get(args[1]);if(destination.getParent()!=null)Files.createDirectories(destination.getParent());
            Files.write(destination,report.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(report.toString());
    }

    private static void decoratedProjectWithoutPaging() throws Exception {
        try(MockPromsPortal portal=portal(rows("NATIVE-CODE-",45),"decorated-project",10)) {
            List<PromsApprovalSupport.Row> loaded=new PromsApprovalSupport(driver,6).collect("tk09",message->{ });
            equal(45,loaded.size(),"Verified decorated project dataset is complete");
            equal("SYNTHETIC-CODE : Synthetic native project",loaded.get(0).getProject(),"Native project preserves verified CODE : NAME display");
            equal(0L,metric("pagerClicks"),"Decorated project crosscheck retains native no-pagination path");
            equal(0,portal.attempts().size(),"Decorated native inspection never submits decisions");
            report.append("PASS: verified projectCode : projectName projection preserves row text and avoids all-page fallback\n");
        }
    }

    private static void lateInitialLoadCannotOverwriteSearch() throws Exception {
        List<Row> records=new ArrayList<>();
        for(int n=0;n<45;n++)records.add(new Row("NATIVE-OTHER-"+n,"Unrelated project","Synthetic requester","NT_IE_TK10(PAC) : OTHER-SITE"));
        records.addAll(rows("NATIVE-LATE-",45));
        try(MockPromsPortal portal=portal(records,"late-initial-load",10)) {
            ApprovePromsWorkflow.Result result=ApprovePromsWorkflow.preview(driver,"tk09",6);
            equal(45,result.getScanned(),"Late initial full list does not override final filtered count");
            equal(45,result.getMatched(),"Only current native TK09 dataset accepted after initial readiness");
            equal(0L,metric("prematureSearchCalls"),"No filter typing occurs while jQuery or ng-table initial load is active");
            equal(0L,metric("pagerClicks"),"Late initial load still uses complete native data without paging");
            equal(0,portal.attempts().size(),"Initial-readiness test never submits decisions");
            report.append("PASS: delayed initial unfiltered load settles before typing and cannot overwrite final category filter\n");
        }
    }

    private static void completeFortyFiveWithoutPaging() throws Exception {
        try(MockPromsPortal portal=portal(rows("NATIVE-",45),"normal",10)) {
            ApprovePromsWorkflow.Result result=ApprovePromsWorkflow.preview(driver,"tk09",6);
            equal(45,result.getScanned(),"Complete native dataset count");equal(45,result.getMatched(),"Every native category matches");
            equal(45,result.getMatchedReferences().size(),"All references returned, not only visible ten");
            equal(0L,metric("pagerClicks"),"Native initial preview never navigates pages");
            equal(0,portal.attempts().size(),"Native preview never submits decisions");
            report.append("PASS: 45 loaded filtered requests read once without any pagination\n");
        }
    }

    private static void emptyAndPrefixAbsenceWithoutPaging() throws Exception {
        try(MockPromsPortal portal=portal(new ArrayList<Row>(),"normal",10)) {
            PromsApprovalSupport support=new PromsApprovalSupport(driver,6);
            equal(0,support.collect("tk09",message->{ }).size(),"Native empty dataset is complete");
            support.verifyNoLongerPending(portal.url(),"NATIVE-GONE");
            equal(0L,metric("pagerClicks"),"Fresh empty verification never pages");
        }
        try(MockPromsPortal portal=portal(rows("NATIVE-GONE-DECOY-",45),"normal",10)) {
            new PromsApprovalSupport(driver,6).verifyNoLongerPending(portal.url(),"NATIVE-GONE");
            equal(0L,metric("pagerClicks"),"Exact absence across 45 prefix matches never pages");
            equal(0,portal.attempts().size(),"Absence verification makes no decisions");
            report.append("PASS: verified empty and full prefix-match dataset prove exact absence without page walking\n");
        }
    }

    private static void hiddenTargetOnlyVisitsNeededPage() throws Exception {
        List<Row> records=rows("NATIVE-LATER-DECOY-",45);
        records.set(24,row("NATIVE-LATER"));
        try(MockPromsPortal portal=portal(records,"normal",10)) {
            PromsApprovalSupport support=new PromsApprovalSupport(driver,6);
            List<PromsApprovalSupport.Row> captured=support.searchReference("native-later");
            equal(45,captured.size(),"Complete native prefix dataset retained");
            equal(2L,metric("pagerClicks"),"Only pages 2 and 3 visited to locate exact target");
            check(support.findExactRow("NATIVE-LATER").isDisplayed(),"Unique exact target is left visible");
            equal(0,support.getScannedCount(),"Reference location does not overwrite initial scan counter");
        }
        try(MockPromsPortal portal=portal(records,"normal",10)) {
            boolean rejected=false;
            try{new PromsApprovalSupport(driver,6).verifyNoLongerPending(portal.url(),"NATIVE-LATER");}
            catch(IOException stillPending){rejected=true;}
            check(rejected,"Exact target on page3 cannot be mistaken for absence");
            equal(2L,metric("pagerClicks"),"Pending proof visits only pages needed to show target");
            equal(0,portal.attempts().size(),"Hidden-target verification never submits");
            report.append("PASS: unique exact target on page3 is found with two necessary page visits; absence fails safely\n");
        }
    }

    private static void duplicateExactHiddenFailsWithoutPaging() throws Exception {
        List<Row> records=rows("NATIVE-DUP-DECOY-",45);records.set(12,row("NATIVE-DUP"));records.set(42,row("native-dup"));
        try(MockPromsPortal portal=portal(records,"normal",10)) {
            boolean rejected=false;try{new PromsApprovalSupport(driver,6).searchReference("NATIVE-DUP");}
            catch(IOException duplicate){rejected=true;}
            check(rejected,"Casefold duplicate exact refs in hidden pages are rejected");
            equal(0L,metric("pagerClicks"),"Native duplicates are detected without pagination");
            equal(0,portal.attempts().size(),"Duplicate references never submit decisions");
            report.append("PASS: later-page duplicate exact references detected in complete data before any page visits\n");
        }
    }

    private static void unsupportedSnapshotsUsePagedFallback() throws Exception {
        for(String mode:Arrays.asList("count-mismatch","schema-mismatch")) {
            try(MockPromsPortal portal=portal(rows("NATIVE-FALLBACK-",45),mode,10)) {
                List<PromsApprovalSupport.Row> captured=new PromsApprovalSupport(driver,6).collect("tk09",message->{ });
                equal(45,captured.size(),"Fallback retains all requests after "+mode);
                equal(4L,metric("pagerClicks"),"Unsupported native snapshot uses proven DOM paging: "+mode);
                equal(0,portal.attempts().size(),"Fallback preview never submits");
            }
        }
        report.append("PASS: inconsistent total and unsupported schema fall back to complete DOM pagination\n");
    }

    private static void contradictoryEmptyOrHiddenDomFailsClosed() throws Exception {
        for(String mode:Arrays.asList("count-mismatch","hidden-dom")) {
            List<Row> records=mode.equals("hidden-dom")?rows("NATIVE-GONE-DECOY-",45):new ArrayList<Row>();
            try(MockPromsPortal portal=portal(records,mode,10)) {
                boolean rejected=false;
                try{new PromsApprovalSupport(driver,6).verifyNoLongerPending(portal.url(),"NATIVE-GONE");}
                catch(IOException unverified){rejected=true;}
                check(rejected,"Contradictory native count or hidden populated DOM cannot prove empty absence: "+mode);
                equal(0L,metric("pagerClicks"),"Contradictory snapshot fails before arbitrary page walking");
                equal(0,portal.attempts().size(),"Unverified absence never submits a decision");
            }
        }
        report.append("PASS: contradictory empty totals and hidden populated DOM fail closed before absence confirmation\n");
    }

    private static List<Row> rows(String prefix,int count){List<Row> result=new ArrayList<>();for(int n=0;n<count;n++)result.add(row(prefix+String.format("%03d",n)));return result;}
    private static Row row(String reference){return new Row(reference,"Synthetic native project","Synthetic requester","NT_IE_TK09(PAC) : SYNTHETIC-SITE");}
    private static MockPromsPortal portal(List<Row> rows,String mode,int size) throws Exception {MockPromsPortal result=new MockPromsPortal(fixture,rows,mode,size,true);driver.get(result.url());return result;}
    private static Long metric(String name){return ((Number)((JavascriptExecutor)driver).executeScript("return window.fixture[arguments[0]]",name)).longValue();}
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
    private static void equal(Object expected,Object actual,String name){if(!expected.equals(actual))throw new AssertionError(name+": expected "+expected+", got "+actual);checks++;}
}
