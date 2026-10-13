# TrueOps Toolkit

A Windows desktop launcher for four PROMS tools, with one encrypted shared account and one active tool at a time.

**Current version: 1.1.0** · [Download the latest release](https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest) · [Changelog](CHANGELOG.md)

## Get started

1. Download `TrueOpsToolkit-1.1.0-windows.zip` from Releases and extract the complete folder.
2. Open `TrueOpsToolkit.jar` directly. Windows, Java 8 or later, Windows PowerShell, and Google Chrome are required. If JAR files are not associated with Java, run `java -jar TrueOpsToolkit.jar` from the extracted folder.
3. Select **Shared account**, enter your username and password, and save. All four tools use this account.
4. Select **Portal settings** and choose External or Internal. Connect to the company network or VPN before using Internal.
5. Open a tool, configure its settings and request list, and review the data before starting.

| Tool | Settings available in the application | Application file |
|---|---|---|
| Approve PROMS | Four AND conditions, search wait, and description | `TrueOps-Approve.jar` |
| Approve by Ref | Reference number, type, description, and search wait | `TrueOps-ApproveByRef.jar` |
| Delete Site | Reference number and deletion reason table | `TrueOps-DeleteSite.jar` |
| TK07 Review | Two AND conditions, search wait, approval comment, and rejection comment | `TrueOps-TK07.jar` |

The launcher and all four tools use an English interface. Opening an individual module JAR also starts the Toolkit and uses the shared account. Application filenames stay the same between releases; the complete Toolkit uses one version number. Request TUAR is a separate application. Its folder, shortcut, implementation, libraries, and input templates are excluded from the Toolkit release and current source.

The release ZIP contains no ChromeDriver binary, `.exe`, `.bat`, or `.cmd` files, including inside bundled JARs. ChromeDriver is downloaded automatically from Google when the Toolkit starts. An internet connection is required for that first download. Java and Chrome must already be installed; their installers are not bundled.

## Accounts and settings

The username and password are encrypted together using **Windows DPAPI CurrentUser** in `config/credentials.dat`. There is no separate key file, and account values are excluded from `settings.csv` and individual tool settings. Decryption requires the Windows account that saved the credentials, normally on the same computer. Programs running under that Windows account can also decrypt them. Each recipient of a distributed release should configure their own account in the application.

Edit the account from the launcher or the **Shared account settings** button in a tool. Every tool uses the latest saved account. When editing, leave the password empty to retain the saved password, or remove the saved account before configuring a new one. If the account file cannot be read, the tool stops before signing in and asks you to configure the shared account again.

If the Toolkit has no saved shared account, it checks legacy account files inside its module folders in this order: Approve PROMS, Approve by Ref, Delete Site, and TK07 Review. It verifies that the encrypted account can be read back before removing the old account fields. An existing shared account is retained. Original standalone project folders are not imported or modified automatically.

Each PROMS tool keeps its task preferences in `settings.csv`. TK07 Review stores `Search`, `Description_Approve`, and `Description_Reject`; the search tools also store `SearchDelayMs`. Legacy `Login.txt` files inside a Toolkit module are renamed or retained as a legacy preferences backup on first use; existing current preferences remain authoritative. The settings file contains no account credentials.

**Portal settings:** One mode applies to every tool and is reloaded before each run. External uses `https://proms.truecorp.co.th/proms/login/`; Internal uses `http://promsweb.true.th/proms/login` and requires the company network or VPN. The mode is saved in `config/portal.properties` and retained across updates. The Toolkit supports both sign-in layouts. Complete CAPTCHA whenever the selected portal requests it.

The four PROMS tools provide **Save settings** and **Reload saved settings**. Saving settings does not start a task. Starting a task validates and saves the current settings and automatically clicks Sign in once. Complete CAPTCHA in Chrome if requested; failed sign-ins are not retried automatically. Approve PROMS has four separate AND condition fields and requires at least one filled field. Every filled field must match; blank fields are ignored. TK07 Review has two condition fields with the same AND rule and requires at least one filled field. Existing `Search,TK08&&ATTS` files load into separate fields and remain compatible. The description in Approve by Ref and both TK07 comments may be empty.

