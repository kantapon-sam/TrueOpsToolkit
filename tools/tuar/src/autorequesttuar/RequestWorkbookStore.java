package autorequesttuar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads one TUAR New Request per enabled row from requests.xlsx.
 */
final class RequestWorkbookStore {

    private static final String SHEET_NAME = "Requests";
    private static final String OPTIONS_SHEET_NAME = "Options";
    private static final int FIRST_DATA_ROW = 1;
    private static final int LAST_DATA_ROW = 1000;
    private static final int REQUIRE_DATE_OPTION_DAYS = 1096;
    private static final int REQUIRE_DATE_OPTIONS_COLUMN = 6;
    private static final DateTimeFormatter REQUIRE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu")
                    .withResolverStyle(
                            java.time.format.ResolverStyle.STRICT);

    private RequestWorkbookStore() {
    }

    /**
     * Replaces only the Options sheet values and the Requests dropdown rules.
     * Existing request rows are preserved.
     */
    static void refreshOptions(Path workbookFile, FormOptions options)
            throws IOException {
        if (!Files.isRegularFile(workbookFile)) {
            throw new IOException("File not found: " + workbookFile);
        }

        List<String> requestTypes = labels(options.getRequestTypes());
        List<String> services = labels(options.getServices());
        List<String> purposes = labels(options.getPurposes());
        List<String> accountTypes = labels(options.getAccountTypes());
        List<String> expiry = labels(options.getExpiryOptions());
        List<String> requireDates = requireDateOptions(
                LocalDate.now(), REQUIRE_DATE_OPTION_DAYS);
        List<String> booleans = new ArrayList<>();
        booleans.add("Y");
        booleans.add("N");

        requireOptions("Request Type", requestTypes);
        requireOptions("Service", services);
        requireOptions("Purpose", purposes);
        requireOptions("Type Of Account", accountTypes);
        requireOptions("Expiry", expiry);

        Path absoluteFile = workbookFile.toAbsolutePath().normalize();
        Path parent = absoluteFile.getParent();
        Path temporary = Files.createTempFile(
                parent, "requests-options-", ".xlsx");
        boolean written = false;
        try {
            try (InputStream input = Files.newInputStream(absoluteFile);
                    XSSFWorkbook workbook = new XSSFWorkbook(input)) {
                XSSFSheet requests =
                        workbook.getSheet(SHEET_NAME);
                XSSFSheet optionSheet =
                        workbook.getSheet(OPTIONS_SHEET_NAME);
                if (requests == null || optionSheet == null) {
                    throw new IllegalArgumentException(
                            "requests.xlsx must contain Requests and "
                            + "Options sheets.");
                }

                List<List<String>> columns = new ArrayList<>();
                columns.add(requestTypes);
                columns.add(services);
                columns.add(purposes);
                columns.add(accountTypes);
                columns.add(expiry);
                columns.add(booleans);
                columns.add(requireDates);
                updateOptionHeader(
                        optionSheet,
                        REQUIRE_DATE_OPTIONS_COLUMN,
                        "Require Date");
                updateOptionSheet(optionSheet, columns);
                updateValidations(
                        requests,
                        requestTypes.size(),
                        services.size(),
                        purposes.size(),
                        accountTypes.size(),
                        expiry.size(),
                        requireDates.size());

                try (OutputStream output =
                        Files.newOutputStream(temporary)) {
                    workbook.write(output);
                    written = true;
                }
            }

            if (written) {
                try {
                    Files.move(
                            temporary,
                            absoluteFile,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(
                            temporary,
                            absoluteFile,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    throw new IOException(
                            "Could not update requests.xlsx. Close the "
                            + "workbook in Excel and try again.",
                            ex);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static List<FormData> load(Path workbookFile, FormOptions options)
            throws IOException {
        Path absoluteWorkbook = workbookFile
                .toAbsolutePath()
                .normalize();
        Path parent = absoluteWorkbook.getParent();
        if (parent == null) {
            throw new IOException(
                    "Could not determine the requests.xlsx folder.");
        }
        return load(
                workbookFile,
                options,
                parent.resolve("attachments"));
    }

    static List<FormData> load(
            Path workbookFile,
            FormOptions options,
            Path attachmentsFolder) throws IOException {
        if (!Files.isRegularFile(workbookFile)) {
            throw new IOException("File not found: " + workbookFile);
        }
        if (attachmentsFolder == null) {
            throw new IllegalArgumentException(
                    "Attachments folder is required.");
        }

        try (InputStream input = Files.newInputStream(workbookFile);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new IllegalArgumentException(
                        "requests.xlsx must contain a Requests sheet.");
            }
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException(
                        "The Requests sheet does not contain a header.");
            }

            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> columns =
                    columnsByName(headerRow, formatter, evaluator);

            int enabled = optionalColumn(columns, "enabled");
            int requestType = requiredColumn(columns, "request_type");
            int service = requiredColumn(columns, "service");
            int subject = requiredColumn(columns, "subject");
            int detail = optionalColumn(columns, "detail");
            int purpose = optionalColumn(columns, "purpose");
            int accountType =
                    requiredColumn(columns, "type_of_account");
            int expiry = optionalColumn(columns, "expiry");
            int contactNumber =
                    requiredColumn(columns, "contact_number");
            int requireDate = requiredColumn(columns, "require_date");
            int userAmount = requiredColumn(columns, "user_amount");
            int vendor = optionalColumn(
                    columns, "request_for_vendor");
            int attachFile = optionalColumn(columns, "attach_file");

            List<FormData> requests = new ArrayList<>();
            for (int rowIndex = 1;
                    rowIndex <= sheet.getLastRowNum();
                    rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                int excelRow = rowIndex + 1;
                if (row == null
                        || isBlankRow(
                                row,
                                headerRow.getLastCellNum(),
                                formatter,
                                evaluator)) {
                    continue;
                }
                if (!booleanValue(
                        valueAt(row, enabled, formatter, evaluator),
                        false,
                        excelRow)) {
                    continue;
                }

                OptionItem requestTypeOption = findOption(
                        options.getRequestTypes(),
                        requiredText(
                                row,
                                requestType,
                                "request_type",
                                excelRow,
                                formatter,
                                evaluator),
                        "request_type",
                        excelRow);
                OptionItem serviceOption = findOption(
                        options.getServices(),
                        requiredText(
                                row,
                                service,
                                "service",
                                excelRow,
                                formatter,
                                evaluator),
                        "service",
                        excelRow);
                String subjectText = requiredText(
                        row,
                        subject,
                        "subject",
                        excelRow,
                        formatter,
                        evaluator);
                String detailText = valueAt(
                        row, detail, formatter, evaluator).trim();

                String purposeText = valueAt(
                        row, purpose, formatter, evaluator).trim();
                if (purposeText.isEmpty()) {
                    purposeText = "-";
                }
                OptionItem purposeOption = findOption(
                        options.getPurposes(),
                        purposeText,
                        "purpose",
                        excelRow);

                OptionItem accountOption = findOption(
                        options.getAccountTypes(),
                        requiredText(
                                row,
                                accountType,
                                "type_of_account",
                                excelRow,
                                formatter,
                                evaluator),
                        "type_of_account",
                        excelRow);
                if ("-1".equals(accountOption.getValue())) {
                    throw rowError(
                            excelRow,
                            "type_of_account must not be -");
                }

                String expiryText = valueAt(
                        row, expiry, formatter, evaluator).trim();
                if (expiryText.isEmpty()) {
                    expiryText = "-";
                }
                OptionItem expiryOption = findOption(
                        applicableExpiryOptions(options, accountOption),
                        expiryText,
                        "expiry",
                        excelRow);
                if ("2".equals(accountOption.getValue())
                        && "-".equals(expiryOption.getLabel())) {
                    throw rowError(
                            excelRow,
                            "expiry is required for an expiring account");
                }

                String contact = digits(
                        requiredText(
                                row,
                                contactNumber,
                                "contact_number",
                                excelRow,
                                formatter,
                                evaluator),
                        "contact_number",
                        excelRow);
                String date = normalizeRequireDate(
                        requiredText(
                                row,
                                requireDate,
                                "require_date",
                                excelRow,
                                formatter,
                                evaluator),
                        excelRow);
                String amount = digits(
                        requiredText(
                                row,
                                userAmount,
                                "user_amount",
                                excelRow,
                                formatter,
                                evaluator),
                        "user_amount",
                        excelRow);
                try {
                    if (Integer.parseInt(amount) <= 0) {
                        throw rowError(
                                excelRow,
                                "user_amount must be greater than 0");
                    }
                } catch (NumberFormatException ex) {
                    throw rowError(
                            excelRow, "user_amount is too large");
                }

                boolean requestForVendor = booleanValue(
                        valueAt(row, vendor, formatter, evaluator),
                        false,
                        excelRow);
                AttachmentReference attachment = attachment(
                        attachmentsFolder,
                        valueAt(row, attachFile, formatter, evaluator));

                requests.add(new FormData(
                        requestTypeOption,
                        serviceOption,
                        subjectText,
                        detailText,
                        purposeOption,
                        accountOption,
                        expiryOption,
                        contact,
                        date,
                        amount,
                        requestForVendor,
                        attachment.path,
                        attachment.problem,
                        excelRow));
            }
            return requests;
        }
    }

    private static List<OptionItem> applicableExpiryOptions(
            FormOptions options, OptionItem accountType) {
        if (accountType != null
                && "2".equals(accountType.getValue())) {
            return options.getExpiryOptions();
        }
        for (OptionItem option : options.getExpiryOptions()) {
            if ("-".equals(option.getLabel())) {
                List<OptionItem> placeholder = new ArrayList<>();
                placeholder.add(option);
                return placeholder;
            }
        }
        List<OptionItem> placeholder = new ArrayList<>();
        placeholder.add(new OptionItem("0", "-"));
        return placeholder;
    }

    private static void updateOptionSheet(
            XSSFSheet sheet, List<List<String>> columns) {
        int largestNewSize = 0;
        for (List<String> values : columns) {
            largestNewSize = Math.max(largestNewSize, values.size());
        }
        int lastRow = Math.max(sheet.getLastRowNum(), largestNewSize);
        Row styleRow = sheet.getRow(FIRST_DATA_ROW);

        for (int rowIndex = FIRST_DATA_ROW;
                rowIndex <= lastRow;
                rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
                if (styleRow != null) {
                    row.setHeight(styleRow.getHeight());
                }
            }
            for (int column = 0;
                    column < columns.size();
                    column++) {
                Cell cell = row.getCell(column);
                if (cell == null) {
                    cell = row.createCell(column);
                    CellStyle style = templateStyle(
                            styleRow, column);
                    if (style != null) {
                        cell.setCellStyle(style);
                    }
                }
                List<String> values = columns.get(column);
                int valueIndex = rowIndex - FIRST_DATA_ROW;
                cell.setCellValue(valueIndex < values.size()
                        ? values.get(valueIndex) : "");
            }
        }
    }

    private static void updateOptionHeader(
            XSSFSheet sheet, int column, String value) {
        Row header = sheet.getRow(0);
        if (header == null) {
            header = sheet.createRow(0);
        }
        Cell cell = header.getCell(column);
        if (cell == null) {
            cell = header.createCell(column);
            CellStyle style = templateStyle(header, 0);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
        cell.setCellValue(value);
    }

    private static CellStyle templateStyle(Row row, int column) {
        if (row == null) {
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            cell = row.getCell(0);
        }
        return cell == null ? null : cell.getCellStyle();
    }

    private static void updateValidations(
            XSSFSheet sheet,
            int requestTypeCount,
            int serviceCount,
            int purposeCount,
            int accountTypeCount,
            int expiryCount,
            int requireDateCount) {
        if (sheet.getCTWorksheet().isSetDataValidations()) {
            sheet.getCTWorksheet().unsetDataValidations();
        }

        DataValidationHelper helper = sheet.getDataValidationHelper();
        addExplicitListValidation(
                sheet, helper, 0, new String[]{"Y", "N"});
        addFormulaListValidation(
                sheet, helper, 1, optionFormula("A", requestTypeCount));
        addFormulaListValidation(
                sheet, helper, 2, optionFormula("B", serviceCount));
        addFormulaListValidation(
                sheet, helper, 5, optionFormula("C", purposeCount));
        addFormulaListValidation(
                sheet, helper, 6, optionFormula("D", accountTypeCount));
        addFormulaListValidation(
                sheet, helper, 7, optionFormula("E", expiryCount));
        addFormulaListValidation(
                sheet,
                helper,
                9,
                optionFormula("G", requireDateCount));

        DataValidationConstraint amountConstraint =
                helper.createNumericConstraint(
                        DataValidationConstraint.ValidationType.INTEGER,
                        DataValidationConstraint.OperatorType.BETWEEN,
                        "1",
                        "999999");
        addValidation(sheet, helper, 10, amountConstraint);
        addExplicitListValidation(
                sheet, helper, 11, new String[]{"Y", "N"});
    }

    private static void addExplicitListValidation(
            XSSFSheet sheet,
            DataValidationHelper helper,
            int column,
            String[] values) {
        addValidation(
                sheet,
                helper,
                column,
                helper.createExplicitListConstraint(values));
    }

    private static void addFormulaListValidation(
            XSSFSheet sheet,
            DataValidationHelper helper,
            int column,
            String formula) {
        addValidation(
                sheet,
                helper,
                column,
                helper.createFormulaListConstraint(formula));
    }

    private static void addValidation(
            XSSFSheet sheet,
            DataValidationHelper helper,
            int column,
            DataValidationConstraint constraint) {
        CellRangeAddressList range = new CellRangeAddressList(
                FIRST_DATA_ROW,
                LAST_DATA_ROW,
                column,
                column);
        DataValidation validation =
                helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static String optionFormula(
            String column, int count) {
        return "'" + OPTIONS_SHEET_NAME + "'!$" + column
                + "$2:$" + column + "$" + (count + 1);
    }

    private static List<String> labels(List<OptionItem> options) {
        List<String> result = new ArrayList<>();
        for (OptionItem option : options) {
            String label = option.getLabel().trim();
            if (!label.isEmpty()) {
                result.add(label);
            }
        }
        return result;
    }

    private static List<String> requireDateOptions(
            LocalDate firstDate, int numberOfDays) {
        List<String> result = new ArrayList<>();
        for (int offset = 0; offset < numberOfDays; offset++) {
            result.add(REQUIRE_DATE_FORMAT.format(
                    firstDate.plusDays(offset)));
        }
        return result;
    }

    private static void requireOptions(
            String name, List<String> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException(
                    "TUAR did not return any " + name + " options.");
        }
    }

    private static AttachmentReference attachment(
            Path attachmentsFolder, String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            return new AttachmentReference(null, null);
        }

        final Path supplied;
        try {
            supplied = java.nio.file.Paths.get(text);
        } catch (java.nio.file.InvalidPathException ex) {
            return new AttachmentReference(
                    null,
                    "Invalid attachment file name: " + text);
        }

        if (supplied.isAbsolute()
                || supplied.getNameCount() != 1
                || ".".equals(text)
                || "..".equals(text)
                || text.contains("/")
                || text.contains("\\")) {
            return new AttachmentReference(
                    null,
                    "attach_file must contain only a file name, "
                    + "not a path: " + text);
        }

        Path root = attachmentsFolder
                .toAbsolutePath()
                .normalize();
        Path resolved = root.resolve(supplied).normalize();
        if (!resolved.startsWith(root)) {
            return new AttachmentReference(
                    null,
                    "Attachment path is outside the attachments folder: "
                    + text);
        }
        if (!Files.isRegularFile(resolved)) {
            return new AttachmentReference(
                    null,
                    "Attachment was not found: " + resolved);
        }
        return new AttachmentReference(resolved, null);
    }

    private static final class AttachmentReference {

        private final Path path;
        private final String problem;

        AttachmentReference(Path path, String problem) {
            this.path = path;
            this.problem = problem;
        }
    }

    private static OptionItem findOption(
            List<OptionItem> options,
            String input,
            String column,
            int rowNumber) {
        for (OptionItem option : options) {
            if (option.getValue().equalsIgnoreCase(input)
                    || option.getLabel().equalsIgnoreCase(input)) {
                return option;
            }
        }
        throw rowError(
                rowNumber,
                column + " \"" + input
                + "\" was not found in the options loaded from TUAR");
    }

    private static String normalizeRequireDate(
            String value, int rowNumber) {
        String[] parts = value.trim().split("/");
        if (parts.length != 3) {
            throw rowError(
                    rowNumber,
                    "require_date must use DD/MM/YYYY, "
                    + "for example 30/07/2026");
        }
        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            if (year >= 2400) {
                year -= 543;
            }
            String normalized = String.format(
                    Locale.ROOT,
                    "%02d/%02d/%04d",
                    day,
                    month,
                    year);
            LocalDate.parse(normalized, REQUIRE_DATE_FORMAT);
            return normalized;
        } catch (NumberFormatException
                | DateTimeParseException ex) {
            throw rowError(
                    rowNumber,
                    "require_date is invalid: " + value
                    + ". Use DD/MM/YYYY, for example 30/07/2026");
        }
    }

    private static String requiredText(
            Row row,
            int column,
            String name,
            int rowNumber,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        String value = valueAt(
                row, column, formatter, evaluator).trim();
        if (value.isEmpty()) {
            throw rowError(rowNumber, name + " is required");
        }
        return value;
    }

    private static String digits(
            String value, String name, int rowNumber) {
        if (!value.matches("\\d+")) {
            throw rowError(
                    rowNumber, name + " must contain digits only");
        }
        return value;
    }

    private static boolean booleanValue(
            String value, boolean defaultValue, int rowNumber) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(text) || "yes".equals(text)
                || "y".equals(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "no".equals(text)
                || "n".equals(text) || "0".equals(text)) {
            return false;
        }
        throw rowError(
                rowNumber,
                "Boolean values must be TRUE/FALSE, YES/NO, or 1/0");
    }

    private static Map<String, Integer> columnsByName(
            Row header,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0;
                index < header.getLastCellNum();
                index++) {
            String name = valueAt(
                    header, index, formatter, evaluator)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.put(name, index);
            }
        }
        return columns;
    }

    private static int requiredColumn(
            Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) {
            throw new IllegalArgumentException(
                    "The Requests sheet must contain the column "
                    + name);
        }
        return index;
    }

    private static int optionalColumn(
            Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null ? -1 : index;
    }

    private static boolean isBlankRow(
            Row row,
            int columnCount,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        for (int index = 0; index < columnCount; index++) {
            if (!valueAt(row, index, formatter, evaluator)
                    .trim()
                    .isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String valueAt(
            Row row,
            int index,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        if (row == null || index < 0) {
            return "";
        }
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }
        if (cell.getCellTypeEnum() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return new SimpleDateFormat(
                    "dd/MM/yyyy", Locale.ROOT)
                    .format(cell.getDateCellValue());
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private static IllegalArgumentException rowError(
            int rowNumber, String message) {
        return new IllegalArgumentException(
                "Excel row " + rowNumber + ": " + message);
    }
}
