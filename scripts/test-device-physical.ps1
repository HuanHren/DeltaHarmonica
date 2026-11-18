[CmdletBinding()]
param(
    [ValidateSet('Audit','Build','Verify','Install','Run','PostCheck')][string]$Action='Audit',
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial
)
$ErrorActionPreference='Stop'
# This is a fail-closed scaffold. It has no ADB transport and cannot authorize
# installation or instrumentation until the missing safety proofs exist.
switch ($Action) {
    'Audit' {
        $processes=@(Get-Process adb -ErrorAction SilentlyContinue | Select-Object Id,Path)
        $listener=@(Get-NetTCPConnection -State Listen -LocalPort 5037 -ErrorAction SilentlyContinue | Select-Object LocalAddress,OwningProcess)
        [pscustomobject]@{ DeviceIdentity='NOT_CHECKED'; AdbProcesses=$processes; ServerListeners=$listener; SerialLogged=$false; Status='BLOCKED' }
    }
    'Build' {
        & (Join-Path $PSScriptRoot '..\gradlew.bat') assembleInstrumented assembleInstrumentedAndroidTest
        if($LASTEXITCODE -ne 0) { throw 'Build failed' }
        Write-Warning 'Build outputs alone do not prove same-invocation provenance.'
    }
    default { throw 'BLOCKED: physical-device authorization and transport are not implemented or verified; no device command executed.' }
}
