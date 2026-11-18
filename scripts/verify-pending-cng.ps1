param([Parameter(Mandatory=$true)][string]$PendingRecord,[Parameter(Mandatory=$true)][string]$InputFile,[Parameter(Mandatory=$true)][string]$SignatureFile,[Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedFingerprint)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
$record=Get-Content -Raw -LiteralPath $PendingRecord|ConvertFrom-Json
$envelope=Get-Content -Raw -LiteralPath $SignatureFile|ConvertFrom-Json
if($record.publicKeySha256 -cne $ExpectedFingerprint -or $envelope.publicKeySha256 -cne $ExpectedFingerprint -or $envelope.algorithm -cne 'RSA-PSS-SHA256' -or $envelope.status -cne 'PENDING_UNTRUSTED' -or $envelope.trusted -ne $false){throw 'Pending signature metadata mismatch'}
$payload=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputFile).ProviderPath)
$sha=[Security.Cryptography.SHA256]::Create();try {$hash=([BitConverter]::ToString($sha.ComputeHash($payload))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
if($hash -cne $envelope.payloadSha256){throw 'Payload digest mismatch'}
Assert-4b2Signature $payload ([Convert]::FromBase64String($envelope.signatureBase64)) ([Convert]::FromBase64String($record.publicKeySpkiBase64)) $ExpectedFingerprint 'RSA-PSS-SHA256'
[pscustomobject]@{cryptographicSignature='VALID';independentTrust='NOT_ESTABLISHED';expiryAndRevocation='NOT_IMPLEMENTED';installAuthorization='BLOCKED'}
