package autorequesttuar;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Creates and checks a release workbook from schema only, never from user data. */
public final class ReleaseTemplate {
    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args[0]);
        Files.createDirectories(file.toAbsolutePath().getParent());
        String[] columns = {"enabled", "request_type", "service", "subject", "detail",
            "purpose", "type_of_account", "expiry", "contact_number", "require_date",
            "user_amount", "request_for_vendor", "attach_file"};
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet requests = workbook.createSheet("Requests");
            workbook.createSheet("Options");
            requests.createFreezePane(0, 1);
            Font font = workbook.createFont();
            font.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(font);
            Row header = requests.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
                header.getCell(i).setCellStyle(headerStyle);
                requests.setColumnWidth(i, Math.max(18, columns[i].length() + 3) * 256);
            }
            try (OutputStream output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        try (InputStream input = Files.newInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() != 2
                    || workbook.getSheet("Requests").getPhysicalNumberOfRows() != 1
                    || workbook.getSheet("Options").getPhysicalNumberOfRows() != 0) {
                throw new AssertionError("Release workbook must contain schema only.");
            }
        }
        java.util.List<OptionItem> empty = Collections.emptyList();
        FormOptions options = new FormOptions(empty, empty, empty, empty, empty);
        if (!RequestWorkbookStore.load(file, options).isEmpty()) {
            throw new AssertionError("Release workbook must contain zero requests.");
        }
        System.out.println("PASS: release workbook schema accepted; no request data.");
    }
}
