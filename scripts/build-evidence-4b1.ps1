param([switch]$LibraryOnly)
$ErrorActionPreference='Stop'
function Get-InputManifest([string]$Root,[string[]]$Paths) {
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $records=@(foreach($rel in ($Paths | Sort-Object)) {
        if([string]::IsNullOrWhiteSpace($rel) -or [IO.Path]::IsPathRooted($rel) -or $rel -match '(^|[\\/])(\.{1,2}|)([\\/]|$)' -or $rel -match ':') {throw 'Noncanonical input path'}
        $canonical=$rel.Replace('\','/')
        if(!$seen.Add($canonical)){throw 'Duplicate or case-colliding input path'}
        $full=[IO.Path]::GetFullPath((Join-Path $rootPath $rel))
        if(!$full.StartsWith($rootPath,[StringComparison]::OrdinalIgnoreCase)){throw 'Input escapes root'}
        $ancestor=[IO.Path]::GetDirectoryName($full)
        while($ancestor) {
            $item=Get-Item -LiteralPath $ancestor -Force -ErrorAction Stop
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Input has reparse ancestor'}
            $next=[IO.Path]::GetDirectoryName($ancestor)
            if(!$next -or $next -ceq $ancestor){break}; $ancestor=$next
        }
        $file=Get-Item -LiteralPath $full -Force -ErrorAction Stop
        if($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsupported input'}
        [ordered]@{path=$rel.Replace('\','/');size=$file.Length;type='file';sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    $json=ConvertTo-Json -InputObject $records -Depth 4 -Compress
    $hash=[Security.Cryptography.SHA256]::Create(); try {$digest=([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace('-','').ToLowerInvariant()} finally {$hash.Dispose()}
    return @{files=$records;digest=$digest;canonicalJson=$json}
}
function Get-ProjectInputs([string]$Root) {
    $paths=[System.Collections.Generic.List[string]]::new()
    foreach($required in @('gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties')) {
        if(!(Test-Path -LiteralPath (Join-Path $Root $required) -PathType Leaf)){throw "Missing required wrapper input: $required"}
    }
    foreach($dir in @('app/src','gradle','scripts')) {
        $absolute=Join-Path $Root $dir
        if(!(Test-Path -LiteralPath $absolute -PathType Container)){throw "Missing required input directory: $dir"}
        if((Get-Item -LiteralPath $absolute -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Input root is a reparse point: $dir"}
        Get-ChildItem -LiteralPath $absolute -Recurse -Force -ErrorAction Stop | ForEach-Object {
            if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Input tree contains a reparse point'}
            if(!$_.PSIsContainer){$paths.Add($_.FullName.Substring($Root.TrimEnd('\').Length+1))}
        }
    }
    foreach($name in @('settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle.properties','gradlew','gradlew.bat')) { if(!(Test-Path -LiteralPath (Join-Path $Root $name) -PathType Leaf)){throw "Missing required build input: $name"};$paths.Add($name) }
    if(Test-Path -LiteralPath (Join-Path $Root 'local.properties') -PathType Leaf){$paths.Add('local.properties')}
    return @($paths)
}
if(!$LibraryOnly) {
    $root=(Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath.TrimEnd('\')
    $id='SAFE1B4B1-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[guid]::NewGuid().ToString('N')
    $dir=Join-Path $root ('app/build/outputs/android-test-safety/builds/'+$id)
    [IO.Directory]::CreateDirectory($dir) | Out-Null
    $start=[DateTime]::UtcNow.ToString('o'); $before=Get-InputManifest $root (Get-ProjectInputs $root)
    [IO.File]::WriteAllText((Join-Path $dir 'source-before.json'),$before.canonicalJson,[Text.UTF8Encoding]::new($false))
    Push-Location $root
    try { & .\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest --rerun-tasks --no-build-cache --console=plain *> (Join-Path $dir 'build.log'); $exit=$LASTEXITCODE } finally { Pop-Location }
    $after=Get-InputManifest $root (Get-ProjectInputs $root); $end=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText((Join-Path $dir 'source-after.json'),$after.canonicalJson,[Text.UTF8Encoding]::new($false))
    $state=if($exit -eq 0 -and $before.digest -ceq $after.digest){'OBSERVED_MATCH_NOT_AUTHORIZATION'}else{'INVALID'}
    $result=[ordered]@{schemaVersion=1;buildId=$id;startUtc=$start;endUtc=$end;gradleExit=$exit;inputBefore=$before.digest;inputAfter=$after.digest;status=$state;inputCoverage='workspace app/src, gradle, scripts, selected root files; external SDK/dependencies, environment and unmodeled inputs not verified';command='assembleInstrumented assembleInstrumentedAndroidTest --rerun-tasks --no-build-cache --console=plain';log=(Join-Path $dir 'build.log');authorization='BLOCKED'}
    [IO.File]::WriteAllText((Join-Path $dir 'build-result.json'),($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    $result|ConvertTo-Json -Depth 5
    if($state -eq 'INVALID'){throw 'Build or input consistency failed'}
}
