# Generate the release signing key for the Android app, outside the repository:
# ..\signing\ (next to android\, common\, client\, server\; not in any git repo).
#
#   .\scripts\new-android-keystore.ps1                  # create the key
#   .\scripts\new-android-keystore.ps1 -SetGitHubSecrets  # create it (or reuse it) and store it as Actions secrets
#
# Back up the .jks and the password file (e.g. in the password manager). If the
# key is lost, installed apps can't be updated by a new build: users have to
# uninstall first. -Force replaces an existing key; only for a deliberate rotation.
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SetGitHubSecrets,
    [string]$Repo = 'stevennight/NyaRemoteControl-android'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path (Split-Path -Parent $root) 'signing'
$keystore = Join-Path $dir 'nya-remote-android-release.jks'
$info = Join-Path $dir 'nya-remote-android-release.txt'
$alias = 'nya-remote'

function New-RandomPassword {
    $bytes = [byte[]]::new(32)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Read-Info {
    $map = @{}
    foreach ($line in Get-Content -LiteralPath $info) {
        if ($line -match '^(ANDROID_[A-Z_]+)=(.*)$') { $map[$Matches[1]] = $Matches[2] }
    }
    return $map
}

$exists = (Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $info)
if ($exists -and $Force) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    foreach ($f in @($keystore, $info)) {
        if (Test-Path -LiteralPath $f) { Move-Item -LiteralPath $f -Destination "$f.replaced-$stamp" }
    }
    $exists = $false
}

if (-not $exists) {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $keytool) { throw 'keytool not found: install a JDK (17+) or put its bin directory on PATH.' }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    # PKCS12 uses one password for the store and the key.
    $password = New-RandomPassword
    & $keytool.Source -genkeypair -keystore $keystore -storetype PKCS12 -alias $alias `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass $password -keypass $password `
        -dname 'CN=NyaRemoteControl Android, O=NyaRemoteControl, C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
    $sha256 = (& $keytool.Source -list -v -keystore $keystore -storepass $password -alias $alias |
        Select-String 'SHA256:').ToString().Trim()
    @(
        '# NyaRemoteControl Android release signing key. Keep with the .jks; never commit.'
        "# Created $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'). Certificate $sha256"
        "ANDROID_KEYSTORE_FILE=$keystore"
        "ANDROID_KEYSTORE_PASSWORD=$password"
        "ANDROID_KEY_ALIAS=$alias"
        "ANDROID_KEY_PASSWORD=$password"
    ) | Set-Content -LiteralPath $info -Encoding utf8
    Write-Host "created $keystore" -ForegroundColor Green
} else {
    Write-Host "keeping the existing key $keystore (use -Force to replace it)" -ForegroundColor Yellow
}

if ($SetGitHubSecrets) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) { throw 'gh (GitHub CLI) not found.' }
    $v = Read-Info
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    # Values go through stdin, not the command line.
    $b64 | & $gh.Source secret set ANDROID_KEYSTORE_BASE64 --repo $Repo
    $v['ANDROID_KEYSTORE_PASSWORD'] | & $gh.Source secret set ANDROID_KEYSTORE_PASSWORD --repo $Repo
    $v['ANDROID_KEY_ALIAS'] | & $gh.Source secret set ANDROID_KEY_ALIAS --repo $Repo
    $v['ANDROID_KEY_PASSWORD'] | & $gh.Source secret set ANDROID_KEY_PASSWORD --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "gh secret set failed with exit code $LASTEXITCODE" }
    Write-Host "Actions secrets set on $Repo" -ForegroundColor Green
}

Write-Host "key file:      $keystore"
Write-Host "passwords etc: $info"
Write-Host 'Back up both files (password manager). Losing the key means installed apps cannot be updated.' -ForegroundColor Yellow
