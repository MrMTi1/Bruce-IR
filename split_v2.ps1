$inFile = "Flipper-IRDB-main.json"
$outDir = "db_parts"
$size = 20 * 1024 * 1024

if (!(Test-Path $inFile)) { throw "Input file not found" }
if (!(Test-Path $outDir)) { New-Item -ItemType Directory $outDir }

$fs = [System.IO.File]::OpenRead((Resolve-Path $inFile))
$buf = New-Object byte[] $size
$num = 1

while ($true) {
    $read = $fs.Read($buf, 0, $size)
    if ($read -le 0) { break }

    $name = "part_" + $num.ToString("00") + ".bin"
    $path = Join-Path $outDir $name
    $os = [System.IO.File]::Create($path)
    $os.Write($buf, 0, $read)
    $os.Close()

    Write-Host "Part $num created: $name ($read bytes)"
    $num++
}
$fs.Close()
Write-Host "Done! Total parts: $($num - 1)"
