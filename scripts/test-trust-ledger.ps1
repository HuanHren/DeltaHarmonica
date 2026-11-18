$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'trust-ledger.ps1')
$root=[IO.Path]::Combine([IO.Path]::GetTempPath(),('dh-ledger-'+[guid]::NewGuid().ToString('N')))
[IO.Directory]::CreateDirectory($root)|Out-Null
$key=[Security.Cryptography.RSA]::Create(2048)
try {
 $pub=$key.ExportSubjectPublicKeyInfo();$sha=[Security.Cryptography.SHA256]::Create();try{$pin=([BitConverter]::ToString($sha.ComputeHash($pub))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 $path=Join-Path $root 'ledger.json'; $now=[DateTimeOffset]'2026-06-01Z'
 $p='{"version":1,"keyFingerprint":"'+$pin+'","sequence":1,"issuedUtc":"2026-01-01T00:00:00Z","expiresUtc":"2027-01-01T00:00:00Z","buildId":"fixture"}'
 $s=$key.SignData([Text.Encoding]::UTF8.GetBytes($p),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)
 $blocked=$false;try{Invoke-OfflineLedgerAccept $path $p $s $pub $now}catch{$blocked=$true};if(!$blocked){throw 'Missing ledger accepted'}
 $state=@{schemaVersion=1;fingerprint=$pin;status='active';lastSequence=0};[IO.File]::WriteAllText($path,($state|ConvertTo-Json -Compress))
 Invoke-OfflineLedgerAccept $path $p $s $pub $now
 $disk=Get-Content -Raw $path|ConvertFrom-Json;if($disk.lastSequence -ne 1){throw 'Sequence not persisted'}
 $blocked=$false;try{Invoke-OfflineLedgerAccept $path $p $s $pub $now}catch{$blocked=$true};if(!$blocked){throw 'Replay accepted'}
 $p2=$p.Replace('"sequence":1','"sequence":2');$s2=$key.SignData([Text.Encoding]::UTF8.GetBytes($p2),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)
 $ledgerScript=Join-Path $PSScriptRoot 'trust-ledger.ps1'
 $jobs=@(1..2|ForEach-Object {Start-Job -ArgumentList $ledgerScript,$path,$p2,([Convert]::ToBase64String($s2)),([Convert]::ToBase64String($pub)) -ScriptBlock {param($script,$statePath,$payload,$sig,$public);. $script;try{Invoke-OfflineLedgerAccept $statePath $payload ([Convert]::FromBase64String($sig)) ([Convert]::FromBase64String($public)) ([DateTimeOffset]'2026-06-01Z')|Out-Null;'accepted'}catch{'rejected'}}})
 try {$answers=@(foreach($j in $jobs){if(!(Wait-Job $j -Timeout 20)){throw 'Child timed out'};Receive-Job $j -ErrorAction Stop});if(@($answers|Where-Object {$_ -eq 'accepted'}).Count -ne 1 -or @($answers|Where-Object {$_ -eq 'rejected'}).Count -ne 1){throw 'Concurrent sequence accepted incorrectly'}}finally{foreach($j in $jobs){Remove-Job $j -Force}}
 $blocked=$false;try{Invoke-OfflineLedgerAccept $path $p2 $s2 $pub $now}catch{$blocked=$true};if(!$blocked){throw 'Post-process replay accepted'}
 'PASS concurrent and post-process replay checks (offline fixture only)'
 [IO.File]::WriteAllText($path,'{broken');$blocked=$false;try{Invoke-OfflineLedgerAccept $path $p $s $pub $now}catch{$blocked=$true};if(!$blocked){throw 'Corrupt ledger accepted'}
 'PASS missing state, committed sequence, replay and corruption rejected (offline fixture only)'
}finally{
 $key.Dispose();$resolved=(Resolve-Path -LiteralPath $root).ProviderPath
 if($resolved -cne $root -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-ledger-')){throw 'Unsafe fixture target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
