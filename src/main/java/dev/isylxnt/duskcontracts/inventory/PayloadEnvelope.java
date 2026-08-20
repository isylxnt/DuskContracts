package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.domain.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

public final class PayloadEnvelope {
    private static final int MAGIC = 0x44534349; // DSCI
    private PayloadEnvelope() {}

    public static byte[] encode(int schema, int dataVersion, String algorithm, byte[] payload, int maximum) {
        if (payload.length > maximum) throw new DomainException(DomainException.Kind.VALIDATION, "Serialized item exceeds configured limit");
        byte[] alg = algorithm.getBytes(StandardCharsets.UTF_8);
        byte[] checksum = sha256(payload);
        ByteBuffer out = ByteBuffer.allocate(4 + 4 + 4 + 2 + alg.length + 4 + checksum.length + payload.length);
        out.putInt(MAGIC).putInt(schema).putInt(dataVersion).putShort((short) alg.length).put(alg);
        out.putInt(payload.length).put(checksum).put(payload);
        return out.array();
    }
    public static Decoded decode(byte[] envelope, int maximum) {
        try {
            ByteBuffer in = ByteBuffer.wrap(envelope);
            if (in.getInt() != MAGIC) throw invalid("Invalid item payload magic");
            int schema = in.getInt(); int dataVersion = in.getInt(); int algLength = Short.toUnsignedInt(in.getShort());
            if (algLength < 1 || algLength > 128 || in.remaining() < algLength + 36) throw invalid("Invalid item payload header");
            byte[] alg = new byte[algLength]; in.get(alg); int length = in.getInt();
            if (length < 0 || length > maximum || in.remaining() != 32 + length) throw invalid("Invalid item payload size");
            byte[] expected = new byte[32]; in.get(expected); byte[] payload = new byte[length]; in.get(payload);
            if (!MessageDigest.isEqual(expected, sha256(payload))) throw invalid("Item payload checksum mismatch");
            return new Decoded(schema, dataVersion, new String(alg, StandardCharsets.UTF_8), payload, HexFormat.of().formatHex(expected));
        } catch (java.nio.BufferUnderflowException ex) { throw invalid("Truncated item payload"); }
    }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); } }
    private static DomainException invalid(String text) { return new DomainException(DomainException.Kind.PERMANENT, text); }
    public record Decoded(int schemaVersion, int serverDataVersion, String algorithm, byte[] payload, String checksum) {
        public Decoded { payload = Arrays.copyOf(payload, payload.length); }
        @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    }
}
