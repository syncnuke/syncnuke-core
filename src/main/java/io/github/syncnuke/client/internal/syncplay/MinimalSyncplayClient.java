package io.github.syncnuke.client.internal.syncplay;

/*
 * MinimalSyncplayClient.java  (Jackson edition)
 *
 * Compile (Unix/Mac):
 *   javac -cp jackson-core-2.17.1.jar:jackson-databind-2.17.1.jar MinimalSyncplayClient.java
 *
 * Run:
 *   java -cp .:jackson-core-2.17.1.jar:jackson-databind-2.17.1.jar \
 *        MinimalSyncplayClient <host> <port> <user> <room> [<plainPwd>]
 *
 * Required JARs
 *   ├─ jackson-core-2.x.y.jar
 *   └─ jackson-databind-2.x.y.jar   (brings jackson-annotations as a transitive dep)
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MinimalSyncplayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java MinimalSyncplayClient <host> <port> <username> <room> [<plainPwd>]");
            return;
        }
        String host     = args[0];
        int    port     = Integer.parseInt(args[1]);
        String username = args[2];
        String room     = args[3];
        String password = args.length >= 5 ? md5Hex(args[4]) : "";

        Socket sock = new Socket(host, port);
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in  = new BufferedReader(
                new InputStreamReader(sock.getInputStream(),  StandardCharsets.UTF_8));

        /* -------- send Hello -------- */
        ObjectNode hello = MAPPER.createObjectNode();
        hello.put("username", username);
        ObjectNode roomObj = MAPPER.createObjectNode();
        roomObj.put("name", room);
        hello.set("room", roomObj);
        if (!password.isEmpty()) hello.put("password", password);
        hello.put("version",     "1.2.255");
        hello.put("realversion", "1.7.0");

        ObjectNode feats = MAPPER.createObjectNode();
        feats.put("featureList",  true);
        feats.put("readiness",    true);
        feats.put("managedRooms", false);
        feats.put("chat",         true);
        hello.set("features", feats);

        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("Hello", hello);
        sendJson(wrapper, out);

        System.out.println("Sent Hello; waiting for server messages…");

        /* -------- keep reading forever -------- */
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("<< " + line);
                }
            } catch (IOException e) {
                System.err.println("Reader stopped: " + e.getMessage());
            }
        }, "SyncplayReader").start();
    }

    private static void sendJson(ObjectNode obj, BufferedWriter out) throws IOException {
        System.out.println(MAPPER.writeValueAsString(obj));
        out.write(MAPPER.writeValueAsString(obj));
        out.write("\r\n");
        out.flush();
    }

    /** Plain MD5, because Syncplay still expects it */
    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
