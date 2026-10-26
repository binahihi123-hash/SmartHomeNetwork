package com.smarthome.common;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe message reader/writer for socket communication.
 * Uses JSON Lines format (one JSON object per line, terminated by \n).
 * BufferedReader.readLine() handles TCP fragmentation and coalescing correctly.
 */
public class MessageIO implements AutoCloseable {
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();
    private volatile boolean closed = false;

    public MessageIO(Socket socket) throws IOException {
        this.reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.writer = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), "UTF-8")),
                false); // manual flush for atomicity
    }

    /**
     * Send a message as a single JSON line. Thread-safe.
     */
    public void send(Message message) throws IOException {
        if (closed) throw new IOException("MessageIO is closed");
        writeLock.lock();
        try {
            String json = message.toJson();
            writer.println(json);
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Write error");
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read the next message. Blocks until a complete line is available.
     * Returns null if the connection is closed.
     * Thread-safe.
     */
    public Message receive() throws IOException {
        if (closed) return null;
        readLock.lock();
        try {
            String line = reader.readLine();
            if (line == null) {
                return null; // connection closed
            }
            line = line.trim();
            if (line.isEmpty()) {
                return null;
            }
            return Message.fromJson(line);
        } catch (SocketTimeoutException e) {
            throw e; // let caller handle timeouts
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("Invalid JSON message: " + e.getMessage(), e);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        try { reader.close(); } catch (IOException ignored) {}
        writer.close();
    }

    public boolean isClosed() {
        return closed;
    }
}
