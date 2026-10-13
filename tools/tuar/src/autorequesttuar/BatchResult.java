package autorequesttuar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BatchResult {

    private final int submittedCount;
    private final List<SkippedRequest> skippedRequests;

    BatchResult(
            int submittedCount,
            List<SkippedRequest> skippedRequests) {
        this.submittedCount = submittedCount;
        this.skippedRequests = Collections.unmodifiableList(
                new ArrayList<>(skippedRequests));
    }

    int getSubmittedCount() {
        return submittedCount;
    }

    List<SkippedRequest> getSkippedRequests() {
        return skippedRequests;
    }

    static final class SkippedRequest {

        private final int workbookRow;
        private final String service;
        private final String reason;

        SkippedRequest(
                int workbookRow, String service, String reason) {
            this.workbookRow = workbookRow;
            this.service = service;
            this.reason = reason;
        }

        int getWorkbookRow() {
            return workbookRow;
        }

        String getService() {
            return service;
        }

        String getReason() {
            return reason;
        }
    }
}
