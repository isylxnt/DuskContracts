package dev.isylxnt.duskcontracts.recovery;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;

public final class EmergencyRecoveryStore {
    private final JavaPlugin plugin;
    public EmergencyRecoveryStore(JavaPlugin plugin){this.plugin=plugin;}
    public void write(UUID player, UUID contract, byte[] payload, String reason){
        try{
            var dir=plugin.getDataFolder().toPath().resolve("recovery");Files.createDirectories(dir);
            var file=dir.resolve("item-return-"+Instant.now().toEpochMilli()+"-"+UUID.randomUUID()+".recovery");
            String body="schema-version: 1\nplayer: "+player+"\ncontract: "+contract+"\nreason-base64: "+Base64.getEncoder().encodeToString(reason.getBytes(StandardCharsets.UTF_8))+"\npayload-base64: "+Base64.getEncoder().encodeToString(payload)+"\n";
            Files.writeString(file,body,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            plugin.getLogger().severe("Items were written to emergency recovery file " + file.getFileName() + " because the database return claim failed.");
        }catch(IOException ex){plugin.getLogger().log(Level.SEVERE,"CRITICAL: could not persist emergency item recovery payload for player "+player,ex);}
    }
}
