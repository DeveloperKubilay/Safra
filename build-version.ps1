param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('1.21', '1.21.1', '1.21.2', '1.21.3', '1.21.4', '1.21.5', '1.21.6', '1.21.8', '1.21.9', '1.21.10', '1.21.11')]
    [string]$McVersion,

    [string]$ModVersion
)

$loaders = @('fabric', 'neoforge')
if ($McVersion -ne '1.21.2') {
    $loaders += 'forge'
}

$gradleArgs = @()
foreach ($loader in $loaders) {
    $gradleArgs += (':' + $loader + ':build')
}

$gradleArgs += "-PmcVersion=$McVersion"
if ($ModVersion) {
    $gradleArgs += "-Pmod_version=$ModVersion"
}
$gradleArgs += '--no-daemon'
$gradleArgs += '--console=plain'

& "$PSScriptRoot\\gradlew.bat" @gradleArgs
exit $LASTEXITCODE
