# Releases

The current release is **1.0.2**, with Git tag **v1.0.2**. It includes Approve PROMS, Approve by Ref, and Delete Site. Request TUAR is maintained as a separate application and its retired folder is no longer bundled. The installer shows progress and automatically reopens the updated Toolkit after successful installation. The English interface, encrypted shared account, automatic application and ChromeDriver checks in a startup dialog, and direct JAR launch are retained. The first release was **1.0.0**. Keep published release tags and assets unchanged when publishing future versions.

`version.properties` is the only version source. Both PowerShell and NetBeans/Ant copy it into the launcher and apply `app.version` to the application JAR manifests. The launcher passes that version to its child programs.

| Change | Next version example |
|---|---|
| Compatible bug fix or interface correction | 1.0.0 → 1.0.1 |
| Compatible new feature | 1.0.0 → 1.1.0 |
| Incompatible change requiring migration | 1.0.0 → 2.0.0 |

Keep application names stable: `TrueOpsToolkit.jar`, `TrueOps-Approve.jar`, `TrueOps-ApproveByRef.jar`, and `TrueOps-DeleteSite.jar`. The release ZIP and Git tag carry the version. There is no `modules/tuar` folder, TUAR shortcut, compatibility JAR, implementation, or input template in new release packages.

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

For version 1.0.2, packaging produces:

- `releases/TrueOpsToolkit-1.0.2-windows.zip`
- `releases/TrueOpsToolkit-1.0.2-windows.zip.sha256`
- `releases/update.properties`

The ZIP is assembled from an explicit allowlist of application JARs, dependency JARs, documentation, and newly generated blank inputs. It never copies personal `dist` contents recursively. The packaging policy rejects `.exe`, `.bat`, `.cmd`, and standalone ChromeDriver binaries, including inside bundled archives. Users open `TrueOpsToolkit.jar` directly. Reference CSVs have no rows. Each PROMS module has `settings.csv` with empty Search/Description preferences and no account credentials. The TUAR implementation, dependencies, workbook, and attachments template are excluded. Users set the shared account in the toolkit after extraction; ChromeDriver is downloaded automatically during startup and checked again before browser use.

PROMS personal runtime data in `dist` is kept by a normal build. Before removing an existing `modules/tuar` folder, its contents are archived outside the Toolkit under `%LOCALAPPDATA%\TrueOpsToolkit\backups`. Runtime startup and update installation also retire that legacy folder safely. Private `tools/*/seed` data is never copied back into `dist` or into a release. Existing PROMS `Login.txt` preferences are migrated to `settings.csv` on first use.

## Publish in Git

Commit the reviewed source, set tag `v1.0.2` on that commit, then create the release using the corresponding changelog and attach all three files above. For future releases, substitute the version from `version.properties`.

The app update feed is `https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest/download/update.properties`. The generated descriptor contains `app.version`, the versioned `archive.url`, `archive.sha256`, and `archive.root`. Publish the descriptor together with its exact ZIP, because rebuilding the ZIP changes its checksum. The application updater replaces application files while retaining the shared account and PROMS personal runtime inputs. Its visible installer waits for released files, verifies and installs the staged update, and restores the previous application files if installation fails. A successful install automatically reopens the Toolkit.

The release script and automatic updater support stable `MAJOR.MINOR.PATCH` versions. Development prereleases can be built locally, but are not distributed through the stable update feed. `test-release.ps1` also stages the actual ZIP through the updater's validation path in a temporary folder, without installing it over any user data.

Do not upload the personal `dist` folder or private seed files. `.gitignore` excludes generated releases, runtime files, credentials, workbooks, and reference CSVs; it does not untrack a file already committed. Review staged files before committing. This preparation script does not initialize Git, create tags, push, or publish releases.

For a fresh install, users extract the release and open `TrueOpsToolkit.jar`. Opening an individual module JAR also routes through the toolkit. The application updater retains existing runtime inputs and the shared account; a manual install into a new folder starts with blank inputs and requires configuring the account. The Windows-protected saved account belongs to the Windows user on the computer that saved it; it is not a portable account file.

The immutable installer already distributed in 1.0.1 performs the first upgrade with its earlier behavior, so users must open the Toolkit once after that installation. The progress dialog and automatic reopen apply to updates performed by the installed 1.0.2 updater and later. The 1.0.0 update validator requires TUAR files, which are removed from this release; users on 1.0.0 must make one manual application-file upgrade. To retain their data, extract the release into a separate folder and copy the encrypted account and each PROMS module's preferences, reference files, and personal task data into matching locations before opening the new Toolkit. Keep the application JARs and libraries from the new release.
