$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'cng-pending.ps1') -LibraryOnly
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
$parameters=[Security.Cryptography.CngKeyCreationParameters]::new()
$parameters.Provider=[Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider
$parameters.ExportPolicy=[Security.Cryptography.CngExportPolicies]::None
$parameters.KeyUsage=[Security.Cryptography.CngKeyUsages]::Signing
$parameters.Parameters.Add([Security.Cryptography.CngProperty]::new('Length',[BitConverter]::GetBytes(2048),[Security.Cryptography.CngPropertyOptions]::None))
$keyName='DeltaHarmonica-Fixture-'+[guid]::NewGuid().ToString('N')
$key=[Security.Cryptography.CngKey]::Create([Security.Cryptography.CngAlgorithm]::Rsa,$keyName,$parameters)
try {
 $result=Get-PendingCngMaterial $key
 if($result.status -cne 'PENDING_INDEPENDENT_REVIEW' -or $result.trusted -ne $false -or $result.exportPolicy -ne 0){throw 'Unsafe pending state'}
 $payload=[Text.Encoding]::UTF8.GetBytes('fixture')
 $rsa=[Security.Cryptography.RSACng]::new($key)
 try { $sig=$rsa.SignData($payload,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss) } finally {$rsa.Dispose()}
 Assert-4b2Signature $payload $sig ([Convert]::FromBase64String($result.publicKeySpkiBase64)) $result.publicKeySha256 'RSA-PSS-SHA256'
 $rejected=$false;try {Assert-4b2Signature ([Text.Encoding]::UTF8.GetBytes('changed')) $sig ([Convert]::FromBase64String($result.publicKeySpkiBase64)) $result.publicKeySha256 'RSA-PSS-SHA256'} catch {$rejected=$true};if(!$rejected){throw 'Tampered signature accepted'}
 'PASS CNG software fixture signed, tampering rejected, trust remains pending'
} finally {if($key.KeyName -cne $keyName){throw 'Unexpected fixture key'};$key.Delete();$key.Dispose()}
