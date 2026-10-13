package com.java.myapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Canonical TK07 project and RFS-site rules; PDF contents never enter task logs. */
final class Tk07PdfReview {
    private static final String[] PROJECTS = { "single grid", "consolida", "blackcell grey",
        "asg card expansion", "geo b2b", "support olt small pocket", "ip clock" };
    private Tk07PdfReview() { }

    static boolean eligibleProject(String project) {
        String folded = project == null ? "" : project.toLowerCase(Locale.ROOT);
        for (String keyword : PROJECTS) if (folded.contains(keyword)) return true;
        return false;
    }

    static String siteFromRequestType(String type) throws IOException {
        String text = type == null ? "" : type.trim();
        int separator = text.lastIndexOf(':');
        if (separator < 0) throw new IOException("The TK07 request type has no site identifier. No decision was submitted.");
        String site = text.substring(separator + 1).trim().toUpperCase(Locale.ROOT);
        if (site.startsWith("D")) site = site.substring(1);
        else if (site.endsWith("Z") || site.endsWith("A") || site.endsWith("T")) site = site.substring(0, site.length() - 1);
        if (site.isEmpty() || !site.matches("[A-Z0-9_-]+")) {
            throw new IOException("The TK07 request site identifier cannot be verified. No decision was submitted.");
        }
        return site;
    }

    static boolean pdfContainsSite(Path file, String site) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > 50L * 1024L * 1024L) {
            throw new IOException("The current RFS PDF is empty or too large to verify. No decision was submitted.");
        }
        try (PDDocument document = PDDocument.load(file.toFile())) {
            if (document.getNumberOfPages() == 0 || document.getNumberOfPages() > 500) {
                throw new IOException("The current RFS PDF page count cannot be verified. No decision was submitted.");
            }
            String text = new PDFTextStripper().getText(document).toUpperCase(Locale.ROOT);
            if (text.trim().isEmpty()) throw new IOException("The RFS PDF has no extractable text. No decision was submitted.");
            // A site must be a complete identifier, never the prefix of another site's code.
            return Pattern.compile("(?<![A-Z0-9_-])" + Pattern.quote(site.toUpperCase(Locale.ROOT))
                    + "(?![A-Z0-9_-])").matcher(text).find();
        }
    }
}
