package com.kalynx.centralindexer.it.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;

/**
 * Creates and manages a temporary self-signed PKCS12 keystore for use in TLS tests.
 *
 * <p>The keystore is generated programmatically via the JDK {@code keytool} utility into a
 * temp file. The certificate has {@code CN=localhost} so that hostname verification
 * against {@code localhost} succeeds without custom logic in the server.
 *
 * <p>Use in a try-with-resources block; {@link #close()} deletes the temp keystore file.
 *
 * <pre>{@code
 * try (TestKeystore ks = new TestKeystore()) {
 *     SSLContext clientCtx = ks.clientSslContext();
 *     // ... start server with ks.getKeystorePath() / ks.getPassword()
 * }
 * }</pre>
 */
public final class TestKeystore implements AutoCloseable {

    private static final String ALIAS = "test";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private final Path keystoreFile;
    private final String password = "test-keystore-password";

    /**
     * Generates a fresh self-signed PKCS12 keystore in a temp file.
     *
     * @throws Exception if {@code keytool} is unavailable or returns a non-zero exit code
     */
    public TestKeystore() throws Exception {
        keystoreFile = Files.createTempFile("test-keystore", ".p12");
        Files.delete(keystoreFile);
        generateKeystore();
    }

    /**
     * Returns the absolute path to the generated keystore file.
     *
     * @return path string suitable for use in {@code TlsConfig}
     */
    public String getKeystorePath() {
        return keystoreFile.toString();
    }

    /**
     * Returns the keystore password.
     *
     * @return the password used when the keystore was created
     */
    public String getPassword() {
        return password;
    }

    /**
     * Returns the keystore type.
     *
     * @return {@code "PKCS12"}
     */
    public String getKeystoreType() {
        return KEYSTORE_TYPE;
    }

    /**
     * Builds a client {@link SSLContext} that trusts only the self-signed certificate
     * contained in this keystore.
     *
     * <p>The certificate is extracted from the PKCS12 keystore and loaded into an
     * in-memory JKS trust store, ensuring the HTTPS client can verify the test server.
     *
     * @return a configured {@link SSLContext} for use with {@link javax.net.ssl.HttpsURLConnection}
     * @throws Exception if the keystore cannot be read or the SSL context cannot be initialised
     */
    public SSLContext clientSslContext() throws Exception {
        KeyStore serverKs = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream is = Files.newInputStream(keystoreFile)) {
            serverKs.load(is, password.toCharArray());
        }
        Certificate cert = serverKs.getCertificate(ALIAS);

        KeyStore trustKs = KeyStore.getInstance(KeyStore.getDefaultType());
        trustKs.load(null, null);
        trustKs.setCertificateEntry("server", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustKs);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * Deletes the temporary keystore file.
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(keystoreFile);
        } catch (Exception ignored) {
        }
    }

    private void generateKeystore() throws Exception {
        List<String> cmd = List.of(
                "keytool", "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-storetype", KEYSTORE_TYPE,
                "-keystore", keystoreFile.toString(),
                "-storepass", password,
                "-keypass", password,
                "-dname", "CN=localhost,OU=Test,O=Test,L=Test,ST=Test,C=US",
                "-noprompt"
        );
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }
}


