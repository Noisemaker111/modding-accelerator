# Ask the Forge bridge one question over the loopback control socket.
#   .\ask.ps1 root
#   .\ask.ps1 screenshot
#   .\ask.ps1 screenshot title
#   .\ask.ps1 quit
#   .\ask.ps1 -Port 27700 root
$ErrorActionPreference = 'Stop'
$port = 27699
$words = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq '-Port' -and $i + 1 -lt $args.Count) {
        $port = [int]$args[$i + 1]
        $i++
    }
    else {
        $words.Add([string]$args[$i])
    }
}
$line = ($words -join ' ').Trim()
if (-not $line) { $line = 'root' }

# The client may be listening on ::1 or 127.0.0.1 depending on the JVM's family preference.
$client = $null
foreach ($addr in @('127.0.0.1', '::1')) {
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        $probe.Connect($addr, $port)
        $client = $probe
        break
    }
    catch {
        $probe.Dispose()
    }
}
if (-not $client) { throw "nothing listening on $port (127.0.0.1 or ::1)" }
$client.ReceiveTimeout = 90000
try {
    $stream = $client.GetStream()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes("$line`n")
    $stream.Write($bytes, 0, $bytes.Length)
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    $reader.ReadLine()
}
finally {
    $client.Dispose()
}
