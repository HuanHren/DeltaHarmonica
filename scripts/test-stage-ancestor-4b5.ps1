$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'stage4b-offline.ps1')
$root=[IO.Path]::Combine([IO.Path]::GetTempPath(),('dh-ancestor-'+[guid]::NewGuid().ToString('N')))
[IO.Directory]::CreateDirectory($root) | Out-Null
try {
 $actual=Join-Path $root 'actual';[IO.Directory]::CreateDirectory($actual)|Out-Null
 $link=Join-Path $root 'junction'; New-Item -ItemType Junction -Path $link -Target $actual | Out-Null
 $source=Join-Path $root 'fixture';[IO.File]::WriteAllText($source,'fixture')
 $hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
 $rejected=$false;try {Publish-Stage4bFile $source (Join-Path $link 'nested') $hash | Out-Null}catch{$rejected=$true}
 if(!$rejected){throw 'Reparse ancestor was accepted'}
 'PASS reparse ancestor rejected before staging'
} finally {
 if(Test-Path -LiteralPath $link){[IO.Directory]::Delete($link)}
 $resolved=(Resolve-Path -LiteralPath $root).ProviderPath
 if($resolved -cne $root -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-ancestor-')){throw 'Unsafe fixture target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
