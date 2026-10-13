# Releases

The first release is **1.0.0**, with Git tag **v1.0.0**. It includes an English interface, one encrypted shared account, automatic application and ChromeDriver checks in a startup dialog, and direct JAR launch without bundled native executables or batch launchers. Keep published release tags and assets unchanged when publishing future versions.

`version.properties` is the only version source. Both PowerShell and NetBeans/Ant copy it into the launcher and apply `app.version` to all five JAR manifests. The launcher passes that version to its child programs.

| Change | Next version example |
|---|---|
| Compatible bug fix or interface correction | 1.0.0 → 1.0.1 |
| Compatible new feature | 1.0.0 → 1.1.0 |
| Incompatible change requiring migration | 1.0.0 → 2.0.0 |

Keep executable names stable: `TrueOpsToolkit.jar`, `TrueOps-TUAR.jar`, `TrueOps-Approve.jar`, `TrueOps-ApproveByRef.jar`, and `TrueOps-DeleteSite.jar`. The release ZIP and Git tag carry the version.

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

For version 1.0.0, packaging produces:

- `releases/TrueOpsToolkit-1.0.0-windows.zip`
- `releases/TrueOpsToolkit-1.0.0-windows.zip.sha256`
- `releases/update.properties`

The ZIP is assembled from an explicit allowlist of application JARs, dependency JARs, documentation, and newly generated blank inputs. It never copies personal `dist` contents recursively. The packaging policy rejects `.exe`, `.bat`, `.cmd`, and standalone ChromeDriver binaries, including inside bundled archives. Users open `TrueOpsToolkit.jar` directly. The TUAR workbook contains column headers and an empty Options sheet; the program refreshes its choices from the portal when used. Reference CSVs have no rows. `Login.txt` contains only empty Search/Description settings. Users set the shared account in the toolkit after extraction; ChromeDriver is downloaded automatically during startup and checked again before browser use.

Existing runtime data in `dist` is kept by a normal build. Private `tools/*/seed` data is never copied back into `dist` or into a release. A successful build removes only the four known obsolete module JAR names after creating their replacements.

## Publish in Git

Commit the reviewed source, set tag `v1.0.0` on that commit, then create the release using the corresponding changelog and attach all three files above. For future releases, substitute the version from `version.properties`.

The app update feed is `https://github.com/kantapon-sam/TrueOpsToolkit/releases/latest/download/update.properties`. The generated descriptor contains `app.version`, the versioned `archive.url`, `archive.sha256`, and `archive.root`. Publish the descriptor together with its exact ZIP, because rebuilding the ZIP changes its checksum. The application updater replaces application files while retaining the shared account and personal runtime inputs.

The release script and automatic updater support stable `MAJOR.MINOR.PATCH` versions. Development prereleases can be built locally, but are not distributed through the stable update feed. `test-release.ps1` also stages the actual ZIP through the updater's validation path in a temporary folder, without installing it over any user data.

Do not upload the personal `dist` folder or private seed files. `.gitignore` excludes generated releases, runtime files, credentials, workbooks, and reference CSVs; it does not untrack a file already committed. Review staged files before committing. This preparation script does not initialize Git, create tags, push, or publish releases.

For a fresh install, users extract the release and open `TrueOpsToolkit.jar`. Opening an individual module JAR also routes through the toolkit. The application updater retains existing runtime inputs and the shared account; a manual install into a new folder starts with blank inputs and requires configuring the account. The Windows-protected saved account belongs to the Windows user on the computer that saved it; it is not a portable account file.
