$ErrorActionPreference='Stop'
$taskDir=Join-Path $env:TEMP ('deltaharmonica-guard-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $taskDir | Out-Null
try {
    foreach($fakeName in @('installSafetyProbe','connectedSafetyProbe')) {
        $init=Join-Path $taskDir ($fakeName+'.gradle')
        $sentinel=Join-Path $taskDir ($fakeName+'.executed')
        $sentinelGroovy=$sentinel.Replace('\','/')
        $initBody=@"
gradle.projectsEvaluated {
    def p = gradle.rootProject
    def fake = p.tasks.register('$fakeName') { doLast { new File('$sentinelGroovy').text = 'executed' } }
    p.tasks.named('help') { dependsOn(fake) }
}
"@
        [IO.File]::WriteAllText($init,$initBody,[Text.UTF8Encoding]::new($false))
        $output = & .\gradlew.bat help -I $init --console=plain 2>&1
        if ($LASTEXITCODE -eq 0 -or ($output -join "`n") -notmatch 'SAFE-1: device tasks are blocked' -or (Test-Path -LiteralPath $sentinel)) { throw "Task graph guard failed for $fakeName" }
    }
    'Offline graph guard checks passed: 2 (synthetic tasks; no ADB)'
} finally {
    Get-ChildItem -LiteralPath $taskDir -File | Remove-Item -Force
    Remove-Item -LiteralPath $taskDir -Force
}
