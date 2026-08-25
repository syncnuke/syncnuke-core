package io.github.syncnuke.client.internal.protocol.syncplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import pl.syncplay.proto.SyncplayProto;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MinimalSyncplayClient {

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
        SyncplayProto.RoomInfo roomInfo = SyncplayProto.RoomInfo.newBuilder()
                .setName(room)
                .build();

        SyncplayProto.Features features = SyncplayProto.Features.newBuilder()
                .setFeatureList(true)
                .setReadiness(true)
                .setManagedRooms(false)
                .setChat(true)
                .build();

        SyncplayProto.HelloMessage helloMessage = SyncplayProto.HelloMessage.newBuilder()
                .setUsername(username)
                .setRoom(roomInfo)
                .setVersion("1.2.255")
                .setRealversion("1.7.0")
                .setFeatures(features)
                .setPassword(password)
                .build();

        SyncplayProto.SyncplayMessage message = SyncplayProto.SyncplayMessage.newBuilder()
                .setHello(helloMessage)
                .build();

        sendProtoAsJson(message, out);

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

    private static void sendProtoAsJson(SyncplayProto.SyncplayMessage msg, BufferedWriter out) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode wrapper = mapper.createObjectNode();

        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();

        // Reflectively get which `oneof` is set
        SyncplayProto.SyncplayMessage.BodyCase bodyCase = msg.getBodyCase();
        if (bodyCase == SyncplayProto.SyncplayMessage.BodyCase.BODY_NOT_SET) {
            throw new IllegalArgumentException("SyncplayMessage has no set body");
        }

        // Get the method name and actual inner message
        String fieldName = bodyCase.name(); // e.g., "HELLO", "SET", ...
        String jsonKey = capitalizeFirst(fieldName.toLowerCase()); // → "Hello", "Set", etc.

        // Use reflection to get the actual message from msg (e.g., getHello(), getSet(), etc.)
        String getterName = "get" + jsonKey;
        try {
            com.google.protobuf.Message innerMsg =
                    (com.google.protobuf.Message) SyncplayProto.SyncplayMessage.class
                            .getMethod(getterName)
                            .invoke(msg);

            wrapper.set(jsonKey, mapper.readTree(printer.print(innerMsg)));
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to extract inner message via reflection", e);
        }

        String finalJson = mapper.writeValueAsString(wrapper);
        System.out.println(finalJson);
        out.write(finalJson);
        out.write("\r\n");
        out.flush();
    }

    private static String capitalizeFirst(String input) {
        return input.isEmpty() ? input : input.substring(0, 1).toUpperCase() + input.substring(1);
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