**Search wait (ms):** Approve PROMS, Approve by Ref, and TK07 Review provide a minimum settling wait from 1000 to 10000 milliseconds; the default is 2000. It is saved as `SearchDelayMs`. The program also waits for the initial Pending dataset and the actual current search response. A delay never replaces confirmation that the correct result finished loading.

**Approve PROMS:** Search matching ignores letter case. A standalone `TK` code followed by digits, such as `tk09`, matches a complete token in the **Request Type category** before its site suffix; it does not match `TK090`, `TK09X`, or text in Project Name, Requester, or the site identifier. Other terms match the row's searchable text. All filled conditions must match. After the search completes, the supported PROMS Angular page provides its complete filtered dataset directly, so the tool does not need to visit every page to collect candidates. It verifies the dataset against the table count and visible rows. Unsupported page layouts use checked pagination. Each approval still searches for and selects only its exact pending reference, then verifies the result.

**Approve by Ref:** Ref No. matches the complete reference, ignoring letter case. The **Type** column is the request category, such as `TK09`; it matches a complete category token, not part of a different code or a site identifier. It is separate from the FBOQ **C-Part Type** check: approval still requires at least one FBOQ row and every C-Part Type must be **Services**. If the C-Part table explicitly has additional pages, the tool stops before submitting approval and asks you to verify the complete FBOQ manually. Unpaginated and single-page C-Part tables are supported. The tool returns to the pending list for each reference and uses that input row's Description.

Both approval tools show scanned, matched, approved, skipped, and failed counts. An approval is counted only after PROMS confirms it and a fresh pending-list search no longer contains that exact reference. No-match runs and partial failures are reported explicitly. An unconfirmed submission stops the run and requires checking the reference in PROMS before retrying.

Delete Site asks for confirmation before starting. Stopping or closing a tool does not undo actions already completed on the website.

**TK07 Review:** Requires a complete TK07 category token and one canonical project-name phrase: `single grid`, `consolida`, `blackcell grey`, `asg card expansion`, `geo b2b`, `support olt small pocket`, or `ip clock`. Site normalization removes a leading `D`; otherwise it removes a trailing `Z`, `A`, or `T`. After confirming that the exact request's attachment list has loaded, it approves when a readable RFS PDF contains the complete normalized site identifier, or rejects when the loaded list has no RFS attachment or a readable PDF lacks that identifier. It uses the separate saved approval/rejection comments.

Multiple RFS attachments, non-PDF RFS files, unreadable or image-only PDFs, and unknown attachment loading state are skipped. An unconfirmed download stops the run before reviewing another request, so a late file cannot become another request's evidence. Downloads are isolated by run; original PDFs and standalone data are retained. PDF contents are not printed to task logs. TK07 reports approved and rejected counts only after confirmation and a fresh pending-list check.

## Automatic updates

**Startup dialog:** Opening the Toolkit automatically checks both the application and ChromeDriver. A progress dialog shows the checks and downloads; you do not need to press a check button. If a new application version is ready, select **Update now** to close the Toolkit and install it, or **Later** to install when you finish and close the Toolkit. Updates never stop an active tool.

**Installation progress and reopen:** After the Toolkit closes, the installer displays its status while waiting for application files, verifying the update, and installing the new version. It keeps a backup so it can restore the previous application files if installation fails. After a successful installation, it automatically opens the updated Toolkit.

**Toolkit application:** The launcher downloads newer releases from GitHub and verifies SHA256 against the release descriptor. Installation runs after the Toolkit closes and no module is active. The updater preserves the encrypted account, settings, Excel workbooks, CSV files, attachments, ChromeDriver, and logs. **Check for updates** remains available for an additional manual check. The installed version remains usable if the network is unavailable.

