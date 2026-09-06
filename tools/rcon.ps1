# RCON client (Minecraft RCON protocol 3, little endian).
# Usage: & tools/rcon.ps1 -Port 25575 -Password dfstest -Command "dfs info"
param(
    [string]$Server = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = '',
    [string]$Command = '',
    [int]$TimeoutMs = 5000
)

$ErrorActionPreference = 'Stop'

function Send-Packet([System.IO.Stream]$s, [int]$id, [int]$type, [string]$payload) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $body = New-Object byte[] ($bodyBytes.Length + 10)
    $body[0] = $id -band 0xFF
    $body[1] = ($id -shr 8) -band 0xFF
    $body[2] = ($id -shr 16) -band 0xFF
    $body[3] = ($id -shr 24) -band 0xFF
    $body[4] = $type -band 0xFF
    $body[5] = ($type -shr 8) -band 0xFF
    $body[6] = ($type -shr 16) -band 0xFF
    $body[7] = ($type -shr 24) -band 0xFF
    [Array]::Copy($bodyBytes, 0, $body, 8, $bodyBytes.Length)
    # CRITICAL: Forge's RconClient does a single read() and requires the whole
    # packet (length + body) to arrive in one segment. Build one buffer and
    # write it in a single call.
    $packet = New-Object byte[] ($body.Length + 4)
    $packet[0] = $body.Length -band 0xFF
    $packet[1] = ($body.Length -shr 8) -band 0xFF
    $packet[2] = ($body.Length -shr 16) -band 0xFF
    $packet[3] = ($body.Length -shr 24) -band 0xFF
    [Array]::Copy($body, 0, $packet, 4, $body.Length)
    $s.Write($packet, 0, $packet.Length)
    $s.Flush()
}

function Read-Packet([System.IO.Stream]$s) {
    $lenBuf = New-Object byte[] 4
    $read = $s.Read($lenBuf, 0, 4)
    if ($read -lt 4) { throw "connection closed" }
    $len = [BitConverter]::ToInt32($lenBuf, 0)
    $buf = New-Object byte[] $len
    $off = 0
    while ($off -lt $len) {
        $r = $s.Read($buf, $off, $len - $off)
        if ($r -le 0) { throw "connection closed" }
        $off += $r
    }
    $id = [BitConverter]::ToInt32($buf, 0)
    $type = [BitConverter]::ToInt32($buf, 4)
    $text = [System.Text.Encoding]::UTF8.GetString($buf, 8, $len - 10)
    return @{ id = $id; type = $type; text = $text }
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect($Server, $Port)
    $client.ReceiveTimeout = $TimeoutMs
    $client.SendTimeout = $TimeoutMs
    $stream = $client.GetStream()
    $reqId = [Random]::new().Next(1, 100000)

    Send-Packet $stream $reqId 3 $Password
    $resp = Read-Packet $stream
    if ($resp.id -ne $reqId) { throw "RCON auth response id mismatch" }

    $cmdId = $reqId + 1
    Send-Packet $stream $cmdId 2 $Command
    $resp = Read-Packet $stream
    Write-Output $resp.text
}
finally {
    $client.Dispose()
}
