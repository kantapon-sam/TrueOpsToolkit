package com.java.myapp;

import java.io.*;

public class PathFolder {

    private String current;
    private String Login;
    private String User_NCE;
    private String Chromedriver;
    private String ZipChromedriver;

    public PathFolder() {
        try {
            current = new File(".").getCanonicalPath();
            Login = new File(".").getCanonicalPath() + "\\Login.txt";
            User_NCE = new File(".").getCanonicalPath() + "\\User_NCE.txt";
            Chromedriver = new File(".").getCanonicalPath() + "\\chromedriver.exe";
            ZipChromedriver = new File(".").getCanonicalPath() + "\\chromedriver-win64.zip";
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public String getCurrent() {
        return current;
    }

    public String getLogin() {
        return Login;
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

    public String getUser_NCE() {
        return User_NCE;
    }

}
