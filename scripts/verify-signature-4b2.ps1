# Offline cryptographic primitive only: no provenance authorization or trust provisioning.
function Assert-4b2Signature([byte[]]$Payload,[byte[]]$Signature,[byte[]]$PublicKey,[string]$PinnedSha256,[string]$Algorithm) {
    if($Algorithm -cne 'RSA-PSS-SHA256' -or $PinnedSha256 -cnotmatch '^[a-f0-9]{64}$') {throw 'Unknown algorithm or missing pin'}
    $sha=[Security.Cryptography.SHA256]::Create()
    try {$actual=([BitConverter]::ToString($sha.ComputeHash($PublicKey))).Replace('-','').ToLowerInvariant()} finally {$sha.Dispose()}
    if($actual -cne $PinnedSha256) {throw 'Public key pin mismatch'}
    $rsa=[Security.Cryptography.RSA]::Create()
    try {
        $consumed=0; $rsa.ImportSubjectPublicKeyInfo($PublicKey,[ref]$consumed)
        if($consumed -ne $PublicKey.Length -or $rsa.KeySize -lt 2048 -or !$rsa.VerifyData($Payload,$Signature,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)) {throw 'Invalid signature'}
    } finally {$rsa.Dispose()}
}
