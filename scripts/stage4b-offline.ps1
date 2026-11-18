# Local evidence staging only. This is not an installation authorization boundary.
function Assert-Stage4bNoReparseAncestors([string]$Path) {
    $current=[IO.Path]::GetFullPath($Path)
    while($current) {
        if(Test-Path -LiteralPath $current) {
            $item=Get-Item -LiteralPath $current -Force -ErrorAction Stop
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse ancestor rejected'}
        }
        $parent=[IO.Path]::GetDirectoryName($current)
        if(!$parent -or $parent -ceq $current){break}; $current=$parent
    }
}
function Assert-Stage4bFile([string]$Path,[string]$Root,[string]$Hash) {
    Assert-Stage4bNoReparseAncestors $Path
    if ($Hash -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid digest' }
    $rootFull=[IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $expected=[IO.Path]::Combine($rootFull,($Hash+'.apk'))
    $actual=(Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath
    if (![string]::Equals($actual,$expected,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected staged path' }
    $item=Get-Item -LiteralPath $actual -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or ((Get-Item -LiteralPath $rootFull -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Reparse point rejected' }
    if ((Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Hash) { throw 'Staged digest mismatch' }
}
function Publish-Stage4bFile([string]$InputPath,[string]$Root,[string]$Hash) {
    Assert-Stage4bNoReparseAncestors $InputPath
    Assert-Stage4bNoReparseAncestors $Root
    if ($Hash -cnotmatch '^[a-f0-9]{64}$' -or (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Hash) { throw 'Input digest mismatch' }
    [IO.Directory]::CreateDirectory($Root) | Out-Null
    if ((Get-Item -LiteralPath $Root -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse root rejected' }
    $output=[IO.Path]::Combine([IO.Path]::GetFullPath($Root),($Hash+'.apk'))
    if ([IO.File]::Exists($output)) { Assert-Stage4bFile $output $Root $Hash; return $output }
    $temp=[IO.Path]::Combine([IO.Path]::GetFullPath($Root),('.stage-'+[guid]::NewGuid().ToString('N')+'.tmp'))
    try {
        [IO.File]::Copy((Resolve-Path -LiteralPath $InputPath).ProviderPath,$temp,$false)
        if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Hash) { throw 'Copy digest mismatch' }
        [IO.File]::Move($temp,$output)
        Assert-Stage4bFile $output $Root $Hash
        return $output
    } finally { if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) } }
}
function Assert-Stage4bSourceText([string]$Text) {
    if ($Text -match '(?i)/data/(?:user/\d+|data)/com\.huanhren\.deltaharmonica(?:/|["''])' -or $Text -match 'createPackageContext\s*\(\s*["'']com\.huanhren\.deltaharmonica["'']') { throw 'Protected reference' }
    if ($Text -match '(?i)File\s*\(\s*["''](?:/|[a-z]:[\\/])[^"'']*["'']\s*\)\s*\.deleteRecursively\s*\(') { throw 'Absolute recursive deletion requires manual review' }
}
function Assert-Stage4bAuthority([string]$Authority) {
    if ($Authority -cne 'com.huanhren.deltaharmonica.instrumented.importprovider') { throw 'Unexpected authority' }
}
# Comparison of synthetic evidence only; not proof that a real build or signer is trustworthy.
function Assert-Stage4bFixtureRecord($Expected,$Actual) {
    foreach($key in @('buildId','role','apkSha256','manifestSha256','certificateSha256','applicationId','targetPackage','runner','stagePath')) {
        if ([string]::IsNullOrWhiteSpace([string]$Expected[$key]) -or $Actual[$key] -cne $Expected[$key]) { throw "Fixture mismatch: $key" }
    }
}
