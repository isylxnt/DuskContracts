package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.domain.DomainException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class PayloadEnvelopeTest {
    @Test void roundTripsVersionAlgorithmSizeAndChecksum(){byte[] payload="metadata+pdc".getBytes(StandardCharsets.UTF_8);byte[] encoded=PayloadEnvelope.encode(1,3465,"BUKKIT_BYTES_V1",payload,1024);var decoded=PayloadEnvelope.decode(encoded,1024);assertThat(decoded.schemaVersion()).isEqualTo(1);assertThat(decoded.serverDataVersion()).isEqualTo(3465);assertThat(decoded.algorithm()).isEqualTo("BUKKIT_BYTES_V1");assertThat(decoded.payload()).isEqualTo(payload);assertThat(decoded.checksum()).hasSize(64);}
    @Test void detectsTampering(){byte[] encoded=PayloadEnvelope.encode(1,1,"x",new byte[]{1,2,3},100);encoded[encoded.length-1]^=1;assertThatThrownBy(()->PayloadEnvelope.decode(encoded,100)).isInstanceOf(DomainException.class).hasMessageContaining("checksum");}
    @Test void bundleRoundTrips(){byte[] bundle=ItemBundleCodec.encode(java.util.List.of(new byte[]{1},new byte[]{2,3}),100);assertThat(ItemBundleCodec.decode(bundle,100)).containsExactly(new byte[]{1},new byte[]{2,3});}
    @Test void bundleRejectsTruncationTrailingDataAndOversize(){
        byte[] bundle=ItemBundleCodec.encode(java.util.List.of(new byte[]{1,2,3}),100);
        assertThatThrownBy(()->ItemBundleCodec.decode(java.util.Arrays.copyOf(bundle,bundle.length-1),100)).isInstanceOf(DomainException.class);
        byte[] trailing=java.util.Arrays.copyOf(bundle,bundle.length+1);
        assertThatThrownBy(()->ItemBundleCodec.decode(trailing,100)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->ItemBundleCodec.decode(bundle,4)).isInstanceOf(DomainException.class).hasMessageContaining("limit");
    }
}
