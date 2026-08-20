package dev.isylxnt.duskcontracts.commands;

import dev.isylxnt.duskcontracts.application.ContractApplicationService;
import dev.isylxnt.duskcontracts.application.ContractViewMapper;
import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.api.event.ContractCancelledEvent;
import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.domain.OperationType;
import dev.isylxnt.duskcontracts.domain.ContractStatus;
import dev.isylxnt.duskcontracts.domain.RewardType;
import dev.isylxnt.duskcontracts.inventory.MenuManager;
import dev.isylxnt.duskcontracts.inventory.WizardService;
import dev.isylxnt.duskcontracts.localization.Messages;
import dev.isylxnt.duskcontracts.persistence.*;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public final class ContractsCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;private final BooleanSupplier ready;private final ConfigManager config;private final Messages messages;
    private final MenuManager menus;private final WizardService wizard;private final ContractApplicationService contracts;
    private final Storage storage;private final PlatformScheduler scheduler;private final Map<UUID,Pending> confirmations=new ConcurrentHashMap<>();
    public ContractsCommand(JavaPlugin plugin,BooleanSupplier ready,ConfigManager config,Messages messages,MenuManager menus,
            WizardService wizard,ContractApplicationService contracts,Storage storage,PlatformScheduler scheduler){this.plugin=plugin;this.ready=ready;this.config=config;this.messages=messages;this.menus=menus;this.wizard=wizard;this.contracts=contracts;this.storage=storage;this.scheduler=scheduler;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!ready.getAsBoolean()){messages.send(sender,"admin.initializing");return true;}if(args.length==0)return player(sender,"duskcontracts.use",menus::openMain);
        String sub=args[0].toLowerCase(Locale.ROOT);
        return switch(sub){
            case "help"->{messages.send(sender,"menu.help");yield true;}
            case "browse"->player(sender,"duskcontracts.browse",p->menus.openBrowse(p,0));
            case "search"->{if(args.length<2){messages.send(sender,"menu.help");yield true;}yield player(sender,"duskcontracts.browse",p->menus.openSearch(p,args[1]));}
            case "create"->player(sender,"duskcontracts.create",wizard::start);
            case "mine"->player(sender,"duskcontracts.browse",menus::openMine);
            case "active","contributions"->player(sender,"duskcontracts.browse",menus::openContributions);
            case "claim"->player(sender,"duskcontracts.claim",menus::openClaims);
            case "cancel"->{if(args.length<2){messages.send(sender,"menu.help");yield true;}yield player(sender,"duskcontracts.cancel.own",p->cancelOwn(p,args[1]));}
            case "info"->{if(args.length<2){messages.send(sender,"menu.help");yield true;}yield player(sender,"duskcontracts.browse",p->menus.openInfo(p,args[1]));}
            case "toggle-notifications"->player(sender,"duskcontracts.notifications",this::toggle);
            case "admin"->{admin(sender,Arrays.copyOfRange(args,1,args.length));yield true;}
            default->{messages.send(sender,"menu.help");yield true;}
        };
    }
    private void cancelOwn(Player p,String id){storage.contract(id).whenComplete((found,error)->respond(p,()->{if(error!=null){generic(p);return;}if(found.isEmpty()){messages.send(p,"error.not-found",Map.of("contract_id",id));return;}var original=found.get();contracts.cancel(p,original,"creator request").whenComplete((ok,failed)->respond(p,()->{if(failed!=null)generic(p);else{Bukkit.getPluginManager().callEvent(new ContractCancelledEvent(view(original,ContractStatus.CANCELLED),"creator request",false));messages.send(p,"contract.cancelled",Map.of("contract_id",id));}}));}));}
    private void toggle(Player p){storage.toggleNotifications(p.getUniqueId()).whenComplete((value,error)->respond(p,()->{if(error!=null)generic(p);else messages.send(p,"notifications.changed",Map.of("state",value?"enabled":"disabled"));}));}
    private void admin(CommandSender sender,String[] args){
        if(args.length==0){if(adminPermission(sender,"duskcontracts.admin"))messages.send(sender,"menu.help");return;}String sub=args[0].toLowerCase(Locale.ROOT);
        String required=switch(sub){case "reload","validate"->"duskcontracts.admin.reload";case "inspect","operations"->"duskcontracts.admin.inspect";case "cancel"->"duskcontracts.admin.cancel";case "recover","quarantine"->"duskcontracts.admin.recovery";case "stats","doctor"->"duskcontracts.admin.stats";default->"duskcontracts.admin";};
        if(!adminPermission(sender,required))return;
        switch(sub){
            case "reload","validate"->reload(sender);
            case "inspect"->{if(args.length<2)return;storage.contract(args[1]).whenComplete((found,error)->respond(sender,()->{if(error!=null||found.isEmpty()){messages.send(sender,"error.not-found",Map.of("contract_id",args[1]));return;}var c=found.get();messages.send(sender,"contract.info",Map.of("contract_id",c.shortId(),"material",c.material(),"delivered",c.deliveredAmount(),"total",c.totalAmount(),"status",c.status(),"creator",c.creatorName(),"reward",reward(c.rewardType(),c.rewardMinor())));}));}
            case "cancel"->{if(args.length<3)return;danger(sender,"cancel "+args[1],()->adminCancel(sender,args[1],String.join(" ",Arrays.copyOfRange(args,2,args.length))));}
            case "operations"->{String query=args.length>1?args[1]:"";storage.operations(query,20).whenComplete((rows,error)->respond(sender,()->{if(error!=null){generic(sender);return;}for(OperationRecord op:rows)messages.send(sender,"admin.operation",Map.of("operation_id",op.id(),"type",op.type(),"state",op.state(),"correlation_id",op.correlationId(),"evidence",Objects.toString(op.evidence(),"")));}));}
            case "recover"->{if(args.length<2)return;String resolution=args.length>2?args[2]:"COMPLETE";danger(sender,"recover "+args[1],()->resolve(sender,args[1],resolution,"explicit administrator recovery"));}
            case "quarantine"->{if(args.length<3)return;danger(sender,"quarantine "+args[1],()->resolve(sender,args[1],"QUARANTINE",String.join(" ",Arrays.copyOfRange(args,2,args.length))));}
            case "stats"->stats(sender,false);
            case "doctor"->stats(sender,true);
            case "confirm"->{if(args.length<2)return;confirm(sender,args[1]);}
            default->messages.send(sender,"menu.help");
        }
    }
    private void reload(CommandSender sender){try{config.reload();messages.reload(config.get().locale());wizard.reload();menus.reload();messages.send(sender,"admin.reload-ok");}catch(Exception ex){messages.send(sender,"admin.reload-failed",Map.of("reason",ex.getMessage()));}}
    private void adminCancel(CommandSender sender,String id,String reason){storage.contract(id).thenCompose(found->{if(found.isEmpty())return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("not found"));UUID op=UUID.randomUUID();var c=found.get();return storage.prepareOperation(op,"admin-cancel:"+c.id()+":"+c.version(),OperationType.CANCEL,senderId(sender),c.id(),op.toString().substring(0,8),reason).thenCompose(ignored->storage.cancel(c.id(),senderId(sender),reason,true,op,Instant.now()));}).whenComplete((ok,error)->respond(sender,()->{if(error!=null)generic(sender);else messages.send(sender,"admin.done");}));}
    private void resolve(CommandSender sender,String id,String resolution,String note){try{storage.resolveOperation(UUID.fromString(id),senderId(sender),resolution,note).whenComplete((ok,error)->respond(sender,()->{if(error!=null)generic(sender);else messages.send(sender,"admin.done");}));}catch(IllegalArgumentException ex){generic(sender);}}
    private void stats(CommandSender sender,boolean doctor){storage.stats().whenComplete((value,error)->respond(sender,()->{if(error!=null){generic(sender);return;}if(!doctor){messages.send(sender,"admin.stats",Map.of("open",value.openContracts(),"claims",value.pendingClaims(),"ambiguous",value.ambiguousOperations()));return;}messages.send(sender,"admin.doctor",Map.ofEntries(Map.entry("plugin",plugin.getPluginMeta().getVersion()),Map.entry("server",Bukkit.getVersion()),Map.entry("java",System.getProperty("java.version")),Map.entry("scheduler",scheduler.mode()),Map.entry("storage",config.storage().type()),Map.entry("economy",contracts.economy().providerName()),Map.entry("open",value.openContracts()),Map.entry("claims",value.pendingClaims()),Map.entry("ambiguous",value.ambiguousOperations()),Map.entry("schema",value.schemaVersion()),Map.entry("latency",value.latencyMillis())));}));}
    private void danger(CommandSender sender,String description,Runnable action){UUID id=senderId(sender);String token=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);confirmations.put(id,new Pending(token,System.nanoTime()+60_000_000_000L,description,action));messages.send(sender,"admin.confirm",Map.of("token",token));}
    private void confirm(CommandSender sender,String token){Pending pending=confirmations.remove(senderId(sender));if(pending==null||System.nanoTime()>pending.expiresAt||!pending.token.equalsIgnoreCase(token)){generic(sender);return;}pending.action.run();}
    private boolean player(CommandSender sender,String permission,java.util.function.Consumer<Player> action){if(!permission(sender,permission))return true;if(!(sender instanceof Player p)){messages.send(sender,"error.players-only");return true;}action.accept(p);return true;}
    private boolean permission(CommandSender sender,String node){if(sender.hasPermission(node))return true;messages.send(sender,"error.no-permission");return false;}
    private boolean adminPermission(CommandSender sender,String node){if(sender.hasPermission("duskcontracts.admin")||sender.hasPermission(node))return true;messages.send(sender,"error.no-permission");return false;}
    private void respond(CommandSender sender,Runnable action){if(sender instanceof Player p)scheduler.runEntity(p,action);else scheduler.runGlobal(action);}
    private void generic(CommandSender sender){String id=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);messages.send(sender,"error.generic",Map.of("correlation_id",id));}
    private UUID senderId(CommandSender sender){return sender instanceof Player p?p.getUniqueId():new UUID(0,0);}
    private String reward(RewardType type,long minor){return type==RewardType.ITEM?"ITEM":BigDecimal.valueOf(minor,config.get().decimalPlaces()).toPlainString();}
    private static ContractView view(dev.isylxnt.duskcontracts.domain.Contract c,ContractStatus status){return ContractViewMapper.state(c,status,c.deliveredAmount(),c.version()+1);}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return match(args[0],List.of("help","browse","search","create","mine","active","claim","cancel","info","toggle-notifications","admin"));
        if(args.length==2&&args[0].equalsIgnoreCase("admin")&&sender.hasPermission("duskcontracts.admin"))return match(args[1],List.of("reload","validate","inspect","cancel","operations","recover","quarantine","stats","doctor","confirm"));
        if(args.length==3&&args[0].equalsIgnoreCase("admin")&&args[1].equalsIgnoreCase("recover"))return match(args[2],List.of("COMPLETE","REFUND","QUARANTINE"));
        return List.of();
    }
    private static List<String> match(String prefix,List<String> values){String p=prefix.toLowerCase(Locale.ROOT);return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(p)).toList();}
    private record Pending(String token,long expiresAt,String description,Runnable action){}
}
