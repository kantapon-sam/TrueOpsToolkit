package com.java.myapp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Download_ChromeDriver {

    public String getChromeVersion() throws IOException {
        String command = "reg query \"HKEY_CURRENT_USER\\Software\\Google\\Chrome\\BLBeacon\" /v version";
        Process process = Runtime.getRuntime().exec(command);

        InputStream inputStream = process.getInputStream();
        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.startsWith("version")) {
                return line.split("\\s+")[line.split("\\s+").length - 1];
            }
        }

        return "Version not found";
    }

    public String readJsonFromUrl(String urlString) throws IOException {
        StringBuilder content = new StringBuilder();

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        } finally {
            connection.disconnect();
        }
        return content.toString();
    }

    public void downloadZipFile(String zipFileUrl, String destinationFolder) throws IOException {
        URL url = new URL(zipFileUrl);
        try (InputStream in = url.openStream();
                FileOutputStream out = new FileOutputStream(destinationFolder + File.separator + "chromedriver-win64.zip")) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    public void unzip(String zipFilePath, String destinationFolder) throws IOException {
        byte[] buffer = new byte[1024];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String fileName = zipEntry.getName();
                File newFile = new File(destinationFolder + File.separator + fileName);
                // Create all non-existent parent directories
                newFile.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    int length;
                    while ((length = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    public void moveFile(String sourceFolderPath, String destinationFolderPath, String fileName) throws IOException {
        Path sourcePath = Paths.get(sourceFolderPath, fileName);
        Path destinationPath = Paths.get(destinationFolderPath, fileName);

        Files.move(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public void deleteFolder(File folder) {
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteFolder(file);
                }
            }
        }
        folder.delete();
    }
}
