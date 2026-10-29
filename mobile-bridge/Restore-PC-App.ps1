param([string]$InstallPath = (Join-Path $env:LOCALAPPDATA 'Programs/learnharness'))
$ErrorActionPreference='Stop'
if(Get-Process LearnHarness -ErrorAction SilentlyContinue){throw 'Close LearnHarness before restoring.'}
$manifest=Get-Content (Join-Path $PSScriptRoot 'update-manifest.json') -Raw | ConvertFrom-Json
$target=Join-Path $InstallPath 'resources/app.asar'
$backup=Join-Path $InstallPath 'resources/app.asar.before-mobile'
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.updatedSha256){throw 'The desktop app changed since this update. Refusing to overwrite it.'}
if((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.originalSha256){throw 'Backup checksum failed.'}
Copy-Item -LiteralPath $backup -Destination $target -Force
Write-Output 'Original desktop shell restored. Learning records were not changed.'
