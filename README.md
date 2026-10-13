# TrueOps Toolkit

A Windows desktop launcher for three PROMS tools, with one encrypted shared account and one active tool at a time.

**Current version: 1.0.1** · [Download the latest release](https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest) · [Changelog](CHANGELOG.md)

## Get started

1. Download `TrueOpsToolkit-1.0.1-windows.zip` from Releases and extract the complete folder.
2. Open `TrueOpsToolkit.jar` directly. Windows, Java 8 or later, Windows PowerShell, and Google Chrome are required. If JAR files are not associated with Java, run `java -jar TrueOpsToolkit.jar` from the extracted folder.
3. Select **Shared account**, enter your username and password, and save. All three tools use this account.
4. Open a tool, configure its settings and request list, and review the data before starting.

| Tool | Settings available in the application | Application file |
|---|---|---|
| Approve PROMS | Search and description | `TrueOps-Approve.jar` |
| Approve by Ref | Reference number, type, and description table | `TrueOps-ApproveByRef.jar` |
| Delete Site | Reference number and deletion reason table | `TrueOps-DeleteSite.jar` |

The launcher and all three tools use an English interface. Opening an individual module JAR also starts the Toolkit and uses the shared account. Application filenames stay the same between releases; the complete Toolkit uses one version number. Request TUAR is now a separate application and is no longer offered in the Toolkit. The release retains a small legacy TUAR shortcut and compatibility marker so installations of 1.0.0 can update automatically. That shortcut opens the three-tool Toolkit; the TUAR implementation, dependencies, and workbook are not included.

The release ZIP contains no ChromeDriver binary, `.exe`, `.bat`, or `.cmd` files, including inside bundled JARs. ChromeDriver is downloaded automatically from Google when the Toolkit starts. An internet connection is required for that first download. Java and Chrome must already be installed; their installers are not bundled.

## Accounts and settings

The username and password are encrypted together using **Windows DPAPI CurrentUser** in `config/credentials.dat`. There is no separate key file, and account values are excluded from `settings.csv` and individual tool settings. Decryption requires the Windows account that saved the credentials, normally on the same computer. Programs running under that Windows account can also decrypt them. Each recipient of a distributed release should configure their own account in the application.

Edit the account from the launcher or the **Shared account settings** button in a tool. Every tool uses the latest saved account. When editing, leave the password empty to retain the saved password, or remove the saved account before configuring a new one. If the account file cannot be read, the tool stops before signing in and asks you to configure the shared account again.

When importing settings from the original standalone PROMS tools, the Toolkit checks existing accounts in this order: Approve PROMS, Approve by Ref, and Delete Site. It verifies that the encrypted account can be read back before removing the old account fields. Search, description, and reference data are retained.

Each PROMS tool keeps Search and Description preferences in `settings.csv`. Existing `Login.txt` preferences are migrated automatically on first use. The settings file contains no account credentials.

The three PROMS tools provide **Save settings** and **Reload saved settings**. Saving settings does not start a task. Starting a PROMS task validates and saves the current settings, then requires you to select Sign in in Chrome. Search is required and supports up to two conditions separated by `&&`. The description in Approve by Ref may be empty.

Delete Site asks for confirmation before starting. Stopping or closing a tool does not undo actions already completed on the website.

## Automatic updates

**Startup dialog:** Opening the Toolkit automatically checks both the application and ChromeDriver. A progress dialog shows the checks and downloads; you do not need to press a check button. If a new application version is ready, select **Update now** to close the Toolkit and install it, or **Later** to install when you finish and close the Toolkit. Open the app again after installation to use the new version. Updates never stop an active tool.

**Toolkit application:** The launcher downloads newer releases from GitHub and verifies SHA256 against the release descriptor. Installation runs after the Toolkit closes and no module is active. The updater preserves the encrypted account, settings, Excel workbooks, CSV files, attachments, ChromeDriver, and logs. **Check for updates** remains available for an additional manual check. The installed version remains usable if the network is unavailable.

**ChromeDriver:** All three tools use the same `drivers/chromedriver.exe`. The Toolkit automatically checks the installed Chrome version and downloads the latest compatible driver from Google at startup and before opening Chrome. If the network is unavailable, an existing driver is used only if it is compatible, and the status is displayed. **Update ChromeDriver** remains available for an additional check while no task is open. The **Update / test Chrome** button in a PROMS tool also opens a local test page.

## Local data

When built from source, runtime files are in `dist`. In a release, they are in the extracted folder containing `TrueOpsToolkit.jar`.

| Location | Contents |
|---|---|
| `config/credentials.dat` | Encrypted shared account |
| `modules/approve` | `settings.csv` containing only search and description settings |
| `modules/approve-ref` | `settings.csv`, `Ref_No.csv`, and task logs |
| `modules/delete` | `settings.csv` and `Ref_No.csv` |
| `drivers` | Shared ChromeDriver |
| `logs` | Launcher and task logs |

Rebuilding preserves existing runtime data and never restores private files from `tools/*/seed`. Distribute the ZIP created by `release.ps1`, which generates blank input files. A personal `dist` folder contains user data and is not a release package.

## Build and test

Open the project in NetBeans and select Build / Run (F6), or run these commands in PowerShell:

```powershell
.\build.ps1
.\test.ps1
.\release.ps1 -SkipBuild
.\test-release.ps1
```

A JDK 8 or later is required. Pass `-JdkHome 'C:\path\to\jdk'` when `JAVA_HOME` does not point to the JDK you want to use. Builds use stable JAR filenames and read the version from `version.properties`. See [RELEASE.md](RELEASE.md) for versioning and GitHub release instructions.

Tests use synthetic accounts to check encryption, migration, settings, updates, process locks, and all three tool windows. Chrome tests use the shared driver with each tool's Selenium libraries on local test pages. They do not sign in to production systems or approve or delete real requests. Pass `-SkipBrowsers` to skip driver downloads and Chrome startup. Reports and screenshots are written to `build/test-reports`. Connectivity to PROMS and actual task results require separate testing against that system.

## Source layout

- `src/trueopstoolkit` — Launcher, tool controls, shared account, updates, and process management.
- `tools/<module>/src` — Individual tool sources, run in separate JVMs and working directories.
- `tools/<module>/lib` — Dependencies for each tool.
- `test` — Tests and generators for blank release inputs.
- `version.properties`, `build.ps1`, `build.xml`, `release.ps1` — Version, build, and packaging configuration.

Approval and deletion retain each tool's existing workflow.
