param(
    [string]$Matc,
    [switch]$Install,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$Version = '1.71.5'
$MaterialAbiVersion = '71'
$Root = Split-Path -Parent $PSScriptRoot
$Tooling = Join-Path $Root '.tooling\filament-1.71.5'
$Source = Join-Path $Root 'renderer-filament\src\main\materials'
$Output = Join-Path $Root 'renderer-filament\src\main\assets\materials'
$Manifest = Join-Path $Output 'sha256.json'

if (-not $Matc) { $Matc = Join-Path $Tooling 'bin\matc.exe' }
if ($Install -and -not (Test-Path -LiteralPath $Matc)) {
    New-Item -ItemType Directory -Force -Path $Tooling | Out-Null
    $Archive = Join-Path $Tooling 'filament-windows.tgz'
    $Url = "https://github.com/google/filament/releases/download/v$Version/filament-v$Version-windows.tgz"
    Invoke-WebRequest -Uri $Url -OutFile $Archive
    tar -xf $Archive -C $Tooling
    $Discovered = Get-ChildItem -LiteralPath $Tooling -Filter matc.exe -Recurse | Select-Object -First 1
    if (-not $Discovered) { throw "matc.exe was not found in $Archive" }
    $Matc = $Discovered.FullName
}

if (-not (Test-Path -LiteralPath $Matc)) {
    throw "matc $Version is required. Pass -Matc <path>, or use -Install."
}
$VersionText = (& $Matc --version 2>&1 | Out-String).Trim()
# Filament's release archive is pinned above to v1.71.5, while matc --version reports the
# material ABI (71) rather than the full release version.
if ($VersionText -ne $MaterialAbiVersion) {
    throw "matc ABI mismatch: Filament $Version requires ABI $MaterialAbiVersion, got $VersionText"
}

$Names = @('opaque', 'transparent', 'polyhedron', 'highlight', 'depth_cueing', 'picking')
if (-not $VerifyOnly) {
    New-Item -ItemType Directory -Force -Path $Output | Out-Null
    foreach ($Name in $Names) {
        & $Matc -a all -p mobile -o (Join-Path $Output "$Name.filamat") (Join-Path $Source "$Name.mat")
        if ($LASTEXITCODE -ne 0) { throw "matc failed for $Name.mat" }
    }
    $Hashes = [ordered]@{}
    foreach ($Name in $Names) {
        $Hashes["$Name.filamat"] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Output "$Name.filamat")).Hash.ToLowerInvariant()
    }
    $Hashes | ConvertTo-Json | Set-Content -LiteralPath $Manifest -Encoding ASCII
}

if (-not (Test-Path -LiteralPath $Manifest)) { throw "Missing material hash manifest: $Manifest" }
$Expected = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
foreach ($Name in $Names) {
    $FileName = "$Name.filamat"
    $Path = Join-Path $Output $FileName
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing precompiled material: $Path" }
    $Actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected.$FileName) { throw "Hash mismatch for $FileName" }
}
Write-Host "Filament $Version materials verified." -ForegroundColor Green
