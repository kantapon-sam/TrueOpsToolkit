# Releases

The current release is **1.0.1**, with Git tag **v1.0.1**. It includes Approve PROMS, Approve by Ref, and Delete Site. Request TUAR is maintained as a separate application. The English interface, encrypted shared account, automatic application and ChromeDriver checks in a startup dialog, and direct JAR launch are retained. The first release was **1.0.0**. Keep published release tags and assets unchanged when publishing future versions.

`version.properties` is the only version source. Both PowerShell and NetBeans/Ant copy it into the launcher and apply `app.version` to the application JAR manifests. The launcher passes that version to its child programs.

| Change | Next version example |
|---|---|
| Compatible bug fix or interface correction | 1.0.0 → 1.0.1 |
| Compatible new feature | 1.0.0 → 1.1.0 |
| Incompatible change requiring migration | 1.0.0 → 2.0.0 |

Keep application names stable: `TrueOpsToolkit.jar`, `TrueOps-Approve.jar`, `TrueOps-ApproveByRef.jar`, and `TrueOps-DeleteSite.jar`. The release ZIP and Git tag carry the version. A small `TrueOps-TUAR.jar` shortcut and manifest-only compatibility JAR remain so the 1.0.0 updater accepts the release. The shortcut opens the three-tool Toolkit; it contains no TUAR workflow or dependencies.

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

For version 1.0.1, packaging produces:

- `releases/TrueOpsToolkit-1.0.1-windows.zip`
- `releases/TrueOpsToolkit-1.0.1-windows.zip.sha256`
- `releases/update.properties`

The ZIP is assembled from an explicit allowlist of application JARs, dependency JARs, documentation, and newly generated blank inputs. It never copies personal `dist` contents recursively. The packaging policy rejects `.exe`, `.bat`, `.cmd`, and standalone ChromeDriver binaries, including inside bundled archives. Users open `TrueOpsToolkit.jar` directly. Reference CSVs have no rows. Each PROMS module has `settings.csv` with empty Search/Description preferences and no account credentials. The TUAR implementation, dependencies, workbook, and attachments template are excluded. Users set the shared account in the toolkit after extraction; ChromeDriver is downloaded automatically during startup and checked again before browser use.

Existing personal runtime data in `dist` is kept by a normal build, including any legacy TUAR inputs. Private `tools/*/seed` data is never copied back into `dist` or into a release. Existing PROMS `Login.txt` preferences are migrated to `settings.csv` on first use.

## Publish in Git

Commit the reviewed source, set tag `v1.0.1` on that commit, then create the release using the corresponding changelog and attach all three files above. For future releases, substitute the version from `version.properties`.

The app update feed is `https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest/download/update.properties`. The generated descriptor contains `app.version`, the versioned `archive.url`, `archive.sha256`, and `archive.root`. Publish the descriptor together with its exact ZIP, because rebuilding the ZIP changes its checksum. The application updater replaces application files while retaining the shared account and personal runtime inputs.

The release script and automatic updater support stable `MAJOR.MINOR.PATCH` versions. Development prereleases can be built locally, but are not distributed through the stable update feed. `test-release.ps1` also stages the actual ZIP through the updater's validation path in a temporary folder, without installing it over any user data.

Do not upload the personal `dist` folder or private seed files. `.gitignore` excludes generated releases, runtime files, credentials, workbooks, and reference CSVs; it does not untrack a file already committed. Review staged files before committing. This preparation script does not initialize Git, create tags, push, or publish releases.

For a fresh install, users extract the release and open `TrueOpsToolkit.jar`. Opening an individual module JAR also routes through the toolkit. The application updater retains existing runtime inputs and the shared account; a manual install into a new folder starts with blank inputs and requires configuring the account. The Windows-protected saved account belongs to the Windows user on the computer that saved it; it is not a portable account file.
