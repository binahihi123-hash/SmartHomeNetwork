package com.smarthome.security;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;

/**
 * TLS configuration for secure TCP connections.
 * Uses Java's built-in SSLSocket/SSLServerSocket with keystore/truststore.
 */
public class TLSConfig {
    private final String keystorePath;
    private final String keystorePassword;
    private final String truststorePath;
    private final String truststorePassword;
    private final boolean tlsEnabled;

    public TLSConfig(String keystorePath, String keystorePassword,
                     String truststorePath, String truststorePassword,
                     boolean tlsEnabled) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.tlsEnabled = tlsEnabled;
    }

    /**
     * Load TLS config from system properties or environment variables.
     */
    public static TLSConfig fromEnvironment() {
        String enabled = System.getProperty("smarthome.tls.enabled",
                System.getenv().getOrDefault("SMARTHOME_TLS_ENABLED", "false"));
        String ksPath = System.getProperty("smarthome.keystore.path",
                System.getenv().getOrDefault("SMARTHOME_KEYSTORE_PATH", "keystore.jks"));
        String ksPass = System.getProperty("smarthome.keystore.password",
                System.getenv().getOrDefault("SMARTHOME_KEYSTORE_PASSWORD", ""));
        String tsPath = System.getProperty("smarthome.truststore.path",
                System.getenv().getOrDefault("SMARTHOME_TRUSTSTORE_PATH", "truststore.jks"));
        String tsPass = System.getProperty("smarthome.truststore.password",
                System.getenv().getOrDefault("SMARTHOME_TRUSTSTORE_PASSWORD", ""));

        return new TLSConfig(ksPath, ksPass, tsPath, tsPass, Boolean.parseBoolean(enabled));
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    /**
     * Create an SSL server socket.
     */
    public ServerSocket createServerSocket(int port) throws Exception {
        if (!tlsEnabled) {
            return new ServerSocket(port);
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keyStore.load(fis, keystorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (truststorePath != null && !truststorePath.isEmpty()) {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(truststorePath)) {
                trustStore.load(fis, truststorePassword.toCharArray());
            }
            tmf.init(trustStore);
        } else {
            tmf.init(keyStore);
        }

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        serverSocket.setNeedClientAuth(false);
        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});

        System.out.println("[TLS] Server socket created with TLSv1.3 on port " + port);
        return serverSocket;
    }

    /**
     * Create an SSL client socket.
     */
    public Socket createClientSocket(String host, int port) throws Exception {
        if (!tlsEnabled) {
            return new Socket(host, port);
        }

        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(truststorePath)) {
            trustStore.load(fis, truststorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

        SSLSocketFactory factory = sslContext.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        socket.startHandshake();

        System.out.println("[TLS] Connected to " + host + ":" + port + " with " + socket.getSession().getProtocol());
        return socket;
    }
}
