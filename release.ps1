param([string]$JdkHome = $env:JAVA_HOME, [switch]$SkipBuild, [string]$CheckArchive)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($PSScriptRoot)
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-ReleaseFileName([string]$Name) {
    $normalized = $Name.Replace('\', '/')
    $leaf = ($normalized -split '/')[-1].TrimEnd([char[]]@(' ', '.'))
    if ($leaf -match '(?i)\.(exe|bat|cmd)$' -or $leaf -ieq 'chromedriver') {
        throw [System.IO.InvalidDataException]::new("Prohibited release file: $Name")
    }
}
function Test-ZipSignature([byte[]]$Bytes, [int]$Count) {
    return $Count -eq 4 -and $Bytes[0] -eq 0x50 -and $Bytes[1] -eq 0x4b -and (
        ($Bytes[2] -eq 3 -and $Bytes[3] -eq 4) -or ($Bytes[2] -eq 5 -and $Bytes[3] -eq 6))
}
function Assert-ReleaseArchiveEntries([System.IO.Compression.ZipArchive]$Archive, [string]$Label, [int]$Depth, [hashtable]$Budget) {
    if ($Depth -gt 8) { throw "Release archive nesting exceeds the inspection limit: $Label" }
    foreach ($entry in $Archive.Entries) {
        $Budget.Entries++
        if ($Budget.Entries -gt 1000000) { throw 'Release archive contains too many entries to inspect.' }
        $name = $entry.FullName.Replace('\', '/')
        if ($name.EndsWith('/')) { continue }
        Assert-ReleaseFileName ($Label + '!/' + $name)
        $Budget.Bytes += $entry.Length
        if ($entry.Length -gt 128MB -or $Budget.Bytes -gt 1GB) { throw 'Release archive exceeds the inspection size limit.' }
        $stream = $entry.Open()
        try {
            $prefix = New-Object byte[] 4
            $count = 0
            while ($count -lt 4) {
                $read = $stream.Read($prefix, $count, 4 - $count)
                if ($read -eq 0) { break }
                $count += $read
            }
            $zip = Test-ZipSignature $prefix $count
            if (-not $zip -and $name -match '(?i)\.(jar|zip|war|ear|xlsx|docx|pptx)$') {
                throw "Cannot inspect nested release archive: $Label!/$name"
            }
            if ($zip) {
                $buffer = New-Object System.IO.MemoryStream
                try {
                    $buffer.Write($prefix, 0, $count)
                    $chunk = New-Object byte[] 32768
                    while (($read = $stream.Read($chunk, 0, $chunk.Length)) -gt 0) {
                        if ($buffer.Length + $read -gt $entry.Length -or $buffer.Length + $read -gt 128MB) { throw 'Nested release archive exceeds its declared size.' }
                        $buffer.Write($chunk, 0, $read)
                    }
                    if ($buffer.Length -ne $entry.Length) { throw 'Nested release archive length mismatch.' }
                    $buffer.Position = 0
                    $nested = [System.IO.Compression.ZipArchive]::new($buffer, [System.IO.Compression.ZipArchiveMode]::Read, $true)
                    try { Assert-ReleaseArchiveEntries $nested ($Label + '!/' + $name) ($Depth + 1) $Budget }
                    finally { $nested.Dispose() }
                } finally { $buffer.Dispose() }
            }
        } finally { $stream.Dispose() }
    }
}
function Assert-ReleaseArchive([string]$Path) {
    Assert-ReleaseFileName $Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try { Assert-ReleaseArchiveEntries $archive ([System.IO.Path]::GetFileName($Path)) 0 @{Entries=0L;Bytes=0L} }
    finally { $archive.Dispose() }
}

# Read-only validation mode is also used by test-release.ps1's synthetic fixtures.
if ($CheckArchive) {
    Assert-ReleaseArchive ([System.IO.Path]::GetFullPath($CheckArchive))
    Write-Host 'PASS: archive and all nested archives contain no EXE, BAT, CMD, or standalone ChromeDriver file.'
    return
}

$versionLine = @(Get-Content -LiteralPath (Join-Path $root 'version.properties') | Where-Object { $_ -match '^app\.version\s*=' })
if ($versionLine.Count -ne 1) { throw 'Expected one app.version in version.properties.' }
$version = ($versionLine[0] -split '=', 2)[1].Trim()
if ($version -notmatch '^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$') { throw 'The release update feed requires a stable MAJOR.MINOR.PATCH version. Use build.ps1 for development prereleases.' }

function Write-Utf8([string]$Path, [string]$Value) {
    [System.IO.File]::WriteAllText($Path, $Value, (New-Object System.Text.UTF8Encoding($false)))
}
function Stage-File([string]$Source, [string]$Relative) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Missing release input: $Relative" }
    Assert-ReleaseFileName $Relative
    if ($Relative -match '(?i)\.(jar|zip)$') { Assert-ReleaseArchive $Source }
    $target = Join-Path $script:stageRoot $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $target -Force
    $script:allowed.Add($Relative.Replace('\', '/'))
}
function Check-AppJar([string]$Path, [string]$ExpectedMain) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $manifest = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $manifest) { throw "Missing manifest: $Path" }
        $reader = New-Object System.IO.StreamReader($manifest.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch ('(?m)^Implementation-Version: ' + [regex]::Escape($version) + '\r?$')) {
            throw "Artifact version differs from version.properties: $Path. Run build.ps1."
        }
        $unfolded = $text -replace '\r?\n ', ''
        if ($unfolded -notmatch ('(?m)^Main-Class: ' + [regex]::Escape($ExpectedMain) + '\r?$')) { throw "Unexpected application entry point: $Path" }
        if ($ExpectedMain -ne 'trueopstoolkit.Main' -and $unfolded -notmatch '(?m)^Class-Path: [^\r\n]*\.\./\.\./TrueOpsToolkit\.jar(?: |\r?$)') { throw "Missing central launcher classpath: $Path" }
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if ($entry.FullName -notmatch '\.class$' -and $entry.FullName -notin @('META-INF/MANIFEST.MF', 'trueopstoolkit/version.properties')) {
                throw "Unexpected application JAR resource in $Path; review release inputs before packaging."
            }
        }
    } finally { $archive.Dispose() }
}

