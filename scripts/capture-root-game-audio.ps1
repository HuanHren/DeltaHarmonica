[CmdletBinding(DefaultParameterSetName = 'File')]
param(
    [Parameter(Mandatory, ParameterSetName = 'File')][string]$Recording,
    [Parameter(Mandatory, ParameterSetName = 'Microphone')][string]$Microphone,
    [Parameter(Mandatory, ParameterSetName = 'Microphone')][ValidateRange(1, 1800)][int]$Seconds,
    [Parameter(Mandatory)][string]$OutputWav,
    [string]$Ffmpeg = 'ffmpeg'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([IO.Path]::GetExtension($OutputWav) -ine '.wav') { throw 'OutputWav must end in .wav.' }
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputWav)
if (Test-Path -LiteralPath $outputPath) { throw 'The output WAV already exists.' }

$ffmpegExe = (Get-Command -Name $Ffmpeg -CommandType Application -ErrorAction Stop).Source
if ($PSCmdlet.ParameterSetName -eq 'File') {
    $inputPath = (Get-Item -LiteralPath $Recording).FullName
    $inputOptions = @('-i', $inputPath)
} else {
    $inputOptions = @('-f', 'dshow', '-i', "audio=$Microphone", '-t', "$Seconds")
}
& $ffmpegExe -hide_banner -n -abort_on empty_output -xerror @inputOptions -map 0:a:0 -vn -sn -dn -c:a pcm_s16le $outputPath
if ($LASTEXITCODE -ne 0) { throw "FFmpeg exited with $LASTEXITCODE. Inspect its output before using the WAV." }
Get-FileHash -LiteralPath $outputPath
