$ErrorActionPreference = "Stop"
$base = "C:\Users\rosMa\Desktop\AllPurpose"
$out = "$base\build_test"
$cpJars = Get-ChildItem "$base\lib\*.jar" | ForEach-Object { $_.FullName }
$cp = [string]::Join(";", $cpJars)
$srcs = (Get-ChildItem "$base\src" -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName + '"' }) -join ' '
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
# NOTE: javac must run via cmd /c with cmd-side 2> redirection. Direct `& javac`
# lets javac's stderr (even benign Notes) surface as PS NativeCommandError records,
# which trips $ErrorActionPreference=Stop and fails the build despite exit 0.
$cmd = 'javac -J-Xmx768m -J-Xss4m -nowarn --release 21 -encoding UTF-8 -cp "' + $cp + '" -d "' + $out + '" ' + $srcs + ' 2> "' + $base + '\audit_javac.log"'
cmd /c $cmd
$code = $LASTEXITCODE
if (Test-Path "$base\audit_javac.log") { cmd /c type "$base\audit_javac.log" }
Write-Output "EXITCODE=$code"
if ($code -ne 0) { throw "javac failed with exit code $code (see audit_javac.log)" }
