param([string]$JdkHome = $env:JAVA_HOME, [switch]$GuardTestsOnly)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($PSScriptRoot)
Add-Type -AssemblyName System.IO.Compression.FileSystem
function New-GuardFixtureArchive([hashtable]$Members) {
    $buffer = New-Object System.IO.MemoryStream
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($buffer, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($name in @($Members.Keys | Sort-Object)) {
                $entry = $archive.CreateEntry($name)
                $stream = $entry.Open()
                try { [byte[]]$data = $Members[$name]; $stream.Write($data, 0, $data.Length) }
                finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
        return ,$buffer.ToArray()
    } finally { $buffer.Dispose() }
}
function Test-ReleaseArchiveGuard {
    $buildRoot = [System.IO.Path]::GetFullPath((Join-Path $root 'build'))
    $temporary = [System.IO.Path]::GetFullPath((Join-Path $buildRoot ('release-guard-tests-' + [Guid]::NewGuid().ToString('N'))))
    if (-not $temporary.StartsWith($buildRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid release guard test path.' }
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    try {
        $fixture = Join-Path $temporary 'synthetic.zip'
        $plain = [System.Text.Encoding]::UTF8.GetBytes('Synthetic fixture only; no executable content.')
        $safeJar = New-GuardFixtureArchive @{'org/openqa/selenium/chrome/ChromeDriver.class'=$plain;'META-INF/MANIFEST.MF'=$plain}
        [System.IO.File]::WriteAllBytes($fixture, (New-GuardFixtureArchive @{'library.jar'=$safeJar;'README.md'=$plain}))
        & (Join-Path $root 'release.ps1') -CheckArchive $fixture
        $checks = 1
        foreach ($name in @('chromedriver.EXE', 'start.bat', 'START.CMD', 'chromedriver', 'legacy/run.CmD.')) {
            foreach ($nested in @($false, $true)) {
                $payload = New-GuardFixtureArchive @{$name=$plain}
                if ($nested) {
                    # A ZIP with no archive extension still has to be inspected.
                    $inner = New-GuardFixtureArchive @{'deeper.jar'=$payload}
                    $payload = New-GuardFixtureArchive @{'library.jar'=(New-GuardFixtureArchive @{'payload.bin'=$inner})}
                }
                [System.IO.File]::WriteAllBytes($fixture, $payload)
                $rejected = $false
                try { & (Join-Path $root 'release.ps1') -CheckArchive $fixture }
                catch {
                    if ($_.Exception.Message -notmatch '^Prohibited release file:') { throw }
                    $rejected = $true
                }
                if (-not $rejected) { throw "Release guard accepted prohibited content: $name (nested=$nested)" }
                $checks++
            }
        }
        Write-Host "PASS: release archive guard ($checks cases): clean Java dependency accepted; direct and nested EXE/BAT/CMD/ChromeDriver payloads rejected."
    } finally {
        $resolved = [System.IO.Path]::GetFullPath($temporary)
        if (-not $resolved.StartsWith($buildRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to delete outside the generated build folder.' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
Test-ReleaseArchiveGuard
if ($GuardTestsOnly) { return }

$sourceText = [System.IO.File]::ReadAllText((Join-Path $root 'version.properties'))
if ($sourceText -notmatch '(?m)^app\.version\s*=\s*([^\r\n]+)') { throw 'Missing app.version.' }
$version = $Matches[1].Trim()
$prefix = 'TrueOpsToolkit-' + $version + '/'
$zipName = 'TrueOpsToolkit-' + $version + '-windows.zip'
$zipPath = Join-Path $root ('releases\' + $zipName)
$expectedHash = (Get-Content -LiteralPath ($zipPath + '.sha256') -Raw).Trim()
$actualHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $zipName
if ($expectedHash -cne $actualHash) { throw 'Release checksum mismatch.' }
$descriptor = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'releases\update.properties')) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
    $pair = $line -split '=', 2
    if ($pair.Count -ne 2 -or $descriptor.ContainsKey($pair[0])) { throw 'Malformed update descriptor.' }
    $descriptor[$pair[0]] = $pair[1]
}
$expectedUrl = 'https://github.com/kantapon-sam/TrueOpsToolkit/releases/download/v' + $version + '/' + $zipName
if ($descriptor.Count -ne 4 -or $descriptor['app.version'] -cne $version -or $descriptor['archive.url'] -cne $expectedUrl -or $descriptor['archive.sha256'] -cne $actualHash.Substring(0,64) -or $descriptor['archive.root'] -cne $prefix.TrimEnd('/')) { throw 'Update descriptor does not match the release ZIP.' }
& (Join-Path $root 'release.ps1') -CheckArchive $zipPath
$expected = New-Object 'System.Collections.Generic.List[string]'
foreach ($path in @('TrueOpsToolkit.jar', 'README.md', 'CHANGELOG.md', 'RELEASE.md', 'version.properties')) { $expected.Add($path) }
$modules = @(
    @{Id='tuar'; Jar='TrueOps-TUAR.jar'},
    @{Id='approve'; Jar='TrueOps-Approve.jar'},
    @{Id='approve-ref'; Jar='TrueOps-ApproveByRef.jar'},
    @{Id='delete'; Jar='TrueOps-DeleteSite.jar'}
)
foreach ($module in $modules) {
    $expected.Add('modules/' + $module.Id + '/' + $module.Jar)
    foreach ($library in Get-ChildItem -LiteralPath (Join-Path $root ('tools\' + $module.Id + '\lib')) -Filter '*.jar' -File | Where-Object { $_.Name -notmatch '-sources\.jar$' }) {
        $expected.Add('modules/' + $module.Id + '/lib/' + $library.Name)
    }
    if ($module.Id -ne 'tuar') { $expected.Add('modules/' + $module.Id + '/Login.txt') }
    if ($module.Id -in @('approve-ref', 'delete')) { $expected.Add('modules/' + $module.Id + '/Ref_No.csv') }
}
$expected.Add('modules/tuar/requests.xlsx')
$expected.Add('modules/tuar/attachments/PLACE_FILES_HERE.txt')

function Read-Entry([System.IO.Compression.ZipArchive]$Archive, [string]$Name) {
    $entry = $Archive.GetEntry($Name)
    if (-not $entry) { throw "Missing archive entry: $Name" }
    $reader = New-Object System.IO.StreamReader($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Test-NestedJar([System.IO.Compression.ZipArchive]$Archive, [string]$Name) {
    $stream = $Archive.GetEntry($prefix + $Name).Open()
    $jar = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Read)
    try {
        $manifest = Read-Entry $jar 'META-INF/MANIFEST.MF'
        if ($manifest -notmatch ('(?m)^Implementation-Version: ' + [regex]::Escape($version) + '\r?$')) { throw "Wrong packaged version in $Name" }
        if ($Name -eq 'TrueOpsToolkit.jar') {
            if ((Read-Entry $jar 'trueopstoolkit/version.properties') -cne $sourceText) { throw 'Launcher version resource differs from source.' }
        }
    } finally { $jar.Dispose(); $stream.Dispose() }
}

$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $files = @($archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
    foreach ($file in $files) {
        if (-not $file.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'Unexpected archive root.' }
    }
    $actual = @($files | ForEach-Object { $_.FullName.Substring($prefix.Length) } | Sort-Object)
    if (@(Compare-Object @($expected | Sort-Object) $actual).Count -ne 0) { throw 'Archive differs from the release allowlist.' }
    if ((Read-Entry $archive ($prefix + 'version.properties')) -cne $sourceText) { throw 'Packaged version source mismatch.' }
    Test-NestedJar $archive 'TrueOpsToolkit.jar'
    foreach ($module in $modules) {
        Test-NestedJar $archive ('modules/' + $module.Id + '/' + $module.Jar)
        if ($module.Id -ne 'tuar') {
            $settings = Read-Entry $archive ($prefix + 'modules/' + $module.Id + '/Login.txt')
            if ($settings -cne "Search,`r`nDescription,`r`n") { throw 'Release preferences must contain only empty, non-secret fields.' }
        }
        if ($module.Id -in @('approve-ref', 'delete')) {
            if ($archive.GetEntry($prefix + 'modules/' + $module.Id + '/Ref_No.csv').Length -ne 0) { throw 'Release reference list is not empty.' }
        }
    }
    $stream = $archive.GetEntry($prefix + 'modules/tuar/requests.xlsx').Open()
    $workbook = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Read)
    try {
        [xml]$requests = Read-Entry $workbook 'xl/worksheets/sheet1.xml'
        [xml]$options = Read-Entry $workbook 'xl/worksheets/sheet2.xml'
        if ($requests.SelectNodes('//*[local-name()="row"]').Count -ne 1 -or $options.SelectNodes('//*[local-name()="row"]').Count -ne 0) { throw 'Release workbook must have headers and no data rows.' }
    } finally { $workbook.Dispose(); $stream.Dispose() }
} finally { $archive.Dispose() }
function Find-ReleaseJavaTool([string]$Name) {
    if ($JdkHome) {
        $candidate = Join-Path $JdkHome ('bin\' + $Name + '.exe')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return (Get-Command ($Name + '.exe') -ErrorAction Stop).Source
}
$java = Find-ReleaseJavaTool 'java'
$javac = Find-ReleaseJavaTool 'javac'
$testClasses = Join-Path $root 'build\release-test-classes'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$launcher = Join-Path $root 'dist\TrueOpsToolkit.jar'
& $javac '-encoding' 'UTF-8' '-source' '1.8' '-target' '1.8' '-cp' $launcher '-d' $testClasses (Join-Path $root 'test\trueopstoolkit\ToolkitUpdateManagerTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Release updater compatibility test compilation failed.' }
& $java '-cp' ($testClasses + ';' + $launcher) 'trueopstoolkit.ToolkitUpdateManagerTest' '--verify-release' $zipPath (Join-Path $root 'releases\update.properties')
if ($LASTEXITCODE -ne 0) { throw 'Packaged release is not compatible with the application updater.' }
Write-Host "PASS: $zipName checksum, update descriptor, all five versions, exact file allowlist, empty account/reference inputs, schema-only workbook, and no executable/batch/driver files at any archive depth."
