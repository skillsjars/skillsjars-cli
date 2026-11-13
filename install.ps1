# Installs sjm (SkillsJars Manager) on Windows x64:
#
#   irm https://raw.githubusercontent.com/skillsjars/skillsjars-cli/main/install.ps1 | iex
#
# Downloads the latest release, checks it against the release's SHA256SUMS, installs it to $env:SJM_INSTALL_DIR
# (default %LOCALAPPDATA%\Programs\sjm), and adds that directory to the user PATH. Set $env:SJM_VERSION (e.g. 0.1.0)
# to install a specific release.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoUrl = if ($env:SJM_RELEASES_URL) { $env:SJM_RELEASES_URL } else { 'https://github.com/skillsjars/skillsjars-cli' }
$installDir = if ($env:SJM_INSTALL_DIR) { $env:SJM_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\sjm' }

if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw "sjm install: no sjm release for Windows $([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)"
}
$asset = 'sjm-windows-x64.exe'

$version = $env:SJM_VERSION
if (-not $version) {
    # The latest release, from the redirect of /releases/latest (no GitHub API rate limit).
    $request = [System.Net.HttpWebRequest]::Create("$repoUrl/releases/latest")
    $request.AllowAutoRedirect = $false
    $response = $request.GetResponse()
    $location = $response.Headers['Location']
    $response.Close()
    if (-not $location -or $location -notmatch '/tag/v(.+)$') { throw "sjm install: can't find the latest release at $repoUrl/releases" }
    $version = $Matches[1]
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    $base = "$repoUrl/releases/download/v$version"
    Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $tmp $asset)
    Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS')

    $expected = Get-Content (Join-Path $tmp 'SHA256SUMS') |
        ForEach-Object { $parts = $_ -split '\s+', 2; if ($parts.Count -eq 2 -and $parts[1].TrimStart('*') -eq $asset) { $parts[0].ToLower() } } |
        Select-Object -First 1
    if (-not $expected) { throw "sjm install: SHA256SUMS has no $asset" }
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $asset)).Hash.ToLower()
    if ($actual -ne $expected) { throw "sjm install: $asset checksum is $actual, expected $expected" }

    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    $target = Join-Path $installDir 'sjm.exe'
    # A running sjm.exe can't be overwritten, but it can be renamed.
    if (Test-Path $target) { Move-Item -Force $target "$target.old" }
    Move-Item -Force (Join-Path $tmp $asset) $target
    Write-Host "Installed sjm $version to $target"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $installDir) {
    [Environment]::SetEnvironmentVariable('Path', ($(if ($userPath) { "$userPath;" } else { '' }) + $installDir), 'User')
    Write-Host "Added $installDir to your user PATH. Open a new terminal to run sjm."
}
