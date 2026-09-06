param(
    [string]$Server = "127.0.0.1",
    [int]$Port = 25575,
    [string]$Password = "dfstest123",
    [string[]]$Commands = @("help")
)

$ErrorActionPreference = "Stop"

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Server, $Port)
$stream = $client.GetStream()

function Send-Rcon([int]$type, [string]$payload) {
    $body = [System.Text.Encoding]::ASCII.GetBytes($payload)
    $len = 4 + 4 + $body.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int32]$len)
    $bw.Write([int32]1000)
    $bw.Write([int32]$type)
    $bw.Write($body)
    $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Flush()
    $bytes = $ms.ToArray()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()

    # read response: length + requestId + type + payload + nulls
    $lenBuf = New-Object byte[] 4
    $read = $stream.Read($lenBuf, 0, 4)
    if ($read -ne 4) { throw "failed reading length" }
    $respLen = [System.BitConverter]::ToInt32($lenBuf, 0)
    $resp = New-Object byte[] $respLen
    $total = 0
    while ($total -lt $respLen) {
        $n = $stream.Read($resp, $total, $respLen - $total)
        if ($n -le 0) { throw "connection closed" }
        $total += $n
    }
    $reqId = [System.BitConverter]::ToInt32($resp, 0)
    $respType = [System.BitConverter]::ToInt32($resp, 4)
    $text = [System.Text.Encoding]::ASCII.GetString($resp, 8, $respLen - 10)
    return $text
}

try {
    $auth = Send-Rcon 3 $Password
    Write-Output "[AUTH] $auth"
    foreach ($cmd in $Commands) {
        $result = Send-Rcon 2 $cmd
        Write-Output "> $cmd"
        Write-Output "  => $($result.Trim())"
    }
} finally {
    $stream.Close()
    $client.Close()
}
