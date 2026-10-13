package com.java.myapp;

import java.awt.Desktop;
import java.io.*;
import java.net.*;

public class URLConnection extends Dialog {

    private int Error = 0;

    public void CheckConnection(String web, String line, int lineNumber, String Name_file) {
        setLineNumber(lineNumber);
        setName_file(Name_file);
        try {
            URL url = new URL(web);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            int statusCode = http.getResponseCode();
            if (statusCode == 200) {
                System.out.println(line.split(",")[0]);
            }
        } catch (UnknownHostException ex) {

            Error++;
            System.exit(0);
        } catch (MalformedURLException | RuntimeException ex) {

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public int getError() {
        return Error;
    }

    public void Login(URI login) {
        try {
            URL url = new URL(login.toString());
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            int statusCode = http.getResponseCode();
            Desktop d = Desktop.getDesktop();
            Process p = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "FTYPE"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.contains("chrome.exe") || line.contains("Chrome.exe")) {
                    count++;
                }
            }
            if (count == 0) {
                GoogleChrome();
                System.exit(0);
            }
            p = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start chrome.exe " + login});
        } catch (UnknownHostException ex) {
            System.exit(0);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void DowloadChromeDriver(URI uri) {
        try {
            URL url = new URL(uri.toString());
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            int statusCode = http.getResponseCode();
            Desktop d = Desktop.getDesktop();
            Process p = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "FTYPE"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.contains("chrome.exe") || line.contains("Chrome.exe")) {
                    count++;
                }
            }
            if (count == 0) {
                GoogleChrome();
                System.exit(0);
            }
            p = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start chrome.exe " + uri});

        } catch (UnknownHostException ex) {

            System.exit(0);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

}
