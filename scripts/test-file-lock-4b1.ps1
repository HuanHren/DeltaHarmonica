$ErrorActionPreference='Stop'
$root=[IO.Path]::Combine([IO.Path]::GetTempPath(),('dh-lock-'+[guid]::NewGuid().ToString('N')))
[IO.Directory]::CreateDirectory($root) | Out-Null
try {
    $file=Join-Path $root 'fixture.apk'; [IO.File]::WriteAllText($file,'fixture')
    $before=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    $handle=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        foreach($kind in @('write','delete','rename')) {
            $rejected=$false
            try { switch($kind) { 'write' {[IO.File]::WriteAllText($file,'replaced')} 'delete' {[IO.File]::Delete($file)} 'rename' {[IO.File]::Move($file,(Join-Path $root 'moved.apk'))} } } catch [IO.IOException] { $rejected=$true } catch [UnauthorizedAccessException] { $rejected=$true }
            if(!$rejected){throw "Lock did not reject $kind"}; "PASS locked $kind rejected"
        }
        if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $before){throw 'Locked content changed'}
    } finally { $handle.Dispose() }
    [IO.File]::WriteAllText($file,'after-release')
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ceq $before){throw 'Post-release mutation not observed'}
    'PASS post-release mutation observed; no ADB or installer invoked'
} finally {
    $resolved=(Resolve-Path -LiteralPath $root).ProviderPath
    if ($resolved -cne $root -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-lock-')) { throw 'Unsafe fixture target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
