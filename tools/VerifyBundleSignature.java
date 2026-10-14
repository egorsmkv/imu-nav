import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Verifies every payload entry, rejecting partial signatures and a different upload identity. */
class VerifyBundleSignature {
    public static void main(String[] args) throws Exception {
        String expected = args[1];
        int payloads = 0;
        try (var jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")
                        || entry.getName().matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                try (var input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) throw new SecurityException("Unsigned or multiple-signer bundle payload");
                var certificate = signers[0].getSignerCertPath().getCertificates().get(0);
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                if (!actual.equals(expected)) throw new SecurityException("Upload certificate mismatch");
                payloads++;
            }
        }
        if (payloads == 0) throw new SecurityException("Empty bundle payload");
        System.out.println("All bundle payloads verified against upload certificate " + expected);
    }
}
