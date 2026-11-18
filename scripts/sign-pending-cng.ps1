param([Parameter(Mandatory=$true)][string]$PendingRecord,[Parameter(Mandatory=$true)][string]$InputFile,[Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
$pendingRoot=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'DeltaHarmonicaSafety\pending')).TrimEnd('\')+'\'
$recordPath=(Resolve-Path -LiteralPath $PendingRecord -ErrorAction Stop).ProviderPath
if(!$recordPath.StartsWith($pendingRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected pending record location'}
$record=Get-Content -Raw -LiteralPath $recordPath|ConvertFrom-Json
if($record.status -cne 'PENDING_INDEPENDENT_REVIEW' -or $record.trusted -ne $false -or $record.keyName -cnotmatch '^DeltaHarmonica-Pending-[a-f0-9]{32}$' -or $record.provider -cne 'Microsoft Software Key Storage Provider'){throw 'Unexpected pending key record'}
$payload=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputFile).ProviderPath)
$key=[Security.Cryptography.CngKey]::Open($record.keyName,[Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider)
try {
 if([int]$key.ExportPolicy -ne 0){throw 'Key unexpectedly exportable'}
 $rsa=[Security.Cryptography.RSACng]::new($key)
 try {$sig=$rsa.SignData($payload,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss)} finally {$rsa.Dispose()}
 Assert-4b2Signature $payload $sig ([Convert]::FromBase64String($record.publicKeySpkiBase64)) $record.publicKeySha256 'RSA-PSS-SHA256'
 $sha=[Security.Cryptography.SHA256]::Create();try {$hash=([BitConverter]::ToString($sha.ComputeHash($payload))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 $envelope=@{schemaVersion=1;status='PENDING_UNTRUSTED';trusted=$false;algorithm='RSA-PSS-SHA256';publicKeySha256=$record.publicKeySha256;payloadSha256=$hash;signatureBase64=[Convert]::ToBase64String($sig);installAuthorization='BLOCKED'}
 $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($envelope|ConvertTo-Json -Depth 5))
 $out=[IO.FileStream]::new([IO.Path]::GetFullPath($OutputFile),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try {$out.Write($bytes,0,$bytes.Length);$out.Flush($true)}finally{$out.Dispose()}
 'Pending signature verified locally; not trusted and not installation authorization.'
}finally{$key.Dispose()}
