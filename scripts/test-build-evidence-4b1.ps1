$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'build-evidence-4b1.ps1') -LibraryOnly
$root=[IO.Path]::Combine([IO.Path]::GetTempPath(),('dh-4b1-'+[guid]::NewGuid().ToString('N')))
[IO.Directory]::CreateDirectory($root) | Out-Null
try {
    [IO.File]::WriteAllText((Join-Path $root 'input.txt'),'one')
    $caught=$false; try { Get-ProjectInputs $root | Out-Null } catch {$caught=$true}; if(!$caught){throw 'Missing required input roots were accepted'}
    $before=Get-InputManifest $root @('input.txt')
    $same=Get-InputManifest $root @('input.txt')
    if($before.digest -cne $same.digest){throw 'Unchanged inputs differ'}
    [IO.File]::WriteAllText((Join-Path $root 'input.txt'),'two')
    $after=Get-InputManifest $root @('input.txt')
    if($before.digest -ceq $after.digest){throw 'Modified input was not detected'}
    $caught=$false; try { Get-InputManifest $root @('../outside') | Out-Null } catch {$caught=$true}; if(!$caught){throw 'Traversal was not rejected'}
    $caught=$false; try { Get-InputManifest $root @('actual/../input.txt') | Out-Null } catch {$caught=$true}; if(!$caught){throw 'Internal traversal alias was accepted'}
    $caught=$false; try { Get-InputManifest $root @('input.txt','INPUT.TXT') | Out-Null } catch {$caught=$true}; if(!$caught){throw 'Case-colliding input aliases were accepted'}
    $target=Join-Path $root 'actual';[IO.Directory]::CreateDirectory($target)|Out-Null
    [IO.File]::WriteAllText((Join-Path $target 'input.txt'),'data')
    $link=Join-Path $root 'junction'; New-Item -ItemType Junction -Path $link -Target $target | Out-Null
    try {
        $caught=$false; try { Get-InputManifest $root @('junction/input.txt') | Out-Null } catch {$caught=$true}
        if(!$caught){throw 'Reparse ancestor was not rejected'}
    } finally { [IO.Directory]::Delete($link) }
    foreach($dir in @('app/src','gradle','scripts')){[IO.Directory]::CreateDirectory((Join-Path $root $dir))|Out-Null}
    foreach($file in @('settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle.properties','gradlew','gradlew.bat')){[IO.File]::WriteAllText((Join-Path $root $file),'fixture')}
    $caught=$false;try{Get-ProjectInputs $root|Out-Null}catch{$caught=$true};if(!$caught){throw 'Missing wrapper files were accepted'}
    [IO.Directory]::CreateDirectory((Join-Path $root 'gradle/wrapper'))|Out-Null
    foreach($file in @('gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties')){[IO.File]::WriteAllText((Join-Path $root $file),'fixture')}
    Get-ProjectInputs $root|Out-Null
    $link=Join-Path $root 'app/src/hidden-junction';New-Item -ItemType Junction -Path $link -Target $target|Out-Null
    try {$caught=$false;try{Get-ProjectInputs $root|Out-Null}catch{$caught=$true};if(!$caught){throw 'Input tree junction was silently omitted'}}finally{[IO.Directory]::Delete($link)}
    'PASS manifest rejects reparse ancestor'
    'PASS stable manifest, modified input, path traversal (3 checks; no device access)'
} finally {
    $resolved=(Resolve-Path -LiteralPath $root).ProviderPath
    if ($resolved -cne $root -or ![IO.Path]::GetFileName($resolved).StartsWith('dh-4b1-')) { throw 'Unsafe fixture target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