function Stage-CompatibilityJar([string]$Relative, [bool]$Launcher) {
    # The 1.0.0 updater requires a TUAR application JAR and one dependency JAR.
    # Manifest-only bridges satisfy that contract without bundling TUAR code.
    $target = Join-Path $script:stageRoot $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    $lines = @('Manifest-Version: 1.0', 'Implementation-Title: TrueOps Toolkit compatibility bridge', ('Implementation-Version: ' + $version))
    if ($Launcher) { $lines += @('Main-Class: trueopstoolkit.ModuleShortcuts$Tuar', 'Class-Path: ../../TrueOpsToolkit.jar') }
    $archive = [System.IO.Compression.ZipFile]::Open($target, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
        $stream = $entry.Open()
        try {
            $bytes = [System.Text.Encoding]::ASCII.GetBytes(($lines -join "`r`n") + "`r`n`r`n")
            $stream.Write($bytes, 0, $bytes.Length)
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
    Assert-ReleaseArchive $target
    if ($Launcher) { Check-AppJar $target 'trueopstoolkit.ModuleShortcuts$Tuar' }
    $script:allowed.Add($Relative.Replace('\', '/'))
}

if (-not $SkipBuild) { & (Join-Path $root 'build.ps1') -JdkHome $JdkHome }
$dist = Join-Path $root 'dist'
$stagingParent = [System.IO.Path]::GetFullPath((Join-Path $root 'build\release'))
$packageName = 'TrueOpsToolkit-' + $version
$script:stageRoot = [System.IO.Path]::GetFullPath((Join-Path $stagingParent $packageName))
# Every recursive delete is confined to this project's generated staging folder.
if (-not $script:stageRoot.StartsWith($stagingParent.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid release staging path.' }
if (Test-Path -LiteralPath $script:stageRoot) { Remove-Item -LiteralPath $script:stageRoot -Recurse -Force }
New-Item -ItemType Directory -Path $script:stageRoot -Force | Out-Null
$script:allowed = New-Object 'System.Collections.Generic.List[string]'

# Explicit allowlist: no recursive copy from the personal dist directory.
$modules = @(
    @{Id='approve'; Jar='TrueOps-Approve.jar'; Main='trueopstoolkit.ModuleShortcuts$Approve'},
    @{Id='approve-ref'; Jar='TrueOps-ApproveByRef.jar'; Main='trueopstoolkit.ModuleShortcuts$Ref'},
    @{Id='delete'; Jar='TrueOps-DeleteSite.jar'; Main='trueopstoolkit.ModuleShortcuts$Delete'}
)
Check-AppJar (Join-Path $dist 'TrueOpsToolkit.jar') 'trueopstoolkit.Main'
Stage-File (Join-Path $dist 'TrueOpsToolkit.jar') 'TrueOpsToolkit.jar'
foreach ($document in @('README.md', 'CHANGELOG.md', 'RELEASE.md', 'version.properties')) {
    Stage-File (Join-Path $root $document) $document
}
foreach ($module in $modules) {
    $relative = 'modules\' + $module.Id + '\' + $module.Jar
    Check-AppJar (Join-Path $dist $relative) $module.Main
    Stage-File (Join-Path $dist $relative) $relative
    # Only dependency names present in source lib are eligible, not arbitrary dist files.
    $libraries = @(Get-ChildItem -LiteralPath (Join-Path $root ('tools\' + $module.Id + '\lib')) -Filter '*.jar' -File | Where-Object { $_.Name -notmatch '-sources\.jar$' } | Sort-Object Name)
    if ($libraries.Count -eq 0) { throw "No dependencies for module $($module.Id)" }
    foreach ($library in $libraries) {
        Stage-File $library.FullName ('modules\' + $module.Id + '\lib\' + $library.Name)
    }
    $directory = Join-Path $script:stageRoot ('modules\' + $module.Id)
    # Fresh non-secret preferences only. Account fields are never written here.
    Write-Utf8 (Join-Path $directory 'settings.csv') "Search,`r`nDescription,`r`n"
    $script:allowed.Add('modules/' + $module.Id + '/settings.csv')
    if ($module.Id -in @('approve-ref', 'delete')) {
        Write-Utf8 (Join-Path $directory 'Ref_No.csv') ''
        $script:allowed.Add('modules/' + $module.Id + '/Ref_No.csv')
    }
}

# Preserve the old automatic update contract with two tiny manifest-only JARs.
# The old TUAR shortcut opens the three-tool launcher; TUAR is a separate app.
Stage-CompatibilityJar 'modules\tuar\TrueOps-TUAR.jar' $true
Stage-CompatibilityJar 'modules\tuar\lib\TrueOps-TUAR-compat.jar' $false

$actual = @(Get-ChildItem -LiteralPath $script:stageRoot -File -Recurse | ForEach-Object { $_.FullName.Substring($script:stageRoot.Length + 1).Replace('\', '/') } | Sort-Object)
foreach ($relative in $actual) { Assert-ReleaseFileName $relative }
if (@(Compare-Object @($script:allowed | Sort-Object) $actual).Count -ne 0) { throw 'Unexpected release staging contents.' }
$releaseRoot = Join-Path $root 'releases'
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$zipName = $packageName + '-windows.zip'
$zipPath = Join-Path $releaseRoot $zipName
$temporaryZip = Join-Path $stagingParent ($zipName + '.tmp.zip')
Compress-Archive -LiteralPath $script:stageRoot -DestinationPath $temporaryZip -Force
Assert-ReleaseArchive $temporaryZip
Move-Item -LiteralPath $temporaryZip -Destination $zipPath -Force
$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Utf8 ($zipPath + '.sha256') ($hash + '  ' + $zipName + "`r`n")
$archiveUrl = 'https://github.com/kantapon-sam/TrueOpsToolkit/releases/download/v' + $version + '/' + $zipName
Write-Utf8 (Join-Path $releaseRoot 'update.properties') ("app.version=$version`r`narchive.url=$archiveUrl`r`narchive.sha256=$hash`r`narchive.root=$packageName`r`n")
Write-Host "Release ready: $zipPath"
Write-Host "SHA256: $hash"
Write-Host 'Update feed: releases\update.properties (attach it to the same GitHub release).'
Write-Host 'Contains three PROMS tools, blank preferences/reference lists, and a manifest-only legacy update bridge. No TUAR implementation, workbook, account, runtime settings, logs, or existing driver was copied.'
Write-Host 'Verified every archive layer: no EXE, BAT, CMD, or standalone ChromeDriver binary.'
