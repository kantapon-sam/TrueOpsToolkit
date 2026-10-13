# Changelog

## 1.1.2 — 2026-10-13

- Redesign the shared account dialog with a clear saved-account state, larger fields, and Show password beside the Password heading.
- Load the actual saved password into the editor, masked as dots by default. Show password now reveals saved passwords as well as newly typed values.
- Save username-only edits without losing the existing password; reject blank replacements and preserve the vault when cancelling. Reset visibility and clear the editor when the dialog closes.
- Keep Windows account encryption and add synthetic dialog tests for reveal/hide, reopening, editing, cancellation, empty passwords, and unreadable account files.

## 1.1.1 — 2026-10-13

- Fix approval stopping before confirmation because the comment button's checkmark was mistaken for an earlier success response.
- Recognize the PROMS success dialog separately and handle its response while the original comment dialog remains open. Preserve exact-reference pending verification and never retry an uncertain submission automatically.
- Report whether confirmation was attempted and the stage where the run stopped. Apply the shared fix to Approve PROMS, Approve by Ref, and TK07 Review.
- Remove the Search wait control and fixed minimum delay. Wait for document readiness, initial pending data, and the current search response automatically. Ignore legacy delay settings and remove them on the next save.
- Add synthetic browser regressions based on observed PROMS markup, including checkmark confirmation buttons, stacked responses, ambiguous or stale dialogs, and slow search results. Production inspection remains read-only.

## 1.1.0 — 2026-10-13

- Add TK07 Review with shared encrypted login and ChromeDriver, editable Search and approval/rejection comments, and isolated PDF downloads.
- Preserve the canonical TK07 project/site rules. Skip multiple or non-PDF RFS attachments, unreadable/image-only PDFs, and unverified loading; stop on an unconfirmed download before reviewing another request. Keep original PDFs and exclude PDF contents from logs.
- Add one shared External / Internal portal mode, including the internal sign-in layout and VPN endpoint. Preserve the mode during application updates.
- Automatically click one unique Sign in control and hand CAPTCHA completion to the user when needed; never retry a failed sign-in automatically.
- Replace the joined Search editor with four optional AND condition fields in Approve PROMS, requiring at least one; TK07 Review has two fields. Every filled condition must match. Preserve the existing `&&` file format.
- Add editable `SearchDelayMs` from 1000 to 10000 milliseconds, defaulting to 2000. Wait for the initial pending data and actual current search response as well as the minimum settling delay.
- Read the supported PROMS page's verified complete filtered dataset directly, avoiding a walk through every page before approval. Retain checked pagination for unsupported page layouts.
- Fix Approve PROMS Search matching so lowercase search text also matches uppercase request data.
- Scope standalone `TK` plus digits search codes to complete Request Type category tokens before the site suffix; exclude similar codes and matches in project, requester, or site text. Other terms retain row-text search, and every filled AND condition must match.
- Match Approve by Ref using complete reference identity and request-category tokens, ignoring letter case; keep the non-empty, Services-only FBOQ eligibility rule.
- Stop before approval when the C-Part Type table explicitly has additional pages; allow unpaginated and single-page FBOQ tables.
- Wait for the current filtered result to finish loading, collect unique candidates, and select only the exact reference intended for approval. Unsupported page layouts retain response checks and checked pagination.
- Return to the pending list for each reference and retain each input row's Description.
- Count an approval only after a confirmation response and a fresh pending-list search show that the exact request is no longer pending.
- Show scanned, matched, approved, skipped, and failed progress; report no-match runs and partial failures instead of presenting them as confirmed approvals.
- Add local Selenium and generated-PDF fixtures. Actual Internal checks through VPN were read-only: 45 case-independent Search matches, one exact reference with four Services-only FBOQ rows, and no pending TK07 matches. No real requests were approved, rejected, or deleted during testing.
- Retain the visible update installer, automatic reopen, encrypted shared account, and automatic ChromeDriver preparation.

## 1.0.2 — 2026-10-13

- Show a visible installation progress dialog after the Toolkit closes, including waiting for application files, update verification, installation, and rollback if needed.
- Automatically reopen the updated Toolkit after a successful installation.
- Remove the retired TUAR folder, shortcut, and compatibility marker from current source and release packages.
- Archive legacy TUAR runtime data outside the Toolkit under the Windows user's local application data before removing the retired folder.
- Keep the three PROMS tools, `settings.csv` preferences, shared encrypted account, and automatic application and ChromeDriver checks.
- Installations of 1.0.1 use their existing installer for the first upgrade and need one manual reopen. Installations of 1.0.0 need one manual application-file upgrade because their update validator requires the retired TUAR files.

## 1.0.1 — 2026-10-13

- Remove Request TUAR from the launcher because it is maintained as a separate application.
- Keep Approve PROMS, Approve by Ref, and Delete Site, with an updated three-tool launcher layout.
- Exclude the TUAR implementation, libraries, and blank workbook from new release packages.
- Retain a small legacy TUAR shortcut and compatibility marker for automatic updates from 1.0.0; the shortcut opens the three-tool Toolkit.
- Rename PROMS preferences to `settings.csv`, automatically migrating Search and Description from existing `Login.txt` files without storing account credentials.
- Retain the shared encrypted account and automatic application and ChromeDriver updates for the three PROMS tools.

## 1.0.0 — 2026-10-13

First release of TrueOps Toolkit.

- One desktop launcher for Request TUAR, Approve PROMS, Approve by Ref, and Delete Site.
- English interface throughout the launcher and all four tools, including dialogs, settings, status messages, and updates.
- One shared account for all four tools, protected using Windows user encryption; credentials are excluded from plain text settings.
- Settings, reference lists, and deletion reasons can be edited in the application.
- One shared ChromeDriver, downloaded and updated automatically to a compatible version during startup and before Chrome is used.
- A startup progress dialog checks application and ChromeDriver updates automatically. New application releases offer **Update now** or **Later** without requiring a manual check.
- All components use the same release version and stable JAR names without legacy version suffixes.
- Application update checks use the GitHub release descriptor and verify the downloaded archive's SHA256 before installation.
- Application updates preserve the shared account, settings, request files, attachments, driver, and logs.
- Open `TrueOpsToolkit.jar` directly. The release contains no ChromeDriver binary, `.exe`, `.bat`, or `.cmd` files; packaging validates nested archives as well.
- Opening a module JAR routes through the toolkit so every supported entry point uses the shared account.
- Release packaging creates blank inputs and excludes personal runtime data.
