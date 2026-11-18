# Offline policy experiment. Caller-provided trust and counters are not a security boundary.
. (Join-Path $PSScriptRoot 'verify-signature-4b2.ps1')
function Test-TrustPolicy([string]$Payload,[byte[]]$Signature,[byte[]]$PublicKey,$Anchor,[DateTimeOffset]$Now) {
    if($null -eq $Anchor -or $Anchor.status -cne 'active' -or $Anchor.fingerprint -cnotmatch '^[a-f0-9]{64}$'){throw 'No active trust anchor'}
    Assert-4b2Signature ([Text.Encoding]::UTF8.GetBytes($Payload)) $Signature $PublicKey $Anchor.fingerprint 'RSA-PSS-SHA256'
    $obj=$Payload|ConvertFrom-Json -AsHashtable -DateKind String -ErrorAction Stop
    $keys=@('version','keyFingerprint','sequence','issuedUtc','expiresUtc','buildId')
    if($obj.Count -ne $keys.Count -or @($keys|Where-Object {!$obj.ContainsKey($_)}).Count -ne 0){throw 'Unexpected schema'}
    if($obj.version -isnot [long] -or $obj.version -ne 1 -or $obj.sequence -isnot [long] -or $obj.sequence -lt [long]$Anchor.minimumSequence -or $obj.keyFingerprint -cne $Anchor.fingerprint -or $obj.buildId -cnotmatch '^[A-Za-z0-9_-]{1,128}$'){throw 'Invalid version, key, sequence or build'}
    foreach($field in @('issuedUtc','expiresUtc')){if($obj[$field] -isnot [string] -or $obj[$field] -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$'){throw 'Invalid timestamp'}}
    $issued=[DateTimeOffset]::ParseExact($obj.issuedUtc,"yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    $expires=[DateTimeOffset]::ParseExact($obj.expiresUtc,"yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    if($issued -gt $Now -or $expires -le $Now -or $expires -le $issued){throw 'Invalid validity period'}
    $canonical='{"version":1,"keyFingerprint":"'+$obj.keyFingerprint+'","sequence":'+$obj.sequence+',"issuedUtc":"'+$obj.issuedUtc+'","expiresUtc":"'+$obj.expiresUtc+'","buildId":"'+$obj.buildId+'"}'
    if($Payload -cne $canonical){throw 'Noncanonical payload'}
    return 'OFFLINE_MODEL_ONLY_NOT_INSTALL_AUTHORIZATION'
}
