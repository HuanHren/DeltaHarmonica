$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'safe-device-input.ps1')

$path = Join-Path ([IO.Path]::GetTempPath()) ('dh-safe-input-' + [guid]::NewGuid().ToString('N'))
$root = New-PrivateApkDirectory $path
$file = Join-Path $root 'fixture.apk'
[IO.File]::WriteAllText($file, 'approved apk bytes')
$expected = (Get-FileHash -LiteralPath $file).Hash
function Assert-Rejected([scriptblock]$Probe) {
    try { & $Probe } catch [IO.IOException] { return }
    throw 'Expected the install-input check to reject the fixture'
}

$hardlink = Join-Path $root 'other-name.apk'
New-Item -ItemType HardLink -Path $hardlink -Target $file | Out-Null
try {
    Assert-Rejected { (Open-InstallFile $file).Dispose() }
    'PASS multiple hard links rejected'
} finally { [IO.File]::Delete($hardlink) }

$junction = $root + '.junction'
New-Item -ItemType Junction -Path $junction -Target $root | Out-Null
try {
    Assert-Rejected { (Open-InstallFile (Join-Path $junction 'fixture.apk')).Dispose() }
    'PASS junction ancestor rejected'
} finally { [IO.Directory]::Delete($junction) }

$lease = Open-InstallFile $file
try {
    Assert-Rejected { Assert-InstallFile $lease ('0' * 64) }
    'PASS unauthorized digest rejected'
    $shell = (Get-Process -Id $PID).Path
    foreach ($case in @('read','write','delete','rename','parent')) {
        $env:DH_TEST_APK = $file
        $code = switch ($case) {
            'read' { '[void][IO.File]::ReadAllBytes($env:DH_TEST_APK)' }
            'write' { '[IO.File]::WriteAllText($env:DH_TEST_APK,"unexpected")' }
            'delete' { '[IO.File]::Delete($env:DH_TEST_APK)' }
            'rename' { '[IO.File]::Move($env:DH_TEST_APK,$env:DH_TEST_APK+".moved")' }
            'parent' { $p = '[IO.Path]::GetDirectoryName($env:DH_TEST_APK)'; "[IO.Directory]::Move($p,$p+'.moved')" }
        }
        $program = '$ErrorActionPreference="Stop"; try {' + $code + '} catch [IO.IOException] { exit 23 }'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($program))
        & $shell -NoProfile -EncodedCommand $encoded
        $want = if ($case -eq 'read') { 0 } else { 23 }
        if ($LASTEXITCODE -ne $want) { throw "Unexpected competing process outcome: $case ($LASTEXITCODE)" }
        Assert-InstallFile $lease $expected
        "PASS subprocess $case"
    }
} finally {
    $lease.Dispose()
    Remove-Item Env:DH_TEST_APK -ErrorAction SilentlyContinue
}
$writer = [IO.File]::Open($file,'Open','Write','ReadWrite')
try {
    $failure = try { (Open-InstallFile $file).Dispose(); $false } catch [IO.IOException] { $true }
    if (!$failure) { throw 'An active writer was accepted' }
    'PASS active writer rejected'
} finally { $writer.Dispose() }
[IO.File]::Delete($file)
[IO.Directory]::Delete($root)
$mutex = Enter-DeviceRun
try {
    $env:DH_APK_INPUT_SCRIPT = Join-Path $PSScriptRoot 'safe-device-input.ps1'
    $program = '. $env:DH_APK_INPUT_SCRIPT; try { (Enter-DeviceRun).Dispose(); exit 1 } catch [IO.IOException] { exit 23 }'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($program))
    & $shell -NoProfile -EncodedCommand $encoded
    if ($LASTEXITCODE -ne 23) { throw 'Second process was not rejected by the runner lock' }
    'PASS concurrent runner rejected'
} finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
    Remove-Item Env:DH_APK_INPUT_SCRIPT -ErrorAction SilentlyContinue
}
