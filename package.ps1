$ErrorActionPreference = "Stop"
$base = "C:\Users\rosMa\Desktop\AllPurpose"
$classes = "$base\build_test"
$build = "$base\build200"
$jar = "$build\AllPurpose-2.1.0.jar"

# Guard: never ship a jar without a descriptor. This exact mistake broke the
# plugin once already (plugin.yml got deleted, jar shipped with no descriptor).
foreach ($req in @("$base\plugin.yml", "$base\config.yml")) {
    if (-not (Test-Path $req)) { throw "FATAL: required file missing: $req" }
}

# Guard: class files must target Java 21 (server Java 25 runs v21 bytecode fine).
$probe = "$classes\com\hush\allpurpose\AllPurpose.class"
if (-not (Test-Path $probe)) { throw "FATAL: no compiled classes at $probe - run compile.ps1 first" }
$b = [System.IO.File]::ReadAllBytes($probe)
$ver = $b[7] - 44
Write-Output "class file version: $ver"
if ($ver -ne 21) { throw "FATAL: class file version is $ver, must be 21. Add --release 21 to compile.ps1" }

# Guard: never ship a partial compile (a previous javac run still writing
# build_test while packaging produced a jar missing classes -> plugin
# disables itself on boot with NoClassDefFoundError). Every source must have
# its top-level class present.
$srcCount = (Get-ChildItem "$base\src" -Recurse -Filter *.java).Count
$clsCount = (Get-ChildItem "$classes\com" -Recurse -Filter *.class | Where-Object { $_.Name -notmatch '\$' }).Count
Write-Output "classes: $clsCount top-level for $srcCount sources"
if ($clsCount -lt $srcCount) { throw "FATAL: partial compile ($clsCount < $srcCount) - re-run compile.ps1 and wait for EXITCODE=0" }

# resources ship inside the jar (saveDefaultConfig + plugin.yml + word list fallback)
Copy-Item "$base\plugin.yml" "$classes\plugin.yml" -Force
# Stamp every build so /version tells jars apart (source plugin.yml stays clean).
# Build counter: guarantees a UNIQUE version on EVERY package run.
# Format: <base>-b<num> (e.g. 2.1.0-b3). No timestamp. Base is read from the
# source plugin.yml so bumping it is a one-line edit there.
# Counter lives in .buildnum (never inside build200/, which gets wiped each run).
$numFile = "$base\.buildnum"
$num = 0
if (Test-Path $numFile) {
    $raw = (Get-Content $numFile -Raw).Trim()
    [int]::TryParse($raw, [ref]$num) | Out-Null
}
$num++
Set-Content $numFile $num -NoNewline
$baseVer = "2.1.0"
try {
    $src = Get-Content "$base\plugin.yml" -Raw
    $m = [regex]::Match($src, '(?m)^version:\s*(.+?)\s*$')
    if ($m.Success -and $m.Groups[1].Value.Trim() -ne "") { $baseVer = $m.Groups[1].Value.Trim() }
} catch { }
$jarVer = "$baseVer-b$num"
$desc = Get-Content "$classes\plugin.yml" -Raw
$desc = [regex]::Replace($desc, '(?m)^version:.*$', "version: $jarVer")
Set-Content "$classes\plugin.yml" $desc -NoNewline
Write-Output "jar version: $jarVer"
Copy-Item "$base\config.yml" "$classes\config.yml" -Force
if (Test-Path "$base\changelog.txt") { Copy-Item "$base\changelog.txt" "$classes\changelog.txt" -Force }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build | Out-Null

& jar --create --file $jar -C $classes . 2>&1 | Out-String -Width 300 | Write-Output

# Verify the descriptor actually made it in
$entries = & jar --list --file $jar 2>&1
if ($entries -notcontains "plugin.yml") { throw "FATAL: plugin.yml missing from $jar" }
Write-Output "OK: plugin.yml present in jar"
# Guard: jar must contain EVERY compiled class including inner ($-classes).
# Top-level-only checks once shipped a jar missing LeaderboardManager$2 etc.,
# which booted then threw NoClassDefFoundError on first lazy load. Any edit
# followed by package-without-compile produces exactly that: refuse it here.
$allCls = (Get-ChildItem "$classes" -Recurse -Filter *.class).Count
$jarCls = ($entries | Where-Object { $_ -like "*.class" }).Count
Write-Output "classes in jar: $jarCls of $allCls compiled (incl. inner)"
if ($jarCls -lt $allCls) { throw "FATAL: jar missing classes ($jarCls < $allCls) - re-run compile.ps1 first, then package again" }
# Spot-check: every $-inner class file must be present by exact name.
$missing = @()
foreach ($f in (Get-ChildItem "$classes" -Recurse -Filter *.class)) {
    $rel = $f.FullName.Substring($classes.Length + 1).Replace("\", "/")
    if ($entries -notcontains $rel) { $missing += $rel }
}
if ($missing.Count -gt 0) { throw ("FATAL: jar missing inner classes: " + ($missing -join ", ")) }
Write-Output "OK: all inner classes present in jar"
Write-Output ("jar size: " + (Get-Item $jar).Length)
