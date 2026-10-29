param([string]$InstallPath = (Join-Path $env:LOCALAPPDATA 'Programs/learnharness'))
$ErrorActionPreference='Stop'
if(Get-Process LearnHarness -ErrorAction SilentlyContinue){throw 'Close LearnHarness first so its learning records are safely saved.'}
$manifest=Get-Content (Join-Path $PSScriptRoot 'update-manifest.json') -Raw | ConvertFrom-Json
$target=Join-Path $InstallPath 'resources/app.asar'
$update=Join-Path $PSScriptRoot 'app.asar'
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.originalSha256){throw 'Installed version differs from the tested version. No files changed.'}
if((Get-FileHash -LiteralPath $update -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.updatedSha256){throw 'Update checksum failed. No files changed.'}
$backup=Join-Path $InstallPath 'resources/app.asar.before-mobile'
if(Test-Path -LiteralPath $backup){throw 'A backup already exists. Restore or review it before installing again.'}
Copy-Item -LiteralPath $target -Destination $backup
try {Copy-Item -LiteralPath $update -Destination $target -Force} catch {Copy-Item -LiteralPath $backup -Destination $target -Force;throw}
Write-Output 'Bridge installed. Start LearnHarness, press Alt to show its menu, and choose LearnHarness > Connect Android phone.'
Write-Output 'Your existing learning data and provider configuration were not moved or edited.'
