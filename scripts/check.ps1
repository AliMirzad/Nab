param([string]$Maven = 'mvn', [string]$Flutter = 'flutter')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $repo 'backend')
try { & $Maven verify; if ($LASTEXITCODE) { throw 'Backend check failed' } } finally { Pop-Location }
Push-Location (Join-Path $repo 'web')
try { npm ci; if ($LASTEXITCODE) { throw 'Web dependencies failed' }; npm test; if ($LASTEXITCODE) { throw 'Web tests failed' }; npm run build; if ($LASTEXITCODE) { throw 'Web check failed' } } finally { Pop-Location }
Push-Location (Join-Path $repo 'mobile')
try {
  & $Flutter pub get; if ($LASTEXITCODE) { throw 'Mobile dependencies failed' }
  & $Flutter analyze; if ($LASTEXITCODE) { throw 'Mobile analysis failed' }
  & $Flutter test; if ($LASTEXITCODE) { throw 'Mobile tests failed' }
} finally { Pop-Location }
