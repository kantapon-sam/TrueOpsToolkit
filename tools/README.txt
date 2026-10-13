TrueOps Toolkit module sources
=============================

Version 1.0.0 includes an English launcher and English interfaces for all tools.

tuar        Request TUAR       TrueOps-TUAR.jar
approve     Approve PROMS      TrueOps-Approve.jar
approve-ref Approve by Ref     TrueOps-ApproveByRef.jar
delete      Delete Site        TrueOps-DeleteSite.jar

src/ and lib/ are local copies from the original NetBeans projects.
The originals are not modified by either build method.
Each module is compiled into its own JAR and keeps its own libraries.
The launcher starts each tool with that module's working directory.

seed/ contains private legacy runtime files and is ignored by Git.
Neither build method copies seed data into dist or release packages.
release.ps1 generates new blank workbooks, reference lists, and settings.
Every Toolkit module uses
dist/drivers/chromedriver.exe, automatically prepared during Toolkit startup
and checked again before browser startup. No driver binary, .exe, .bat, or
.cmd is included in the release. Open TrueOpsToolkit.jar directly.
Startup shows a progress dialog and checks application and driver updates
without a manual check. A new application release offers Update now or Later.
The manager migrates a compatible legacy driver if available and checks the
official Google release endpoint for a newer compatible version.
Changes made in dist/modules/* are retained across builds.
Use the shared account dialog for every login. Username and password are
protected together with Windows DPAPI and are never saved in Login.txt.
Change task settings and PROMS reference lists through the module window.
TUAR can select its workbook and attachments folder in the window.
Saved values are kept in dist/modules/<id> (or the selected data path), not seed/.
The shared encrypted account is kept in dist/config/credentials.dat.
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
