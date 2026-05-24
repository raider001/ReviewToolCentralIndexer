#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not installed or not on PATH."
    exit 1
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "Maven (mvn) is not installed or not on PATH."
    exit 1
}

Write-Host "Building JAR..." -ForegroundColor Yellow
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

Write-Host "Building Docker image..." -ForegroundColor Yellow
$ErrorActionPreference = "Continue"
docker build --pull=false -t reviewtoolcentralindexer-indexer $ScriptDir
if ($LASTEXITCODE -ne 0) {
    $ErrorActionPreference = "Stop"
    Write-Error "Docker image build failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "Restarting indexer..." -ForegroundColor Yellow
docker compose up -d --no-deps --force-recreate indexer
if ($LASTEXITCODE -ne 0) {
    $ErrorActionPreference = "Stop"
    Write-Error "docker compose failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "Indexer restarted." -ForegroundColor Green
Write-Host "Logs: docker compose logs -f indexer"
Write-Host ""
