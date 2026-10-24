# Asks the bridge one command and prints its JSON answer. Used as the proof step
# and handy on its own: .\ask.ps1 root / ping / "ui some.Class" / screenshot / quit
param(
	[string]$Command = 'root',
	[int]$Port = 27699
)

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect('localhost', $Port)
$stream = $client.GetStream()
$writer = [System.IO.StreamWriter]::new($stream)
$reader = [System.IO.StreamReader]::new($stream)
$writer.WriteLine($Command)
$writer.Flush()
$reader.ReadLine()
$client.Close()
