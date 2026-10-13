package com.java.myapp;

import java.io.*;

public class PathFolder {

    private String current;
    private String Settings;
    private String ListWorkOrder;
    private String Chromedriver;
    private String ZipChromedriver;

    public PathFolder() {
        try {
            current = new File(".").getCanonicalPath();
            Settings = new File(".").getCanonicalPath() + "\\settings.csv";
            ListWorkOrder = new File(".").getCanonicalPath() + "\\Ref_No.csv";
            Chromedriver = new File(".").getCanonicalPath() + "\\chromedriver.exe";
            ZipChromedriver = new File(".").getCanonicalPath() + "\\chromedriver-win64.zip";
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public String getCurrent() {
        return current;
    }

    public String getSettings() {
        return Settings;
    }

    public String getChromedriver() {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String toolkitHome = System.getProperty("trueops.home", "").trim();
        if (!toolkitHome.isEmpty()) {
            return new File(toolkitHome, "drivers/chromedriver.exe").getAbsolutePath();
        }
        return Chromedriver;
    }

    public String getZipChromedriver() {
        return ZipChromedriver;
    }

    public String getListWorkOrder() {
        return ListWorkOrder;
    }



}
