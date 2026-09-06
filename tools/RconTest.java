import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Minimal RCON 3 client for testing (little-endian). */
public class RconTest {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25575;
        String password = args.length > 2 ? args[2] : "dntest";
        String command = args.length > 3 ? args[3] : "dn info";

        try (Socket sock = new Socket(host, port)) {
            sock.setSoTimeout(8000);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            int reqId = 12345;
            // auth packet: len, id, type=3, payload, \0\0
            byte[] payload = password.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[payload.length + 10];
            writeIntLE(body, 0, reqId);
            writeIntLE(body, 4, 3);
            System.arraycopy(payload, 0, body, 8, payload.length);
            writePacket(out, body);

            Packet resp = readPacket(in);
            System.out.println("AUTH response: id=" + resp.id + " type=" + resp.type + " payload='" + resp.text + "'");
            if (resp.id == -1) {
                System.out.println("AUTH FAILED (server returned -1)");
                return;
            }

            // command packet
            byte[] cmdPayload = command.getBytes(StandardCharsets.UTF_8);
            byte[] cmdBody = new byte[cmdPayload.length + 10];
            writeIntLE(cmdBody, 0, reqId + 1);
            writeIntLE(cmdBody, 4, 2);
            System.arraycopy(cmdPayload, 0, cmdBody, 8, cmdPayload.length);
            writePacket(out, cmdBody);

            resp = readPacket(in);
            System.out.println("CMD response: id=" + resp.id + " type=" + resp.type);
            System.out.println("---- output ----");
            System.out.println(resp.text);
            System.out.println("----------------");
        }
    }

    static void writeIntLE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
        buf[off + 2] = (byte) ((v >> 16) & 0xFF);
        buf[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        // CRITICAL: Forge's RconClient does a single read() and requires the whole
        // packet (length + body) to arrive in one segment. Build one buffer and
        // write it in a single call.
        byte[] packet = new byte[4 + body.length];
        packet[0] = (byte) (body.length & 0xFF);
        packet[1] = (byte) ((body.length >> 8) & 0xFF);
        packet[2] = (byte) ((body.length >> 16) & 0xFF);
        packet[3] = (byte) ((body.length >> 24) & 0xFF);
        System.arraycopy(body, 0, packet, 4, body.length);
        out.write(packet);
        out.flush();
    }

    static Packet readPacket(DataInputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        in.readFully(lenBuf);
        int len = (lenBuf[0] & 0xFF) | ((lenBuf[1] & 0xFF) << 8) | ((lenBuf[2] & 0xFF) << 16) | ((lenBuf[3] & 0xFF) << 24);
        byte[] buf = new byte[len];
        in.readFully(buf);
        int id = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8) | ((buf[2] & 0xFF) << 16) | ((buf[3] & 0xFF) << 24);
        int type = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8) | ((buf[6] & 0xFF) << 16) | ((buf[7] & 0xFF) << 24);
        String text = new String(buf, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
        return new Packet(id, type, text);
    }

    static class Packet {
        final int id, type;
        final String text;

        Packet(int id, int type, String text) {
            this.id = id;
            this.type = type;
            this.text = text;
        }
    }
}
