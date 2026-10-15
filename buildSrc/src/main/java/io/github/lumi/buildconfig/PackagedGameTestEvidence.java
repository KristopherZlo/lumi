package io.github.lumi.buildconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Refuses empty, failed, skipped, or artifact-changing production runs. */
public final class PackagedGameTestEvidence {
    public void verifyClient(Path completion, Path artifact, String expectedHash, String suite) throws IOException {
        if (!hash(artifact).equals(expectedHash)) throw new IOException("Tested Lumi artifact changed");
        if (Files.size(completion) > 256 || !Files.readString(completion).equals(suite + "\n" + expectedHash)) {
            throw new IOException("Packaged client did not finish the selected suite with the expected artifact");
        }
    }

    public int verify(Path report, Path artifact, String expectedHash) throws IOException {
        if (!hash(artifact).equals(expectedHash)) throw new IOException("Tested Lumi artifact changed");
        if (Files.size(report) > 8 * 1024 * 1024) throw new IOException("GameTest report is too large");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        int tests = 0;
        try (var input = Files.newInputStream(report)) {
            var xml = factory.createXMLStreamReader(input);
            try {
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue;
                    switch (xml.getLocalName()) {
                        case "testcase" -> tests++;
                        case "failure", "error", "skipped" -> throw new IOException("GameTest did not pass");
                    }
                }
            } finally { xml.close(); }
        } catch (XMLStreamException malformed) {
            throw new IOException("Invalid GameTest report", malformed);
        }
        if (tests == 0) throw new IOException("GameTest report contains no tests");
        return tests;
    }

    public String hash(Path artifact) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
