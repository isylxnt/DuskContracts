package dev.isylxnt.duskcontracts.config;

import dev.isylxnt.duskcontracts.domain.DomainException;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import java.io.File;
import java.util.*;

public final class ItemRules {
    public enum Context { REQUESTED, DELIVERED, REWARD }
    private final Set<Material> blocked; private final Set<Material> allowed; private final boolean blockContainers;
    private final int maximumDepth; private final int maximumBytes; private final Set<String> blockedNamespaces;
    private final EnumMap<Context,Policy> policies=new EnumMap<>(Context.class);
    public ItemRules(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        blocked = materials(y.getStringList("blocked-materials")); allowed = materials(y.getStringList("allowed-materials"));
        blockContainers = y.getBoolean("containers.blocked"); maximumDepth = Math.max(0, y.getInt("containers.maximum-depth", 4));
        maximumBytes = Math.max(1,y.getInt("maximum-serialized-bytes",1048576));
        blockedNamespaces = Set.copyOf(y.getStringList("blocked-pdc-namespaces"));
        for(Context context:Context.values()){
            String path="contexts."+context.name().toLowerCase(Locale.ROOT)+".";
            policies.put(context,new Policy(materials(y.getStringList(path+"blocked-materials")),materials(y.getStringList(path+"allowed-materials")),
                    y.getBoolean(path+"allow-containers",true),Math.max(1,y.getInt(path+"maximum-serialized-bytes",maximumBytes)),Set.copyOf(y.getStringList(path+"blocked-pdc-namespaces"))));
        }
    }
    public void validate(ItemStack item, Context context) {
        Policy policy=policies.get(context);
        if (item == null || item.getType().isAir() || blocked.contains(item.getType()) || policy.blocked().contains(item.getType())
                || (!allowed.isEmpty() && !allowed.contains(item.getType())) || (!policy.allowed().isEmpty()&&!policy.allowed().contains(item.getType())))
            throw new DomainException(DomainException.Kind.VALIDATION, "This item type is not allowed");
        if(item.serializeAsBytes().length>Math.min(maximumBytes,policy.maximumBytes()))throw new DomainException(DomainException.Kind.VALIDATION,"This item's serialized data is too large");
        validatePdc(item.getItemMeta().getPersistentDataContainer(),policy); validateContainer(item, 0,policy);
    }
    private void validateContainer(ItemStack item, int depth,Policy policy) {
        if (!(item.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof Container container)) return;
        if (blockContainers||!policy.allowContainers()) throw new DomainException(DomainException.Kind.VALIDATION, "Container items are disabled");
        if (depth >= maximumDepth) throw new DomainException(DomainException.Kind.VALIDATION, "Container nesting is too deep");
        for (ItemStack nested : container.getInventory().getContents()) if (nested != null && !nested.getType().isAir()) {
            validatePdc(nested.getItemMeta().getPersistentDataContainer(),policy); validateContainer(nested, depth + 1,policy);
        }
    }
    private void validatePdc(PersistentDataContainer pdc,Policy policy) {
        for (NamespacedKey key : pdc.getKeys()) if (blockedNamespaces.contains(key.getNamespace())||policy.blockedNamespaces().contains(key.getNamespace()))
            throw new DomainException(DomainException.Kind.VALIDATION, "Items from namespace " + key.getNamespace() + " are blocked");
    }
    private static Set<Material> materials(List<String> values) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String value : values) { Material material = Material.matchMaterial(value); if (material != null) result.add(material); }
        return Set.copyOf(result);
    }
    private record Policy(Set<Material> blocked,Set<Material> allowed,boolean allowContainers,int maximumBytes,Set<String> blockedNamespaces){}
}
