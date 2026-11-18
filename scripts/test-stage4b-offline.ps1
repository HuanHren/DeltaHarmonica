$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'stage4b-offline.ps1')
function ExpectReject($name,[scriptblock]$work) { $rejected=$false; try { & $work | Out-Null } catch { $rejected=$true }; if(!$rejected){throw "Unexpected acceptance: $name"}; "PASS $name" }
$base=[IO.Path]::Combine([IO.Path]::GetTempPath(),'dh-stage4b-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($base) | Out-Null
try {
    $inputPath=Join-Path $base 'fixture.apk'; [IO.File]::WriteAllText($inputPath,'fixture')
    $hash=(Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $stage=Publish-Stage4bFile $inputPath (Join-Path $base 'stage') $hash
    Assert-Stage4bFile $stage (Join-Path $base 'stage') $hash
    ExpectReject 'stage path' { Assert-Stage4bFile $inputPath (Join-Path $base 'stage') $hash }
    [IO.File]::WriteAllText($stage,'replaced')
    ExpectReject 'APK replaced' { Assert-Stage4bFile $stage (Join-Path $base 'stage') $hash }
    ExpectReject 'wrong input hash' { Publish-Stage4bFile $inputPath (Join-Path $base 'stage2') ('0'*64) }
    ExpectReject 'formal data reference' { Assert-Stage4bSourceText '/data/user/0/com.huanhren.deltaharmonica/files' }
    ExpectReject 'formal package context' { Assert-Stage4bSourceText 'createPackageContext("com.huanhren.deltaharmonica", 0)' }
    ExpectReject 'unsafe deletion' { Assert-Stage4bSourceText 'File("/data/user/0/com.huanhren.deltaharmonica").deleteRecursively()' }
    ExpectReject 'other absolute deletion' { Assert-Stage4bSourceText 'File("/data/other").deleteRecursively()' }
    ExpectReject 'provider authority' { Assert-Stage4bAuthority 'com.huanhren.deltaharmonica.importprovider' }
    Assert-Stage4bAuthority 'com.huanhren.deltaharmonica.instrumented.importprovider'
    'PASS content-addressed fixture and isolated authority'
    $expected=@{buildId='build-1';role='test';apkSha256='hash';manifestSha256='manifest';certificateSha256='cert';applicationId='test-id';targetPackage='host-id';runner='runner';stagePath='path'}
    Assert-Stage4bFixtureRecord $expected $expected
    foreach($entry in @(@('buildId','old-build'),@('apkSha256','other'),@('manifestSha256','other'),@('certificateSha256','other'),@('stagePath','other'))) {
        $changed=$expected.Clone(); $changed[$entry[0]]=$entry[1]
        ExpectReject ("fixture mismatch "+$entry[0]) { Assert-Stage4bFixtureRecord $expected $changed }
    }
} finally {
    $resolved=(Resolve-Path -LiteralPath $base).ProviderPath
    if ($resolved -cne $base -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-stage4b-')) { throw 'Unsafe fixture cleanup target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
