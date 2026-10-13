param(
    [switch]$ModulesOnly,
    [switch]$Clean,
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$buildRoot = Join-Path $projectRoot 'build'
$distRoot = Join-Path $projectRoot 'dist'
$versionFile = Join-Path $projectRoot 'version.properties'
$versionLine = @(Get-Content -LiteralPath $versionFile | Where-Object { $_ -match '^app\.version\s*=' })
if ($versionLine.Count -ne 1) { throw 'version.properties must contain exactly one app.version entry.' }
$appVersion = ($versionLine[0] -split '=', 2)[1].Trim()
if ($appVersion -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$') {
    throw 'app.version must be MAJOR.MINOR.PATCH, optionally followed by a prerelease label.'
}

function Find-JdkTool([string]$Name) {
    if ($JdkHome) {
        $candidate = Join-Path $JdkHome ('bin\' + $Name + '.exe')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $command = Get-Command ($Name + '.exe') -ErrorAction SilentlyContinue
    if (-not $command) { throw "A JDK 8 or later is required. Set JAVA_HOME or add its bin folder to PATH." }
    return $command.Source
}

function Remove-BuildFolder([string]$Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $allowed = [System.IO.Path]::GetFullPath($buildRoot).TrimEnd('\')
    if ($resolved -ne $allowed -and -not $resolved.StartsWith($allowed + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside this project's build folder."
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

function Ensure-Folder([string]$Path) {
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

function Copy-Tree([string]$Source, [string]$Destination, [bool]$MissingOnly = $false, [bool]$ResourcesOnly = $false) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return }
    Ensure-Folder $Destination
    $base = [System.IO.Path]::GetFullPath($Source).TrimEnd('\') + '\'
    foreach ($file in Get-ChildItem -LiteralPath $Source -File -Recurse) {
        if ($MissingOnly -and $file.Name -eq 'chromedriver.exe') { continue }
        if ($ResourcesOnly -and $file.Extension -in @('.java', '.form')) { continue }
        $target = Join-Path $Destination $file.FullName.Substring($base.Length)
        if ($MissingOnly -and (Test-Path -LiteralPath $target)) { continue }
        Ensure-Folder (Split-Path -Parent $target)
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
    }
}

function Write-Manifest([string]$Path, [string]$MainClass, [string[]]$Libraries, [string]$Version) {
    $logical = @('Manifest-Version: 1.0', ('Main-Class: ' + $MainClass), 'Implementation-Title: TrueOps Toolkit', ('Implementation-Version: ' + $Version))
    if ($Libraries.Count -gt 0) { $logical += 'Class-Path: ' + ($Libraries -join ' ') }
    $physical = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in $logical) {
        while ($line.Length -gt 70) {
            $physical.Add($line.Substring(0, 70))
            $line = ' ' + $line.Substring(70)
        }
        $physical.Add($line)
    }
    $physical.Add('')
    [System.IO.File]::WriteAllText($Path, ($physical -join "`r`n") + "`r`n", [System.Text.Encoding]::ASCII)
}

function Compile-Java([string]$Source, [string]$Classes, [string]$LibraryFolder) {
    Remove-BuildFolder $Classes
    Ensure-Folder $Classes
    $sources = @(Get-ChildItem -LiteralPath $Source -Filter '*.java' -File -Recurse | Sort-Object FullName)
    if ($sources.Count -eq 0) { throw "No Java sources found in $Source" }
    $argFile = Join-Path $Classes 'sources.args'
    $sourceArgs = @($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [System.IO.File]::WriteAllLines($argFile, $sourceArgs, [System.Text.Encoding]::Default)
    $compileArgs = @('-encoding', 'UTF-8', '-sourcepath', $Source, '-d', $Classes)
    if ($script:javacMajor -ge 9) {
        $compileArgs += @('--release', '8')
    } else {
        $compileArgs += @('-source', '1.8', '-target', '1.8')
    }
    if ($LibraryFolder) {
        $libraries = @(Get-ChildItem -LiteralPath $LibraryFolder -Filter '*.jar' -File | Where-Object { $_.Name -notmatch '-sources\.jar$' } | Sort-Object Name)
        $compileArgs += @('-classpath', (($libraries | ForEach-Object { $_.FullName }) -join [System.IO.Path]::PathSeparator))
    }
    $compileArgs += '@' + $argFile
    & $script:javac @compileArgs
    if ($LASTEXITCODE -ne 0) { throw "Java compilation failed: $Source" }
    Remove-Item -LiteralPath $argFile -Force
    Copy-Tree $Source $Classes $false $true
}

function Create-Jar([string]$Destination, [string]$Manifest, [string]$Classes) {
    $temporary = Join-Path $buildRoot ([System.IO.Path]::GetFileName($Destination))
    & $script:jar 'cfm' $temporary $Manifest '-C' $Classes '.'
    if ($LASTEXITCODE -ne 0) { throw "JAR packaging failed: $Destination" }
    Ensure-Folder (Split-Path -Parent $Destination)
    Copy-Item -LiteralPath $temporary -Destination $Destination -Force
}

if ($Clean) {
    Remove-BuildFolder $buildRoot
    Write-Host 'Cleaned generated build files. dist and all runtime data were preserved.'
    exit 0
}

$script:javac = Find-JdkTool 'javac'
$script:jar = Find-JdkTool 'jar'
$versionInfo = New-Object System.Diagnostics.ProcessStartInfo
$versionInfo.FileName = $script:javac
$versionInfo.Arguments = '-version'
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardOutput = $true
$versionInfo.RedirectStandardError = $true
$versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
$versionText = ($versionProcess.StandardOutput.ReadToEnd() + $versionProcess.StandardError.ReadToEnd()).Trim()
$versionProcess.WaitForExit()
$versionProcess.Dispose()
if ($versionText -notmatch 'javac (?:1\.)?(\d+)') { throw 'Could not identify the JDK compiler version.' }
$script:javacMajor = [int]$Matches[1]
if ($script:javacMajor -lt 8) { throw 'JDK 8 or later is required.' }
Write-Host ('Compiler: ' + $versionText + '; target: Java 8')
Ensure-Folder $buildRoot
Ensure-Folder $distRoot
$modules = @(
    @{ Id = 'tuar'; Jar = 'TrueOps-TUAR.jar'; Legacy = 'AutoRequestTUAR.jar'; Main = 'trueopstoolkit.ModuleShortcuts$Tuar' },
    @{ Id = 'approve'; Jar = 'TrueOps-Approve.jar'; Legacy = 'ApproveProms_TrueCorp_V3.jar'; Main = 'trueopstoolkit.ModuleShortcuts$Approve' },
    @{ Id = 'approve-ref'; Jar = 'TrueOps-ApproveByRef.jar'; Legacy = 'ApproveProms_Ref.jar'; Main = 'trueopstoolkit.ModuleShortcuts$Ref' },
    @{ Id = 'delete'; Jar = 'TrueOps-DeleteSite.jar'; Legacy = 'Delete_Site_TrueCorp.jar'; Main = 'trueopstoolkit.ModuleShortcuts$Delete' }
)

foreach ($module in $modules) {
    Write-Host ('Building module: ' + $module.Id)
    $toolRoot = Join-Path $projectRoot ('tools\' + $module.Id)
    $classes = Join-Path $buildRoot ('modules\' + $module.Id)
    $destination = Join-Path $distRoot ('modules\' + $module.Id)
    Compile-Java (Join-Path $toolRoot 'src') $classes (Join-Path $toolRoot 'lib')
    Ensure-Folder $destination
    Copy-Tree (Join-Path $toolRoot 'lib') (Join-Path $destination 'lib')
    # Runtime data is private. Never restore old plaintext accounts or personal
    # workbooks from seed during a rebuild. release.ps1 generates blank inputs.
    $manifest = Join-Path $buildRoot ($module.Id + '.mf')
    $libraries = @('../../TrueOpsToolkit.jar') + @(Get-ChildItem -LiteralPath (Join-Path $toolRoot 'lib') -Filter '*.jar' -File | Sort-Object Name | ForEach-Object { 'lib/' + [Uri]::EscapeDataString($_.Name) })
    Write-Manifest $manifest $module.Main $libraries $appVersion
    Create-Jar (Join-Path $destination $module.Jar) $manifest $classes
    # Remove only the known obsolete executable, after its replacement exists.
    $legacyJar = Join-Path $destination $module.Legacy
    if (Test-Path -LiteralPath $legacyJar -PathType Leaf) { Remove-Item -LiteralPath $legacyJar -Force }
}

if (-not $ModulesOnly) {
    Write-Host 'Building TrueOps Toolkit launcher'
    $classes = Join-Path $buildRoot 'classes'
    Compile-Java (Join-Path $projectRoot 'src') $classes ''
    Copy-Item -LiteralPath $versionFile -Destination (Join-Path $classes 'trueopstoolkit\version.properties') -Force
    $manifest = Join-Path $buildRoot 'launcher.mf'
    Write-Manifest $manifest 'trueopstoolkit.Main' @() $appVersion
    Create-Jar (Join-Path $distRoot 'TrueOpsToolkit.jar') $manifest $classes
    Copy-Item -LiteralPath $versionFile -Destination (Join-Path $distRoot 'version.properties') -Force
}
Write-Host "Build complete: TrueOps Toolkit $appVersion. Existing runtime data was preserved; private seed data was not copied."
