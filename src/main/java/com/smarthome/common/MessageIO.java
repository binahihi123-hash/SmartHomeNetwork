package com.smarthome.common;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe message reader/writer for socket communication.
 * Uses JSON Lines format (one JSON object per line, terminated by \n).
 * BufferedReader.readLine() handles TCP fragmentation and coalescing correctly.
 * Supports async send and batch send for efficient multi-client broadcast.
 */
public class MessageIO implements AutoCloseable {
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();
    private volatile boolean closed = false;

    // Connection statistics for monitoring (useful for 4G quality tracking)
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong sendErrors = new AtomicLong(0);
    private final long connectedSince = System.currentTimeMillis();

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
                sendErrors.incrementAndGet();
                throw new IOException("Write error");
            }
            messagesSent.incrementAndGet();
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
            messagesReceived.incrementAndGet();
            readLock.unlock();
        }
    }

    /**
     * Send a message without throwing on failure. Thread-safe.
     * Returns true if sent successfully, false on error.
     * Useful for broadcast scenarios where one failed send should not stop others.
     * Critical for 4G/multi-network sync: unstable clients must not block broadcast loop.
     */
    public boolean sendAsync(Message message) {
        if (closed) return false;
        writeLock.lock();
        try {
            String json = message.toJson();
            writer.println(json);
            writer.flush();
            if (writer.checkError()) {
                sendErrors.incrementAndGet();
                return false;
            }
            messagesSent.incrementAndGet();
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Send multiple messages in a single lock acquisition for efficiency.
     * All messages are written and then flushed once — reduces TCP round-trips.
     * Ideal for sending device list + history + state in one burst after reconnect.
     * Returns the count of messages successfully queued before the flush.
     */
    public int sendBatch(List<Message> messages) throws IOException {
        if (closed) throw new IOException("MessageIO is closed");
        if (messages == null || messages.isEmpty()) return 0;
        writeLock.lock();
        try {
            int count = 0;
            for (Message msg : messages) {
                String json = msg.toJson();
                writer.println(json);
                count++;
            }
            writer.flush();
            if (writer.checkError()) {
                sendErrors.incrementAndGet();
                throw new IOException("Batch write error after " + count + " messages");
            }
            messagesSent.addAndGet(count);
            return count;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read the next message with a custom timeout override.
     * Temporarily changes socket timeout for this single read, then restores it.
     * Useful for login flow (short timeout) vs. listener loop (long timeout).
     */
    public Message receiveWithTimeout(Socket socket, int timeoutMs) throws IOException {
        if (closed) return null;
        int originalTimeout = socket.getSoTimeout();
        readLock.lock();
        try {
            socket.setSoTimeout(timeoutMs);
            String line = reader.readLine();
            if (line == null) return null;
            line = line.trim();
            if (line.isEmpty()) return null;
            messagesReceived.incrementAndGet();
            return Message.fromJson(line);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("Invalid JSON message: " + e.getMessage(), e);
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
            readLock.unlock();
        }
    }

    // === Connection statistics (real data, not mock) ===

    /** Total messages sent over this connection */
    public long getMessagesSent() { return messagesSent.get(); }

    /** Total messages received over this connection */
    public long getMessagesReceived() { return messagesReceived.get(); }

    /** Total send errors (indicates connection instability, e.g. 4G dropouts) */
    public long getSendErrors() { return sendErrors.get(); }

    /** Connection uptime in milliseconds */
    public long getUptimeMs() { return System.currentTimeMillis() - connectedSince; }

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
