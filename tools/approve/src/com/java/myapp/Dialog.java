package com.java.myapp;

import java.io.*;
import java.net.*;
import javax.swing.*;

public class Dialog extends PathFolder {

    private final Object[] options = {"Result", "Download", "Exit"};
    final Object[] login = {"OK", "Cancel"};
    private final Object[] cacti = {"OK", "Cancel"};
    private int Numberlogin = 0;
    private int lineNumber = 0;
    private int TotalFile = 0;
    private String Name_file;
    private int choice = 0;
    private PathFolder PathFolderMusic;
    private InputStream InputStream;
    private long secDownload = 0;
    private long secResult = 0;
    private long delay = 0;
    private String dateStart;
    private String dateStop;
    private int Num_Cacti = 0;
    private int Errorline = 0;
    //protected AudioStream Music;

    public static void setLAF() {

        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception e) {
            System.err.println("Failed to set LookAndFeel");

        }
    }











    public void Success() {
        java.awt.Toolkit.getDefaultToolkit().beep();
    }



    public void GoogleChrome() {

        JOptionPane.showMessageDialog(null,
                "Please install programe GoogleChrome",
                "Error GoogleChrome",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }





    public void LoadChromeDriver() {
        URLConnection Connect = new URLConnection();

        try {
            String command = "wmic datafile where name=\"C:\\\\Program Files (x86)\\\\Google\\\\Chrome\\\\Application\\\\chrome.exe\" get Version /value";
            Process child = Runtime.getRuntime().exec(command);

            InputStream in = child.getInputStream();
            String Chromeversion = "";
            int c;
            while ((c = in.read()) != -1) {
                // System.out.print((char) c);
                Chromeversion += (char) c;
            }
            in.close();
            String s = Chromeversion.split("=")[1].split("\\.")[0];
            System.out.println(s);

            JOptionPane.showMessageDialog(null,
                    "1.Load ChromeDriver version " + s + "\n2.Update chrome latest version",
                    "Error ChromeDriver",
                    JOptionPane.ERROR_MESSAGE);
            Connect.DowloadChromeDriver(new URI("https://chromedriver.chromium.org/downloads"));
            System.exit(0);
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        } catch (URISyntaxException ex) {
            System.out.println(ex.getMessage());
        }

    }

    public void Error(String message) {
        JOptionPane.showMessageDialog(null,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }
   public void NewProgram() {

     JOptionPane.showMessageDialog(null, "Please start a new program.");
        System.exit(0);
    }
    public Object[] getOptions() {
        return options;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getChoice() {
        return choice;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public void setTotalFile(int TotalFile) {
        this.TotalFile = TotalFile;
    }

    public void setChoice(int choice) {
        this.choice = choice;
    }

    public void setSecDownload(long secDownload) {
        this.secDownload = secDownload;
    }

    public void setSecResult(long secResult) {
        this.secResult = secResult;
    }

    public long getDelay() {
        return delay;
    }

    public void setErrorline(int Errorline) {
        this.Errorline = Errorline;
    }

    public String getDateStart() {
        return dateStart;
    }

    public String getDateStop() {
        return dateStop;
    }

    public String getName_file() {
        return Name_file;
    }

    public void setName_file(String Name_file) {
        this.Name_file = Name_file;
    }

    public int getNumberlogin() {
        return Numberlogin;
    }

    public int getNum_Cacti() {
        return Num_Cacti;
    }

    public long getSecDownload() {
        return secDownload;
    }

    public long getSecResult() {
        return secResult;
    }

}
