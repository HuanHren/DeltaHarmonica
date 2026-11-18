$ErrorActionPreference='Stop'
$packagePath = '../app/src/{0}/java/com/huanhren/deltaharmonica/{1}.kt'
$workspace = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot ($packagePath -f 'androidTest','WorkspaceProtectionDeviceTest'))
$application = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot ($packagePath -f 'instrumented','IsolatedTestApplication'))
if (!$workspace.Contains('app.newTestDirectory("workspace_library")')) { throw 'The workspace repository is outside the run scope' }
foreach ($needle in @('safe-test-runs','SAFE-TEST-','UUID.randomUUID','NOFOLLOW_LINKS','Files.createDirectory')) {
    if (!$application.Contains($needle)) { throw "Missing test data control: $needle" }
}
if ($workspace -match 'deleteRecursively\s*\(') { throw 'Automatic recursive deletion remains' }
'PASS workspace run directory and retention source checks (static only; no device access)'
