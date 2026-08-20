package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.domain.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ItemBundleCodec {
    private static final int MAGIC = 0x44534342;
    private ItemBundleCodec() {}
    public static byte[] encode(List<byte[]> items, int maximum) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeInt(1); out.writeInt(items.size());
                for (byte[] item : items) { out.writeInt(item.length); out.write(item); }
            }
            if (bytes.size() > maximum) throw new DomainException(DomainException.Kind.VALIDATION, "Item bundle exceeds configured limit");
            return bytes.toByteArray();
        } catch (IOException ex) { throw new AssertionError(ex); }
    }
    public static List<byte[]> decode(byte[] encoded, int maximum) {
        if (encoded.length > maximum) throw new DomainException(DomainException.Kind.PERMANENT, "Item bundle exceeds limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw invalid();
            int count = in.readInt(); if (count < 0 || count > 10000) throw invalid();
            List<byte[]> result = new ArrayList<>(count);
            long total = 0;
            for (int i = 0; i < count; i++) {
                int size = in.readInt();
                if (size < 0 || size > maximum - total) throw invalid();
                total += size;
                result.add(in.readNBytes(size));
                if (result.get(i).length != size) throw invalid();
            }
            if (in.available() != 0) throw invalid();
            return List.copyOf(result);
        } catch (IOException ex) { throw new DomainException(DomainException.Kind.PERMANENT, "Cannot read item bundle", ex); }
    }
    private static DomainException invalid() { return new DomainException(DomainException.Kind.PERMANENT, "Invalid item bundle"); }
}
