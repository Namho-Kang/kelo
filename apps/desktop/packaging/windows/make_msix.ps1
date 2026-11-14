<#
  Packs Kelo for the Microsoft Store (MSIX) from the app folder that `gradlew createDistributable` makes.
  Runs on Windows with the Windows 10/11 SDK (makeappx.exe, signtool.exe; the GitHub windows-latest runner has both).

    pwsh apps/desktop/packaging/windows/make_msix.ps1 `
      -AppDir apps/desktop/build/compose/binaries/main/app/Kelo `
      -Version 1.0.0.0 `
      -IdentityName 12345YourName.Kelo -Publisher "CN=..." -PublisherDisplayName "Your Name" `
      -Out apps/desktop/build/msix/Kelo-1.0.0.0.msix

  For the Store, upload the .msix unsigned: the Store signs it. To install it on your own PC first, pass -Pfx/-PfxPassword
  (a code-signing certificate whose subject is exactly -Publisher) and trust that certificate on the PC.
#>
param(
  [Parameter(Mandatory = $true)] [string] $AppDir,
  [Parameter(Mandatory = $true)] [string] $Version,
  [string] $IdentityName = "KeloPlaceholder.Kelo",
  [string] $Publisher = "CN=Kelo Placeholder",
  [string] $PublisherDisplayName = "Kelo",
  [string] $DisplayName = "Kelo",
  [string] $Out = "Kelo.msix",
  [string] $Pfx = "",
  [string] $PfxPassword = ""
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktop = Resolve-Path (Join-Path $here "..\..")

if ($Version -notmatch '^\d+\.\d+\.\d+\.0$') { throw "Version must be four numbers ending in .0 (the Store's rule), e.g. 1.0.0.0" }
if (-not (Test-Path (Join-Path $AppDir "Kelo.exe"))) { throw "No Kelo.exe in $AppDir (run gradlew createDistributable first)" }

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("kelo-msix-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -Path (Join-Path $AppDir "*") -Destination $stage -Recurse
New-Item -ItemType Directory -Path (Join-Path $stage "Assets") | Out-Null
Copy-Item -Path (Join-Path $desktop "packaging\icons\msix\*") -Destination (Join-Path $stage "Assets")

$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $here "AppxManifest.xml")
function Esc([string] $s) { [System.Security.SecurityElement]::Escape($s) }
$manifest = $manifest.Replace('${IDENTITY_NAME}', (Esc $IdentityName)).Replace('${PUBLISHER}', (Esc $Publisher)).
  Replace('${PUBLISHER_DISPLAY_NAME}', (Esc $PublisherDisplayName)).Replace('${DISPLAY_NAME}', (Esc $DisplayName)).
  Replace('${VERSION}', $Version)
[System.IO.File]::WriteAllText((Join-Path $stage "AppxManifest.xml"), $manifest, (New-Object System.Text.UTF8Encoding $false))

$kits = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
$makeappx = Get-ChildItem -Path $kits -Recurse -Filter makeappx.exe | Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $makeappx) { throw "makeappx.exe not found under $kits (install the Windows SDK)" }

$outDir = Split-Path -Parent $Out
if ($outDir) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
& $makeappx.FullName pack /d $stage /p $Out /o
if ($LASTEXITCODE -ne 0) { throw "makeappx failed" }

if ($Pfx) {
  $signtool = Get-ChildItem -Path $kits -Recurse -Filter signtool.exe | Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
  & $signtool.FullName sign /fd SHA256 /f $Pfx /p $PfxPassword /tr http://timestamp.digicert.com /td SHA256 $Out
  if ($LASTEXITCODE -ne 0) { throw "signtool failed" }
}
Remove-Item -Recurse -Force $stage
Write-Host "MSIX: $Out"
