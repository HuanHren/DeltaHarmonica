param([switch]$LibraryOnly)
$ErrorActionPreference='Stop'
function Get-PendingCngMaterial([Security.Cryptography.CngKey]$Key) {
    $rsa=[Security.Cryptography.RSACng]::new($Key)
    try {$public=$rsa.ExportSubjectPublicKeyInfo()} finally {$rsa.Dispose()}
    $sha=[Security.Cryptography.SHA256]::Create()
    try {$pin=([BitConverter]::ToString($sha.ComputeHash($public))).Replace('-','').ToLowerInvariant()} finally {$sha.Dispose()}
    return [ordered]@{schemaVersion=1;status='PENDING_INDEPENDENT_REVIEW';trusted=$false;provider=$Key.Provider.Provider;exportPolicy=[int]$Key.ExportPolicy;publicKeySha256=$pin;publicKeySpkiBase64=[Convert]::ToBase64String($public);algorithm='RSA-PSS-SHA256'}
}
if(!$LibraryOnly) {
    $name='DeltaHarmonica-Pending-'+[guid]::NewGuid().ToString('N')
    $params=[Security.Cryptography.CngKeyCreationParameters]::new()
    $params.Provider=[Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider
    $params.ExportPolicy=[Security.Cryptography.CngExportPolicies]::None
    $params.KeyUsage=[Security.Cryptography.CngKeyUsages]::Signing
    $params.Parameters.Add([Security.Cryptography.CngProperty]::new('Length',[BitConverter]::GetBytes(3072),[Security.Cryptography.CngPropertyOptions]::None))
    $key=[Security.Cryptography.CngKey]::Create([Security.Cryptography.CngAlgorithm]::Rsa,$name,$params)
    try {
        $material=Get-PendingCngMaterial $key
        if($material.exportPolicy -ne 0){throw 'Key unexpectedly exportable'}
        $material.keyName=$name; $material.createdUtc=[DateTime]::UtcNow.ToString('o'); $material.scope='current-user-software-CNG-not-TPM'; $material.installAuthorization='BLOCKED'
        $dir=Join-Path $env:LOCALAPPDATA 'DeltaHarmonicaSafety\pending'
        [IO.Directory]::CreateDirectory($dir)|Out-Null
        $path=Join-Path $dir ($name+'.json')
        $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($material|ConvertTo-Json -Depth 5))
        $file=[IO.FileStream]::new($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$file.Write($bytes,0,$bytes.Length);$file.Flush($true)} finally {$file.Dispose()}
        [pscustomobject]@{publicKeySha256=$material.publicKeySha256;recordPath=$path;status=$material.status;provider=$material.provider;scope=$material.scope;installAuthorization='BLOCKED'}
    } finally {$key.Dispose()}
}