**ChromeDriver:** All four tools use the same `drivers/chromedriver.exe`. The Toolkit automatically checks the installed Chrome version and downloads the latest compatible driver from Google at startup and before opening Chrome. If the network is unavailable, an existing driver is used only if it is compatible, and the status is displayed. **Update ChromeDriver** remains available for an additional check while no task is open. The **Update / test Chrome** button in a PROMS tool also opens a local test page.

**Upgrading older installations:** Installations already on 1.0.2 use the visible installer and automatically reopen after updating to 1.1.0. The updater installed in 1.0.1 retains its earlier installation behavior for the first upgrade; open the Toolkit once after it finishes. Version 1.0.0 requires the retired TUAR files during update validation, so moving to this four-tool release requires one manual upgrade. Extract the new release into a separate folder, then copy your `config/credentials.dat`, `config/portal.properties` if present, each module's `settings.csv`, `Ref_No.csv`, and other personal task data into matching locations before opening it. If an old module has only `Login.txt`, copy its preferences as `settings.csv` over the new release's blank template. Keep the newly extracted application JARs and libraries. The encrypted account remains usable under the Windows account and computer where it was saved.

## Local data

When built from source, runtime files are in `dist`. In a release, they are in the extracted folder containing `TrueOpsToolkit.jar`.

| Location | Contents |
|---|---|
| `config/credentials.dat` | Encrypted shared account |
| `config/portal.properties` | Shared External / Internal mode |
| `modules/approve` | `settings.csv` containing search conditions, search wait, and description |
| `modules/approve-ref` | `settings.csv`, `Ref_No.csv`, and task logs |
| `modules/delete` | `settings.csv` and `Ref_No.csv` |
| `modules/tk07` | Search, approval/rejection comments, and isolated task downloads |
| `drivers` | Shared ChromeDriver |
| `logs` | Launcher and task logs |

When an existing Toolkit installation contains `modules/tuar`, its legacy data is archived outside the Toolkit under `%LOCALAPPDATA%\TrueOpsToolkit\backups` before that retired folder is removed. The same retirement applies when rebuilding an existing `dist` folder. PROMS runtime data is preserved, and builds never restore private files from `tools/*/seed`.

Distribute the ZIP created by `release.ps1`, which generates blank input files. A personal `dist` folder contains user data and is not a release package.

## Build and test

Open the project in NetBeans and select Build / Run (F6), or run these commands in PowerShell:

```powershell
.\build.ps1
.\test.ps1
.\release.ps1 -SkipBuild
.\test-release.ps1
```

A JDK 8 or later is required. Pass `-JdkHome 'C:\path\to\jdk'` when `JAVA_HOME` does not point to the JDK you want to use. Builds use stable JAR filenames and read the version from `version.properties`. See [RELEASE.md](RELEASE.md) for versioning and GitHub release instructions.

Tests use synthetic accounts to check encryption, migration, settings, updates, process locks, and all four tool windows. Chrome tests use local pages and generated PDFs to check case matching, native filtered data, checked pagination, exact selection, FBOQ eligibility, TK07 decisions, and confirmation failures. Pass `-SkipBrowsers` to skip driver preparation and Chrome startup. Reports and screenshots are written to `build/test-reports`.

Actual Internal portal checks through VPN were read-only: lowercase and uppercase Search found the same 45 pending matches; one exact reference had four Services-only FBOQ rows; TK07 had no pending matches at the time of the check. No real requests were approved, rejected, or deleted during testing. Local fixtures and read-only previews do not establish a successful production approval run.

## Source layout

- `src/trueopstoolkit` — Launcher, tool controls, shared account, updates, and process management.
- `tools/<module>/src` — Individual tool sources, run in separate JVMs and working directories.
- `tools/<module>/lib` — Dependencies for each tool.
- `tools/common/src` — Shared approval-page synchronization and confirmation checks compiled into all module JARs.
- `test` — Tests and generators for blank release inputs.
- `version.properties`, `build.ps1`, `build.xml`, `release.ps1` — Version, build, and packaging configuration.

Approve by Ref retains its FBOQ Services eligibility rule. Delete Site retains its existing workflow.
