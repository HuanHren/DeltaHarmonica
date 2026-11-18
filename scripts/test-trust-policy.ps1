$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'trust-policy.ps1')
$key=[Security.Cryptography.RSA]::Create(2048)
try {
 $pub=$key.ExportSubjectPublicKeyInfo();$sha=[Security.Cryptography.SHA256]::Create();try{$pin=([BitConverter]::ToString($sha.ComputeHash($pub))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 $trusted=@{ fingerprint=$pin; status='active'; minimumSequence=3 }
 $payload='{"version":1,"keyFingerprint":"'+$pin+'","sequence":3,"issuedUtc":"2026-01-01T00:00:00Z","expiresUtc":"2027-01-01T00:00:00Z","buildId":"fixture"}'
 function Sign([string]$value){return ,$key.SignData([Text.Encoding]::UTF8.GetBytes($value),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)}
 function Reject($name,$text,$sig,$public,$anchor,$now){$ok=$false;try{Test-TrustPolicy $text $sig $public $anchor $now|Out-Null}catch{$ok=$true};if(!$ok){throw "Expected rejection: $name"};"PASS $name"}
 $now=[DateTimeOffset]'2026-06-01T00:00:00Z';$sig=Sign $payload
 Test-TrustPolicy $payload $sig $pub $trusted $now|Out-Null;'PASS approved fixture'
 Reject 'tampered payload' ($payload.Replace('fixture','changed')) $sig $pub $trusted $now
 Reject 'wrong signature' $payload (Sign 'other') $pub $trusted $now
 $revoked=$trusted.Clone();$revoked.status='revoked';Reject 'revoked key' $payload $sig $pub $revoked $now
 $pending=$trusted.Clone();$pending.status='pending';Reject 'pending key' $payload $sig $pub $pending $now
 $rotated=$trusted.Clone();$rotated.minimumSequence=4;Reject 'sequence replay' $payload $sig $pub $rotated $now
 Reject 'expired' $payload $sig $pub $trusted ([DateTimeOffset]'2027-01-01T00:00:00Z')
 $v=$payload.Replace('"version":1','"version":0');Reject 'version downgrade' $v (Sign $v) $pub $trusted $now
 $noncanonical=$payload.Replace(',',', ');Reject 'noncanonical bytes' $noncanonical (Sign $noncanonical) $pub $trusted $now
 $next=[Security.Cryptography.RSA]::Create(2048)
 try {
  $newPub=$next.ExportSubjectPublicKeyInfo();$h=[Security.Cryptography.SHA256]::Create();try{$newPin=([BitConverter]::ToString($h.ComputeHash($newPub))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}
  $rotatedPayload=$payload.Replace($pin,$newPin);$rotatedSig=$next.SignData([Text.Encoding]::UTF8.GetBytes($rotatedPayload),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)
  Reject 'unapproved rotated key' $rotatedPayload $rotatedSig $newPub $trusted $now
  $approved=@{fingerprint=$newPin;status='active';minimumSequence=3}
  Test-TrustPolicy $rotatedPayload $rotatedSig $newPub $approved $now|Out-Null
  Test-TrustPolicy $payload $sig $pub $trusted $now|Out-Null
  'PASS independently supplied rotated and prior anchors'
 }finally{$next.Dispose()}
}finally{$key.Dispose()}
