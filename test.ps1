param([string]$JdkHome = $env:JAVA_HOME, [switch]$SkipBrowsers)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($PSScriptRoot)
$dist = Join-Path $root 'dist'
$testClasses = Join-Path $root 'build\test-classes'
$reports = Join-Path $root 'build\test-reports'
function JavaTool([string]$name) {
    if ($JdkHome -and (Test-Path -LiteralPath (Join-Path $JdkHome "bin\$name.exe"))) { return (Join-Path $JdkHome "bin\$name.exe") }
    return (Get-Command "$name.exe" -ErrorAction Stop).Source
}
$java = JavaTool 'java'
$javac = JavaTool 'javac'
New-Item -ItemType Directory -Force -Path $testClasses,$reports | Out-Null
$launcher = Join-Path $dist 'TrueOpsToolkit.jar'
if (-not (Test-Path -LiteralPath $launcher)) { throw 'Run build.ps1 first.' }
$rootTests = @(Get-ChildItem -LiteralPath (Join-Path $root 'test\trueopstoolkit') -Filter '*.java' -File | ForEach-Object { $_.FullName })
& $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' $launcher '-d' $testClasses @rootTests
if ($LASTEXITCODE -ne 0) { throw 'Test compile failed' }
foreach ($test in @('AppVersionTest','ModuleSettingsTest','PortalSettingsTest','PortalSettingsDialogTest','SharedCredentialsTest','SharedCredentialsMigrationTest','RetiredModulesTest','SharedChromeDriverTest','ToolkitUpdateManagerTest','ToolkitUpdaterLifecycleTest','MainUpdateCloseTest','MainStartupUpdateTest','MainInstallHandoffTest','ModuleWindowProgressTest','ModuleWindowSearchTest','UiSnapshotTest')) {
    & $java '-cp' "$testClasses;$launcher" "trueopstoolkit.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
$approveJar = Join-Path $dist 'modules\approve\TrueOps-Approve.jar'
& $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-cp' $approveJar '-d' $testClasses (Join-Path $root 'test\com\java\myapp\PromsSettingsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'PROMS settings test compile failed' }
foreach ($moduleTest in @(@('approve','TrueOps-Approve.jar'),@('approve-ref','TrueOps-ApproveByRef.jar'),@('delete','TrueOps-DeleteSite.jar'))) {
    $moduleJar = Join-Path $dist ("modules\" + $moduleTest[0] + '\' + $moduleTest[1])
    & $java '-cp' "$testClasses;$moduleJar;$launcher" 'com.java.myapp.PromsSettingsTest' $moduleTest[0]
    if ($LASTEXITCODE -ne 0) { throw "PROMS parser test failed: $($moduleTest[0])" }
}
& $java '-cp' "$testClasses;$launcher" 'trueopstoolkit.ProcessIntegrationTest' $dist
if ($LASTEXITCODE -ne 0) { throw 'Process integration failed' }
& $java '-jar' $launcher '--ui-smoke' (Join-Path $reports 'ui')
if ($LASTEXITCODE -ne 0) { throw 'Window smoke test failed' }
$tk07Classpath = "$(Join-Path $dist 'modules\tk07\TrueOps-TK07.jar');$(Join-Path $dist 'modules\tk07\lib\*');$launcher"
& $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' $tk07Classpath '-d' $testClasses (Join-Path $root 'test\com\java\myapp\Tk07WorkflowTest.java')
if ($LASTEXITCODE -ne 0) { throw 'TK07 test compile failed' }
& $java '-cp' "$testClasses;$tk07Classpath" 'com.java.myapp.Tk07WorkflowTest'
if ($LASTEXITCODE -ne 0) { throw 'TK07 settings/PDF checks failed' }
if (-not $SkipBrowsers) {
    & $java '-cp' $launcher 'trueopstoolkit.SharedChromeDriver' '--home' $dist
    if ($LASTEXITCODE -ne 0) { throw 'Shared ChromeDriver preparation failed' }
    $seleniumLib = Join-Path $dist 'modules\approve\lib\*'
    & $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' $seleniumLib '-d' $testClasses (Join-Path $root 'test\SeleniumClientCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'Selenium test compile failed' }
    foreach ($id in @('approve','approve-ref','delete','tk07')) {
        $module = Join-Path $dist "modules\$id"
        $driver = Join-Path $dist 'drivers\chromedriver.exe'
        & $java '-cp' "$testClasses;$(Join-Path $module 'lib\*')" 'SeleniumClientCheck' $driver $id (Join-Path $reports "selenium-$id.txt")
        if ($LASTEXITCODE -ne 0) { throw "Offline Selenium check failed: $id" }
    }
    $fixture = Join-Path $root 'test\approve\mock-proms.html'
    foreach ($workflow in @(@('approve','TrueOps-Approve.jar','ApproveWorkflowTest'),@('approve-ref','TrueOps-ApproveByRef.jar','ApproveByRefWorkflowTest'))) {
        $module = Join-Path $dist ("modules\" + $workflow[0])
        $moduleClasspath = "$(Join-Path $module $workflow[1]);$(Join-Path $module 'lib\*')"
        & $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' $moduleClasspath '-d' $testClasses (Join-Path $root ("test\com\java\myapp\" + $workflow[2] + '.java'))
        if ($LASTEXITCODE -ne 0) { throw "Approval workflow test compile failed: $($workflow[0])" }
        if ($workflow[0] -eq 'approve') {
            & $java '-cp' "$testClasses;$moduleClasspath" ('com.java.myapp.' + $workflow[2]) $driver (Join-Path $reports 'approve-workflow.txt') $fixture
        } else {
            & $java '-cp' "$testClasses;$moduleClasspath" ('com.java.myapp.' + $workflow[2]) $driver $fixture
        }
        if ($LASTEXITCODE -ne 0) { throw "Approval workflow browser test failed: $($workflow[0])" }
    }
    & $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' "$approveJar;$seleniumLib" '-d' $testClasses (Join-Path $root 'test\com\java\myapp\PromsSignInTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Automatic sign-in test compile failed' }
    & $java '-cp' "$testClasses;$approveJar;$seleniumLib" 'com.java.myapp.PromsSignInTest' $driver (Join-Path $reports 'proms-sign-in.txt') (Join-Path $root 'test\approve\mock-login.html')
    if ($LASTEXITCODE -ne 0) { throw 'Automatic sign-in browser checks failed' }
    & $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-sourcepath' (Join-Path $root 'test') '-cp' "$approveJar;$seleniumLib" '-d' $testClasses (Join-Path $root 'test\com\java\myapp\NativePendingSnapshotTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Native pending snapshot compile failed' }
    & $java '-cp' "$testClasses;$approveJar;$seleniumLib" 'com.java.myapp.NativePendingSnapshotTest' $driver (Join-Path $reports 'native-pending-snapshot.txt') (Join-Path $root 'test\native-pending\native-pending.html')
    if ($LASTEXITCODE -ne 0) { throw 'Native pending snapshot browser checks failed' }
    & $java '-cp' "$testClasses;$tk07Classpath" 'com.java.myapp.Tk07WorkflowTest' $driver (Join-Path $root 'test\tk07\mock-tk07.html')
    if ($LASTEXITCODE -ne 0) { throw 'TK07 workflow browser checks failed' }
}
Write-Host 'PASS: module windows, isolation, graceful stop, runtime inputs, and requested browser checks.'
Write-Host 'ChromeDriver updates use official Google endpoints. Browser tests use local synthetic pages only; no production login, request, approval, rejection, or deletion was performed.'
Write-Host "Reports: $reports"
