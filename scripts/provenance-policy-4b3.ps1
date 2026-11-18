# Offline validation model: a successful result never grants device access.
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
function Assert-4b3FixtureProvenance($Payload,[byte[]]$Signature,[byte[]]$PublicKey,[string]$Pin,[string]$BuildId,[DateTimeOffset]$Now) {
    $bytes=[Text.Encoding]::UTF8.GetBytes($Payload)
    Assert-4b2Signature $bytes $Signature $PublicKey $Pin 'RSA-PSS-SHA256'
    $r=$Payload | ConvertFrom-Json -ErrorAction Stop
    if($r.buildId -cne $BuildId -or $r.environmentDigest -cnotmatch '^[a-f0-9]{64}$' -or $r.dependencyDigest -cnotmatch '^[a-f0-9]{64}$') {throw 'Missing or mismatched provenance'}
    $expires=[DateTimeOffset]::Parse($r.expiresUtc)
    if($expires -le $Now) {throw 'Expired provenance'}
    foreach($key in @('hostSha256','testSha256','hostManifestSha256','testManifestSha256')) {if($r.$key -cnotmatch '^[a-f0-9]{64}$'){throw 'Missing artifact digest'}}
}
