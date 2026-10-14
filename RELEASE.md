# Releases

The current release is **1.1.3**, with Git tag **v1.1.3**. Its four tools are Approve PROMS, Approve by Ref, Delete Site, and TK07 Review. They share one account saved without encryption, one ChromeDriver, and one External / Internal portal selection. Sign in is submitted once automatically; CAPTCHA remains a user action, and failed sign-ins are not retried.

The shared account is saved without encryption in `config/credentials.properties`; the dialog states that anyone who can read the file can see the account. Passwords remain masked in the editor unless Show password is selected. Cancel discards edits and empty passwords are rejected. Account storage no longer uses DPAPI or a PowerShell subprocess.

On first startup after upgrading from the encrypted format, the new application deletes `config/credentials.dat` without decrypting it, removes stale module account fields, and prompts for account entry again. A current plaintext account is preserved. Cleanup happens after successful installation when the new application starts, so installation rollback still retains the old account. Subsequent updates preserve the plaintext account and all task data.

Approve PROMS provides four separate AND condition fields and requires at least one filled field; TK07 Review provides two. Page readiness and completion of the current search result are checked automatically; there is no manual delay setting. Version 1.1.1 fixes confirmation-button checkmarks being mistaken for success responses and supports the actual stacked comment/success dialogs. Failures identify whether confirmation was sent, and uncertain submissions are never retried automatically. On the supported Angular page, verified native filtered data avoids visiting every page to collect candidates; unsupported layouts retain checked pagination. Approve by Ref retains its non-empty, Services-only FBOQ rule and stops before approval if its C-Part table has additional pages. TK07 preserves its canonical project/site rules and skips ambiguous, non-PDF, unreadable, or image-only RFS evidence.

The English interface, startup update dialog, visible installation progress, automatic reopen, and direct JAR launch are retained. Request TUAR remains a separate application and is excluded from current packages. The first release was **1.0.0**. Keep published release tags and assets unchanged when publishing future versions.

`version.properties` is the only version source. Both PowerShell and NetBeans/Ant copy it into the launcher and apply `app.version` to the application JAR manifests. The launcher passes that version to its child programs.

| Change | Next version example |
|---|---|
| Compatible bug fix or interface correction | 1.0.0 → 1.0.1 |
| Compatible new feature | 1.0.0 → 1.1.0 |
| Incompatible change requiring migration | 1.0.0 → 2.0.0 |

Keep application names stable: `TrueOpsToolkit.jar`, `TrueOps-Approve.jar`, `TrueOps-ApproveByRef.jar`, `TrueOps-DeleteSite.jar`, and `TrueOps-TK07.jar`. The release ZIP and Git tag carry the version. There is no `modules/tuar` folder, TUAR shortcut, compatibility JAR, implementation, or input template in new release packages.

## Prepare the files

1. Set `app.version` in `version.properties` and update `CHANGELOG.md`.
2. Close the toolkit and run:

```powershell
.\build.ps1
.\test.ps1
.\release.ps1 -SkipBuild
.\test-release.ps1
```

For a build and package in one command, use `.\release.ps1`. Supply `-JdkHome 'C:\path\to\jdk'` when needed. Packaging requires JDK 8 or later. Tests use local/synthetic data; ChromeDriver preparation may contact Google's official download endpoints.

For version 1.1.3, packaging produces:

- `releases/TrueOpsToolkit-1.1.3-windows.zip`
- `releases/TrueOpsToolkit-1.1.3-windows.zip.sha256`
- `releases/update.properties`

The ZIP is assembled from an explicit allowlist of application JARs, dependency JARs, documentation, and newly generated inputs. It never copies personal `dist` contents recursively. The packaging policy rejects `.exe`, `.bat`, `.cmd`, and standalone ChromeDriver binaries, including inside bundled archives. Users open `TrueOpsToolkit.jar` directly. Reference CSVs have no rows. Each module has fresh `settings.csv` preferences and no account credentials. TK07 defaults Search to TK07 and leaves both approval and rejection comments empty. No downloaded PDFs or original standalone settings/logs are bundled. The TUAR implementation, dependencies, workbook, and attachments template are excluded. Users configure the shared account and portal mode after extraction; ChromeDriver is prepared automatically during startup and checked again before browser use.

PROMS personal runtime data in `dist` is kept by a normal build. Before removing an existing `modules/tuar` folder, its contents are archived outside the Toolkit under `%LOCALAPPDATA%\TrueOpsToolkit\backups`. Runtime startup and update installation also retire that legacy folder safely. Private `tools/*/seed` data is never copied back into `dist` or into a release. Existing PROMS `Login.txt` preferences are migrated to `settings.csv` on first use.

## Publish in Git

Commit the reviewed source, set tag `v1.1.3` on that commit, then create the release using the corresponding changelog and attach all three files above. For future releases, substitute the version from `version.properties`.

The app update feed is `https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest/download/update.properties`. The generated descriptor contains `app.version`, the versioned `archive.url`, `archive.sha256`, and `archive.root`. Publish the descriptor together with its exact ZIP, because rebuilding the ZIP changes its checksum. The application updater replaces application files while retaining the shared account and PROMS personal runtime inputs. Its visible installer waits for released files, verifies and installs the staged update, and restores the previous application files if installation fails. A successful install automatically reopens the Toolkit.

The release script and automatic updater support stable `MAJOR.MINOR.PATCH` versions. Development prereleases can be built locally, but are not distributed through the stable update feed. `test-release.ps1` also stages the actual ZIP through the updater's validation path in a temporary folder, without installing it over any user data.

Do not upload the personal `dist` folder or private seed files. `.gitignore` excludes generated releases, runtime files, credentials, workbooks, and reference CSVs; it does not untrack a file already committed. Review staged files before committing. This preparation script does not initialize Git, create tags, push, or publish releases.

For a fresh install, users extract the release and open `TrueOpsToolkit.jar`. Opening an individual module JAR also routes through the toolkit. The application updater retains existing runtime inputs and the plaintext shared account; a manual install into a new folder starts with blank inputs and requires configuring the account. The plaintext account is readable by anyone with file access and is excluded from releases and Git.

Installations on 1.0.2 through 1.1.2 update to 1.1.3 with visible installation progress and automatic reopen; the old encrypted account is cleared and must be entered again. The installer in 1.0.1 needs one manual reopen after its first upgrade. The 1.0.0 update validator requires TUAR files, so that version needs one manual upgrade. Extract the release separately and copy the saved portal mode, module preferences, reference files, and other task data into matching locations. Do not copy the old encrypted account. If only a legacy `Login.txt` exists, copy its preferences as `settings.csv` over the release template. Keep the new application JARs and libraries and configure the shared account again.

Approval validation uses local Selenium fixtures and generated PDFs to exercise asynchronous loading, native filtered data, matching, exact selection, FBOQ/TK07 eligibility, and confirmation failures. Actual Internal portal checks through VPN were read-only: 45 matching records for either Search letter case, one exact reference with four Services-only FBOQ rows, and no pending TK07 matches. No real requests were approved, rejected, or deleted during testing. Record local fixtures and actual read-only checks separately; neither establishes a successful production approval.
