# Changelog

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
