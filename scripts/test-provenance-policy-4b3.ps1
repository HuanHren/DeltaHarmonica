$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'provenance-policy-4b3.ps1')
$key=[Security.Cryptography.RSA]::Create(2048)
try {
 $pub=$key.ExportSubjectPublicKeyInfo();$sha=[Security.Cryptography.SHA256]::Create();try {$pin=([BitConverter]::ToString($sha.ComputeHash($pub))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 $base=@{buildId='fixture';environmentDigest=('e'*64);dependencyDigest=('f'*64);expiresUtc='2030-01-01T00:00:00Z';hostSha256=('a'*64);testSha256=('b'*64);hostManifestSha256=('c'*64);testManifestSha256=('d'*64)}
 function EncodeAndSign($record) {$p=$record|ConvertTo-Json -Compress;$s=$key.SignData([Text.Encoding]::UTF8.GetBytes($p),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss);return @($p,$s)}
 $v=EncodeAndSign $base; Assert-4b3FixtureProvenance $v[0] $v[1] $pub $pin 'fixture' ([DateTimeOffset]'2029-01-01Z'); 'PASS valid fixture'
 foreach($case in @('wrong-build','expired','missing-deps','missing-environment','wrong-apk')) {
  $x=$base.Clone();switch($case){'wrong-build'{$x.buildId='old'} 'expired'{$x.expiresUtc='2020-01-01Z'} 'missing-deps'{$x.dependencyDigest=''} 'missing-environment'{$x.environmentDigest=''} 'wrong-apk'{$x.hostSha256='invalid'}}
  $v=EncodeAndSign $x;$rejected=$false;try{Assert-4b3FixtureProvenance $v[0] $v[1] $pub $pin 'fixture' ([DateTimeOffset]'2029-01-01Z')}catch{$rejected=$true};if(!$rejected){throw "Unsafe acceptance: $case"};"PASS $case"
 }
 foreach($case in @('malformed-deps','malformed-environment')) {
  $x=$base.Clone(); if($case -eq 'malformed-deps'){$x.dependencyDigest='not-a-sha256'}else{$x.environmentDigest='not-a-sha256'}
  $v=EncodeAndSign $x;$rejected=$false;try{Assert-4b3FixtureProvenance $v[0] $v[1] $pub $pin 'fixture' ([DateTimeOffset]'2029-01-01Z')}catch{$rejected=$true};if(!$rejected){throw "Unsafe acceptance: $case"};"PASS $case"
 }
} finally {$key.Dispose()}
