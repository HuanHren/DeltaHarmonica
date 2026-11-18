$ErrorActionPreference='Stop'
$root=[IO.Path]::Combine([IO.Path]::GetTempPath(),('dh-crosslock-'+[guid]::NewGuid().ToString('N')))
[IO.Directory]::CreateDirectory($root) | Out-Null
try {
    $file=Join-Path $root 'fixture.apk'; [IO.File]::WriteAllText($file,'fixture')
    $before=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    $handle=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        foreach($kind in @('write','delete','rename','replace')) {
            $job=Start-Job -ArgumentList $file,$root,$kind -ScriptBlock {
                param($file,$root,$kind)
                try {
                    switch($kind) {
                        'write' {[IO.File]::WriteAllText($file,'mutation')}
                        'delete' {[IO.File]::Delete($file)}
                        'rename' {[IO.File]::Move($file,(Join-Path $root 'renamed.apk'))}
                        'replace' { $source=Join-Path $root 'source.tmp';[IO.File]::WriteAllText($source,'replacement');[IO.File]::Replace($source,$file,(Join-Path $root 'backup.tmp')) }
                    }
                    'unexpected-success'
                } catch [IO.IOException] { 'sharing-rejected' } catch [UnauthorizedAccessException] { 'sharing-rejected' }
            }
            try {
                if(!(Wait-Job $job -Timeout 20)){throw 'Child timed out'}
                $result=@(Receive-Job $job -ErrorAction Stop)
                if($job.State -ne 'Completed' -or $result.Count -ne 1 -or $result[0] -cne 'sharing-rejected'){throw "Unexpected child result: $kind $result"}
                "PASS cross-process $kind rejected"
            } finally {Remove-Job $job -Force}
        }
        if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $before){throw 'Fixture changed'}
    } finally {$handle.Dispose()}
    [IO.File]::WriteAllText($file,'released')
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ceq $before){throw 'Release not observed'}
    'PASS mutation after release observed; no installer or ADB invoked'
} finally {
    $resolved=(Resolve-Path -LiteralPath $root).ProviderPath
    if($resolved -cne $root -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-crosslock-')){throw 'Unsafe fixture target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
