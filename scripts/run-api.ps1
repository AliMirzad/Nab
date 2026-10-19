param([switch]$Demo, [string]$Maven = 'mvn', [string]$MavenRepository = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location (Join-Path $repo 'backend')
if ($Demo) { $env:DEMO_MODE = 'true' } else { $env:DEMO_MODE = 'false' }
if (!(Get-Command $Maven -ErrorAction SilentlyContinue)) { throw 'Install Maven 3.9+ and Java 21+ or pass -Maven with the full Maven executable path.' }
$mavenArgs = @('spring-boot:run')
if ($MavenRepository) { $mavenArgs = @("-Dmaven.repo.local=$MavenRepository") + $mavenArgs }
& $Maven @mavenArgs
exit $LASTEXITCODE
