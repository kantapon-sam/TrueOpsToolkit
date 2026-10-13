TrueOps Toolkit module sources
=============================

Version 1.1.2 includes an English launcher and English interfaces for four PROMS tools.

approve     Approve PROMS      TrueOps-Approve.jar
approve-ref Approve by Ref     TrueOps-ApproveByRef.jar
delete      Delete Site        TrueOps-DeleteSite.jar
tk07        TK07 Review        TrueOps-TK07.jar

src/ contains adapted module sources; lib/ contains selected dependencies
from the original NetBeans projects. Original standalone folders are not
modified by either build method.
Each module is compiled into its own JAR and keeps its own libraries.
The launcher starts each tool with that module's working directory.
tools/common/src contains approval-page synchronization and confirmation
checks and shared portal/sign-in helpers compiled into module JARs by either build method.

seed/ contains private legacy runtime files and is ignored by Git.
Neither build method copies seed data into dist or release packages.
release.ps1 generates new blank reference lists and settings.csv preferences.
Request TUAR is maintained as a separate application and is not a Toolkit task.
Current source and release packages exclude its folder, shortcut,
compatibility marker, implementation, dependencies, and workbook.
Existing modules/tuar runtime contents are archived outside the Toolkit under
%LOCALAPPDATA%\TrueOpsToolkit\backups before that retired folder is removed.
Every Toolkit module uses
dist/drivers/chromedriver.exe, automatically prepared during Toolkit startup
and checked again before browser startup. No driver binary, .exe, .bat, or
.cmd is included in the release. Open TrueOpsToolkit.jar directly.
Startup shows a progress dialog and checks application and driver updates
without a manual check. A new application release offers Update now or Later.
The manager migrates a compatible legacy driver if available and checks the
official Google release endpoint for a newer compatible version.
After the Toolkit closes for an application update, a visible installer
shows its progress while waiting for application files, verifying the
update, installing it, or restoring previous files after an installation
failure. Successful installation automatically reopens the updated Toolkit.
The installer already distributed with 1.0.1 still needs a manual reopen
after its first upgrade. Its next updates use the new installer.
The 1.0.0 update validator requires the retired TUAR files; those installations
need one manual application-file upgrade while retaining their personal data.
Changes made in the four PROMS runtime module folders are retained across builds.
Use the shared account dialog for every login. Username and password are
protected together with Windows DPAPI and are never saved in settings.csv.
Legacy Login.txt preferences inside Toolkit module folders migrate or remain
as a preferences backup; existing current settings remain authoritative.
Change task settings and PROMS reference lists through the module window.
Approve PROMS provides four separate AND condition fields and requires at
least one filled field; TK07 Review provides two. Every filled field must
match, and blank fields are ignored. Existing Search values separated by
&& remain compatible. Matching ignores letter case. Standalone TK codes
match complete Request Type category tokens before the site suffix, never
similar codes or text in project, requester, or site fields.
Approve PROMS, Approve by Ref, and TK07 Review wait for the page and the
current search result automatically. No manual delay setting is required.
Legacy SearchDelayMs is ignored. Verified Angular filtered data avoids visiting every page
to collect candidates; unsupported layouts retain checked pagination.
Each approval still rechecks and selects its exact pending reference.
Approve by Ref matches complete references and request-category tokens
without regard to case. The Type input is a category such as TK09, separate
from the FBOQ C-Part Type check. FBOQ must contain at least one row and every
C-Part Type must be Services. Each input row retains its own Description,
and each reference starts from the pending list. A C-Part table with
additional pages stops the run before approval; unpaginated and single-page
tables are supported.
Both approval tools count success only after PROMS confirms the submission
and a fresh search no longer lists the exact reference as pending. Progress
reports scanned, matched, approved, skipped, and failed counts, with explicit
no-match and partial-failure results. Unconfirmed submissions are not retried
automatically; review the reference in PROMS before retrying.
Saved values are kept in dist/modules/<id> (or the selected data path), not seed/.
The shared encrypted account is kept in dist/config/credentials.dat.
Portal settings select External or Internal for all four tools. The mode is
stored in dist/config/portal.properties and loaded again before each run.
External uses https://proms.truecorp.co.th/proms/login/.
Internal uses http://promsweb.true.th/proms/login over the company network/VPN.
The tool fills the shared account and submits Sign in once automatically.
Complete CAPTCHA in Chrome when required. Failed sign-ins are not retried.
TK07 Review has Search, Description_Approve and Description_Reject settings.
It preserves the canonical project/site rules, verifies the exact request's
fresh attachment response and list, then reviews its RFS PDF. Multiple or
non-PDF RFS files, unreadable/image-only PDFs, and unverified loading skip
without a decision. Unconfirmed downloads stop before the next request.
Downloads are isolated by run; original PDFs and standalone data remain
untouched. PDF contents are excluded from task logs. Approved and rejected
counts require confirmation and a fresh pending-list check.
Opening a module JAR routes through the Toolkit and its shared account.
All application versions come from the root version.properties file.

The copied legacy source includes scoped ChromeDriver cleanup so that closing
a module does not terminate unrelated ChromeDriver processes.

Build from the project root with:
  powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
or open this folder in NetBeans and select Build / Run Project (F6).
Build requires a JDK; running the packaged dist folder requires Java 8 or later.
Keep the complete dist folder together.
Use the generated release ZIP when distributing the application to other users.
Local Selenium/PDF fixtures use synthetic pending pages and results.
Actual Internal portal checks through VPN were read-only: 45 case-independent
Search matches, one exact reference with four Services-only FBOQ rows,
and no pending TK07 matches. No real requests were approved, rejected,
or deleted during testing.
