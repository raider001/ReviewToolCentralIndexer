#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds and starts the Central Indexer service using Docker Compose.

.DESCRIPTION
    Prompts for any required environment variables that are not already set,
    writes a .env file, then runs docker compose up --build -d.

    Required variables:
      POSTGRES_PASSWORD  - Password for the PostgreSQL database.
      BEARER_TOKEN       - Bearer token clients must supply to access /events.
      GITHUB_API_TOKEN   - GitHub personal access token (used for startup reconciliation).

    The webhook secret is hardcoded to "banana" in config.json.

.NOTES
    Run from the ReviewToolCentralIndexer directory:
        .\start.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

function Read-ValueOrDefault {
    param(
        [string]$EnvVar,
        [string]$Prompt,
        [string]$Default = ""
    )

    $existing = [System.Environment]::GetEnvironmentVariable($EnvVar)
    if ($existing) { return $existing }

    if ($Default) {
        $value = Read-Host "$Prompt [Enter for default: $Default]"
        if (-not $value) { return $Default }
        return $value
    } else {
        $value = Read-Host $Prompt
        return $value
    }
}

function Resolve-JavaHome {
    $candidate = $env:JAVA_HOME
    if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe"))) {
        return $candidate
    }

    try {
        $key = Get-Item "HKLM:\SOFTWARE\JavaSoft\JDK" -ErrorAction Stop
        $ver = $key.GetValue("CurrentVersion")
        $home = (Get-Item "HKLM:\SOFTWARE\JavaSoft\JDK\$ver" -ErrorAction Stop).GetValue("JavaHome")
        if ($home -and (Test-Path (Join-Path $home "bin\javac.exe"))) {
            return $home
        }
    } catch {}

    try {
        $key = Get-Item "HKLM:\SOFTWARE\JavaSoft\Java Development Kit" -ErrorAction Stop
        $ver = $key.GetValue("CurrentVersion")
        $home = (Get-Item "HKLM:\SOFTWARE\JavaSoft\Java Development Kit\$ver" -ErrorAction Stop).GetValue("JavaHome")
        if ($home -and (Test-Path (Join-Path $home "bin\javac.exe"))) {
            return $home
        }
    } catch {}

    $javaExe = Get-Command java -ErrorAction SilentlyContinue
    if ($javaExe) {
        $dir = (Get-Item $javaExe.Source).Directory
        while ($dir) {
            if (Test-Path (Join-Path $dir.FullName "bin\javac.exe")) {
                return $dir.FullName
            }
            $dir = $dir.Parent
        }
    }

    return $null
}

Write-Host ""
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "  Central Indexer - GitHub Setup"                 -ForegroundColor Cyan
Write-Host "  Webhook secret: banana"                         -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host ""

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not installed or not on PATH. Please install Docker Desktop and try again."
    exit 1
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "Maven (mvn) is not installed or not on PATH. Please install Maven and try again."
    exit 1
}

$pgPassword     = Read-ValueOrDefault -EnvVar "POSTGRES_PASSWORD" -Prompt "PostgreSQL password"                                        -Default "changeme"
$bearerToken    = Read-ValueOrDefault -EnvVar "BEARER_TOKEN"       -Prompt "Bearer token for /events"
$githubApiToken = Read-ValueOrDefault -EnvVar "GITHUB_API_TOKEN"   -Prompt "GitHub API token (leave blank to skip reconciliation)"     -Default ""

$envFile = Join-Path $ScriptDir ".env"
$envLines = @(
    "POSTGRES_PASSWORD=$pgPassword",
    "BEARER_TOKEN=$bearerToken",
    "GITHUB_API_TOKEN=$githubApiToken"
)
$envLines | Set-Content -Path $envFile -Encoding UTF8

Write-Host ""
Write-Host "Written: .env" -ForegroundColor Green
Write-Host ""

$ProjectRoot = Split-Path -Parent $ScriptDir

$env:JAVA_HOME = Resolve-JavaHome
if (-not $env:JAVA_HOME) {
    Write-Error "Could not locate a JDK. Please install a JDK and set the JAVA_HOME environment variable."
    exit 1
}
Write-Host "Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor DarkGray

Write-Host "Building JAR (this may take a minute on first run)..." -ForegroundColor Yellow
Push-Location $ProjectRoot
try {
    mvn -pl ReviewToolCentralIndexerPluginApi,ReviewToolCentralIndexer `
        -am package -DskipTests --no-transfer-progress
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven build failed with exit code $LASTEXITCODE"
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "JAR built successfully." -ForegroundColor Green
Write-Host ""

Write-Host "Building and starting services..." -ForegroundColor Yellow
$ErrorActionPreference = "Continue"
docker build --pull=false -t reviewtoolcentralindexer-indexer $ScriptDir
if ($LASTEXITCODE -ne 0) {
    $ErrorActionPreference = "Stop"
    Write-Error "Docker image build failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    $ErrorActionPreference = "Stop"
    Write-Error "docker compose failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=================================================" -ForegroundColor Green
Write-Host "  Services started successfully!"                  -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Health check : http://localhost:8765/health"
Write-Host "  Events API   : http://localhost:8765/events"
Write-Host "  SSE stream   : http://localhost:8765/events/stream"
Write-Host "  Webhook URL  : http://localhost:8765/webhooks/github"
Write-Host "  Secret       : banana"
Write-Host ""
Write-Host "Logs: docker compose logs -f indexer"
Write-Host ""
