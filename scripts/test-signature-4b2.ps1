$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
$key=[Security.Cryptography.RSA]::Create(2048)
$other=[Security.Cryptography.RSA]::Create(2048)
try {
    $bytes=[Text.Encoding]::UTF8.GetBytes('{"buildId":"fixture"}')
    $public=$key.ExportSubjectPublicKeyInfo()
    $sha=[Security.Cryptography.SHA256]::Create(); try {$pin=([BitConverter]::ToString($sha.ComputeHash($public))).Replace('-','').ToLowerInvariant()} finally {$sha.Dispose()}
    $sig=$key.SignData($bytes,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)
    Assert-4b2Signature $bytes $sig $public $pin 'RSA-PSS-SHA256'
    function Rejected($name,$content,$signature,$pub,$fingerprint,$algorithm) { $rejected=$false; try { Assert-4b2Signature $content $signature $pub $fingerprint $algorithm } catch {$rejected=$true}; if(!$rejected){throw "Unexpected acceptance: $name"}; "PASS $name" }
    Rejected 'content changed' ([Text.Encoding]::UTF8.GetBytes('changed')) $sig $public $pin 'RSA-PSS-SHA256'
    $badSig=[byte[]]$sig.Clone(); $badSig[0]=$badSig[0] -bxor 1
    Rejected 'signature changed' $bytes $badSig $public $pin 'RSA-PSS-SHA256'
    Rejected 'public key replaced' $bytes $sig ($other.ExportSubjectPublicKeyInfo()) $pin 'RSA-PSS-SHA256'
    Rejected 'wrong algorithm' $bytes $sig $public $pin 'RSA-PKCS1-SHA256'
    Rejected 'pin missing' $bytes $sig $public '' 'RSA-PSS-SHA256'
    'PASS valid RSA-PSS fixture; ephemeral keys are not a trust root'
} finally {$key.Dispose();$other.Dispose()}
