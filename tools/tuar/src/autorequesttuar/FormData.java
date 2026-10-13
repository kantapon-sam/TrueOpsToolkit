package autorequesttuar;

import java.nio.file.Path;

final class FormData {

    final OptionItem requestType;
    final OptionItem service;
    final String subject;
    final String detail;
    final OptionItem purpose;
    final OptionItem accountType;
    final OptionItem expiry;
    final String contactNumber;
    final String requireDate;
    final String userAmount;
    final boolean requestForVendor;
    final Path attachFile;
    final String attachmentProblem;
    final int workbookRow;

    FormData(
            OptionItem requestType,
            OptionItem service,
            String subject,
            String detail,
            OptionItem purpose,
            OptionItem accountType,
            OptionItem expiry,
            String contactNumber,
            String requireDate,
            String userAmount,
            boolean requestForVendor,
            Path attachFile,
            String attachmentProblem,
            int workbookRow) {
        this.requestType = requestType;
        this.service = service;
        this.subject = subject;
        this.detail = detail;
        this.purpose = purpose;
        this.accountType = accountType;
        this.expiry = expiry;
        this.contactNumber = contactNumber;
        this.requireDate = requireDate;
        this.userAmount = userAmount;
        this.requestForVendor = requestForVendor;
        this.attachFile = attachFile;
        this.attachmentProblem = attachmentProblem;
        this.workbookRow = workbookRow;
    }
}
