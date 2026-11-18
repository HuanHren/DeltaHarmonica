# Offline persistence experiment. Never grants installation or establishes a trust root.
. (Join-Path $PSScriptRoot 'trust-policy.ps1')
function Invoke-OfflineLedgerAccept([string]$Path,[string]$Payload,[byte[]]$Signature,[byte[]]$PublicKey,[DateTimeOffset]$Now) {
    $full=[IO.Path]::GetFullPath($Path)
    $sha=[Security.Cryptography.SHA256]::Create();try{$lockHash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($full.ToUpperInvariant())))).Replace('-','')}finally{$sha.Dispose()}
    $mutex=[Threading.Mutex]::new($false,('Local\DeltaHarmonicaLedgerFixture-'+$lockHash))
    $held=$false
    try {
        try {$held=$mutex.WaitOne([TimeSpan]::FromSeconds(10))}catch [Threading.AbandonedMutexException]{$held=$true;throw 'Ledger mutex abandoned; recovery required'}
        if(!$held){throw 'Ledger lock timeout'}
        $item=Get-Item -LiteralPath $full -Force -ErrorAction Stop
        if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsupported ledger path'}
        $state=Get-Content -Raw -LiteralPath $full|ConvertFrom-Json -AsHashtable -ErrorAction Stop
        if($state.Count -ne 4 -or $state.schemaVersion -isnot [long] -or $state.schemaVersion -ne 1 -or $state.lastSequence -isnot [long] -or $state.lastSequence -lt 0 -or $state.lastSequence -ge [long]::MaxValue -or $state.fingerprint -cnotmatch '^[a-f0-9]{64}$' -or $state.status -cne 'active'){throw 'Invalid or unsupported ledger'}
        $anchor=@{status='active';fingerprint=$state.fingerprint;minimumSequence=([long]$state.lastSequence+1)}
        Test-TrustPolicy $Payload $Signature $PublicKey $anchor $Now|Out-Null
        $parsed=$Payload|ConvertFrom-Json -AsHashtable -DateKind String
        $state.lastSequence=$parsed.sequence
        $temp=Join-Path $item.DirectoryName ('.ledger-'+[guid]::NewGuid().ToString('N')+'.tmp')
        $backup=Join-Path $item.DirectoryName ('.ledger-'+[guid]::NewGuid().ToString('N')+'.bak')
        $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($state|ConvertTo-Json -Compress))
        $stream=[IO.FileStream]::new($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
        [IO.File]::Replace($temp,$full,$backup)
        return 'OFFLINE_LEDGER_ONLY_NOT_INSTALL_AUTHORIZATION'
    } finally {if($held){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
