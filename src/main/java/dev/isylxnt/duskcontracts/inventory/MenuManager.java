package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.application.*;
import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.api.event.ContractCancelledEvent;
import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.domain.Contract;
import dev.isylxnt.duskcontracts.domain.ContractStatus;
import dev.isylxnt.duskcontracts.domain.DomainException;
import dev.isylxnt.duskcontracts.domain.RewardType;
import dev.isylxnt.duskcontracts.localization.Messages;
import net.kyori.adventure.text.format.TextDecoration;
import dev.isylxnt.duskcontracts.persistence.*;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import dev.isylxnt.duskcontracts.recovery.EmergencyRecoveryStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"deprecation", "removal"})
public final class MenuManager implements Listener {
    private final JavaPlugin plugin; private final Storage storage; private final ContractApplicationService contracts;
    private final ClaimApplicationService claims; private final WizardService wizard; private final Messages messages;
    private final ConfigManager config; private final PlatformScheduler scheduler; private final ItemSerializer serializer;
    private final EmergencyRecoveryStore recovery; private final RateLimiter limiter;
    private static final UUID PUBLIC_VIEWER = new UUID(0, 0);
    private final Map<UUID, Session> sessions=new ConcurrentHashMap<>(); private volatile YamlConfiguration menus;
    public MenuManager(JavaPlugin plugin,Storage storage,ContractApplicationService contracts,ClaimApplicationService claims,
            WizardService wizard,Messages messages,ConfigManager config,PlatformScheduler scheduler,ItemSerializer serializer){
        this.plugin=plugin;this.storage=storage;this.contracts=contracts;this.claims=claims;this.wizard=wizard;this.messages=messages;this.config=config;this.scheduler=scheduler;this.serializer=serializer;
        this.recovery=new EmergencyRecoveryStore(plugin);this.limiter=new RateLimiter(config.get().maxActionsPerSecond());reload();
    }
    public synchronized void reload(){YamlConfiguration candidate=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"menus.yml"));if(candidate.getInt("schema-version",-1)!=1)throw new IllegalArgumentException("menus.yml: schema-version must be the integer 1");for(String path:List.of("hub","book","library","search","mine","contributions","detail","claims","delivery")){int size=candidate.getInt(path+".size",54);if(size<18||size>54||size%9!=0)throw new IllegalArgumentException("menus.yml: "+path+".size must be a multiple of 9 from 18 through 54; received "+size);}menus=candidate;}
    public void shutdownPlayer(Player player){
        Session session=sessions.remove(player.getUniqueId());
        if(session!=null&&session.holder.type()==ContractMenuHolder.Type.DELIVERY)returnDepositOnShutdown(player,session);
        if(player.getOpenInventory().getTopInventory().getHolder() instanceof ContractMenuHolder)player.closeInventory();
    }
    public void finishShutdown(){
        sessions.clear();limiter.clear();
    }
    public void openMain(Player player){
        ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.MAIN);Inventory inv=create(h,menuSize("hub",27),menuString("hub.title","<dark_gray>Dusk Contracts"),Map.of());
        place(inv,"hub.library",10,Map.of());place(inv,"hub.book",13,Map.of());place(inv,"hub.create",16,Map.of());place(inv,"hub.claims",22,Map.of());open(player,new Session(h,inv));
    }
    private void openBook(Player player){ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.BOOK);Inventory inv=create(h,menuSize("book",27),menuString("book.title","<dark_gray>Book"),Map.of());place(inv,"book.contributions",11,Map.of());place(inv,"book.mine",15,Map.of());place(inv,"book.back",18,Map.of());open(player,new Session(h,inv));}
    public void openBrowse(Player player,int page){openBrowse(player,new ContractFilter(dev.isylxnt.duskcontracts.domain.ContractStatus.OPEN,null,null,null,ContractFilter.Sort.NEWEST,PUBLIC_VIEWER,page,menuSize("library",54)-9));}
    private void openBrowse(Player player,ContractFilter filter){
        storage.browse(filter).whenComplete((rows,error)->scheduler.runEntity(player,()->{
            if(error!=null){generic(player,error);return;}ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.BROWSE);Inventory inv=create(h,menuSize("library",54),menuString("library.title","<dark_gray>Library — page {page}"),Map.of("page",filter.page()+1));Session session=new Session(h,inv);session.page=filter.page();session.browseMode=BrowseMode.LIBRARY;
            session.filter=filter;session.hasNext=rows.size()>=filter.pageSize();int slot=0,limit=inv.getSize()-9;for(ContractSummary row:rows){if(slot>=limit)break;inv.setItem(slot,contractIcon(row));session.contracts.put(slot,row);slot++;}place(inv,"library.back",45,Map.of());place(inv,"library.info",49,Map.of("page",filter.page()+1));place(inv,"library.navigation",53,Map.of("page",filter.page()+1));open(player,session);
        }));
    }
    public void openClaims(Player player){
        int size=menuSize("claims",54);storage.claims(player.getUniqueId(),size-9).whenComplete((rows,error)->scheduler.runEntity(player,()->{
            if(error!=null){generic(player,error);return;}if(rows.isEmpty()){messages.send(player,"claim.none");return;}ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.CLAIMS);Inventory inv=create(h,size,menuString("claims.title","<dark_gray>Claims"),Map.of());Session s=new Session(h,inv);int slot=0;
            for(ClaimRecord row:rows){inv.setItem(slot,claimIcon(row));s.claims.put(slot,row);slot++;}place(inv,"claims.back",49,Map.of());open(player,s);
        }));
    }
    public void openContributions(Player player){int size=menuSize("contributions",54);storage.participating(player.getUniqueId(),size-9,Instant.now()).whenComplete((rows,error)->scheduler.runEntity(player,()->{if(error!=null){generic(player,error);return;}if(rows.isEmpty()){messages.send(player,"contract.none");return;}ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.CONTRIBUTIONS);Inventory inv=create(h,size,menuString("contributions.title","<dark_gray>Participating listings"),Map.of());Session s=new Session(h,inv);for(int i=0;i<rows.size();i++){ContractSummary row=rows.get(i);inv.setItem(i,contractIcon(row));s.contributionContracts.put(i,row.id());}place(inv,"contributions.back",49,Map.of());open(player,s);}));}
    public void openInfo(Player player,String id){storage.contract(id).whenComplete((found,error)->scheduler.runEntity(player,()->{if(error!=null){generic(player,error);return;}if(found.isEmpty()||!canView(player,found.get())){messages.send(player,"error.not-found",Map.of("contract_id",id));return;}openDetail(player,found.get(),null,null);}));}
    public void openSearch(Player player,String query){
        Material material=Material.matchMaterial(query);Player creator=Bukkit.getPlayerExact(query);
        if(material==null&&creator==null){openInfo(player,query);return;}
        int size=menuSize("search",54);ContractFilter filter=new ContractFilter(dev.isylxnt.duskcontracts.domain.ContractStatus.OPEN,null,material==null?null:material.name(),creator==null?null:creator.getUniqueId(),ContractFilter.Sort.NEWEST,player.getUniqueId(),0,size-9);
        storage.browse(filter).whenComplete((rows,error)->scheduler.runEntity(player,()->{if(error!=null){generic(player,error);return;}if(rows.isEmpty()){messages.send(player,"contract.none");return;}ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.BROWSE);Inventory inv=create(h,size,menuString("search.title","<dark_gray>Search: {query}"),Map.of("query",query));Session s=new Session(h,inv);s.browseMode=BrowseMode.SEARCH;int slot=0;for(ContractSummary row:rows){inv.setItem(slot,contractIcon(row));s.contracts.put(slot++,row);}place(inv,"search.back",49,Map.of());open(player,s);}));
    }
    private void openDetail(Player p,Contract c,BrowseMode returnMode,ContractFilter returnFilter){
        if(c.assassination()){storage.isParticipating(c.id(),p.getUniqueId()).whenComplete((participating,error)->scheduler.runEntity(p,()->{if(error!=null){generic(p,error);return;}openDetail(p,c,returnMode,returnFilter,participating);}));return;}
        openDetail(p,c,returnMode,returnFilter,false);
    }
    private void openDetail(Player p,Contract c,BrowseMode returnMode,ContractFilter returnFilter,boolean participating){ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.DETAIL);Inventory inv=create(h,menuSize("detail",54),menuString("detail.title","<dark_gray>Contract {contract_id}"),Map.of("contract_id",c.shortId()));Session s=new Session(h,inv);s.contract=c;s.browseMode=returnMode;s.filter=returnFilter;s.participating=participating;inv.setItem(menuSlot("detail.contract",22,inv),detailIcon(c));if(c.assassination())place(inv,participating?"detail.started":"detail.start",31,Map.of());else place(inv,"detail.deliver",31,Map.of());if(c.status()==ContractStatus.OPEN&&c.creatorId().equals(p.getUniqueId())&&p.hasPermission("duskcontracts.cancel.own"))place(inv,"detail.cancel",40,Map.of());place(inv,"detail.back",49,Map.of());open(p,s);}
    private void openDelivery(Player p,Contract c){ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.DELIVERY);Inventory inv=create(h,menuSize("delivery",54),menuString("delivery.title","Delivery"),Map.of("contract_id",c.shortId()));Session s=new Session(h,inv);s.contract=c;s.depositSlots=inv.getSize()-9;for(int i=s.depositSlots;i<inv.getSize();i++)inv.setItem(i,named(Material.GRAY_STAINED_GLASS_PANE," ",List.of(),Map.of()));place(inv,"delivery.cancel",45,Map.of());place(inv,"delivery.confirm",53,Map.of());open(p,s);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!(e.getView().getTopInventory().getHolder() instanceof ContractMenuHolder holder))return;Session s=sessions.get(p.getUniqueId());if(s==null||s.holder!=holder){e.setCancelled(true);return;}if(!limiter.allow(p.getUniqueId())){e.setCancelled(true);messages.send(p,"error.rate-limit");return;}
        if(holder.type()==ContractMenuHolder.Type.DELIVERY&&deliveryDepositAction(p,e,s))return;e.setCancelled(true);if(e.getClickedInventory()!=e.getView().getTopInventory())return;int slot=e.getRawSlot();ItemStack clicked=e.getCurrentItem();if(clicked!=null&&!clicked.getType().isAir())playSound(p,clicked.getType()==Material.BARRIER||clicked.getType()==Material.RED_CONCRETE?"sounds.back":"sounds.select");
        switch(holder.type()){
            case MAIN->mainClick(p,slot);case BOOK->bookClick(p,slot);case BROWSE->browseClick(p,s,slot,e.isRightClick());case DETAIL->detailClick(p,s,slot);case DELIVERY->{if(slot==menuSlot("delivery.cancel",45,s.inventory))p.closeInventory();else if(slot==menuSlot("delivery.confirm",53,s.inventory))confirmDelivery(p,s);}case CLAIMS->{if(slot==menuSlot("claims.back",49,s.inventory))openMain(p);else{ClaimRecord claim=s.claims.get(slot);if(claim!=null)claim(p,s,slot,claim);}}case CONTRIBUTIONS->{if(slot==menuSlot("contributions.back",49,s.inventory))openBook(p);else openContribution(p,s,slot);}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onDrag(InventoryDragEvent e){if(!(e.getWhoClicked() instanceof Player p)||!(e.getView().getTopInventory().getHolder() instanceof ContractMenuHolder h))return;Session s=sessions.get(p.getUniqueId());if(s==null||s.holder!=h){e.setCancelled(true);return;}if(h.type()!=ContractMenuHolder.Type.DELIVERY||s.processing.get()||e.getRawSlots().stream().anyMatch(slot->slot>=s.depositSlots)){e.setCancelled(true);}}
    @EventHandler public void onClose(InventoryCloseEvent e){if(!(e.getPlayer() instanceof Player p)||!(e.getInventory().getHolder() instanceof ContractMenuHolder h))return;Session s=sessions.get(p.getUniqueId());if(s==null||s.holder!=h)return;sessions.remove(p.getUniqueId(),s);if(h.type()==ContractMenuHolder.Type.DELIVERY)returnDeposit(p,s,"deposit menu closed");}
    @EventHandler public void onQuit(PlayerQuitEvent e){Session s=sessions.remove(e.getPlayer().getUniqueId());if(s!=null&&s.holder.type()==ContractMenuHolder.Type.DELIVERY)returnDeposit(e.getPlayer(),s,"player disconnected");limiter.remove(e.getPlayer().getUniqueId());}
    private void mainClick(Player p,int slot){if(slot==menuInt("hub.library.slot",10))openBrowse(p,0);else if(slot==menuInt("hub.book.slot",13))openBook(p);else if(slot==menuInt("hub.create.slot",16))wizard.start(p);else if(slot==menuInt("hub.claims.slot",22))openClaims(p);}
    private void bookClick(Player p,int slot){if(slot==menuInt("book.contributions.slot",11))openContributions(p);else if(slot==menuInt("book.mine.slot",15))openMine(p);else if(slot==menuInt("book.back.slot",18))openMain(p);}
    private void detailClick(Player p,Session s,int slot){
        if(s.contract==null)return;
        if(slot==menuSlot("detail.deliver",31,s.inventory)&&!s.contract.assassination()){openDelivery(p,s.contract);return;}
        if(slot==menuSlot("detail.start",31,s.inventory)&&s.contract.assassination()&&!s.participating){startAssassination(p,s);return;}
        if(slot==menuSlot("detail.back",49,s.inventory)){returnFromDetail(p,s);return;}
        if(slot!=menuSlot("detail.cancel",40,s.inventory)||!s.contract.creatorId().equals(p.getUniqueId())||s.contract.status()!=ContractStatus.OPEN)return;
        if(!s.cancelArmed){s.cancelArmed=true;place(s.inventory,"detail.cancel-confirm",40,Map.of());playSound(p,"sounds.back");return;}
        cancelListing(p,s);
    }
    private void cancelListing(Player p,Session s){
        if(!s.processing.compareAndSet(false,true))return;
        Contract snapshot=s.contract;
        place(s.inventory,"detail.cancelling",40,Map.of());
        contracts.cancel(p,snapshot,"creator menu request").whenComplete((ignored,error)->scheduler.runEntity(p,()->{
            s.processing.set(false);
            if(error!=null){s.cancelArmed=false;place(s.inventory,"detail.cancel",40,Map.of());generic(p,unwrap(error));return;}
            ContractView view=ContractViewMapper.state(snapshot,ContractStatus.CANCELLED,snapshot.deliveredAmount(),snapshot.version()+1);
            Bukkit.getPluginManager().callEvent(new ContractCancelledEvent(view,"creator menu request",false));
            p.closeInventory();playSound(p,"sounds.success");messages.send(p,"contract.cancelled",Map.of("contract_id",snapshot.shortId()));
        }));
    }
    public void openMine(Player p){int size=menuSize("mine",54);storage.browse(new ContractFilter(null,null,null,p.getUniqueId(),ContractFilter.Sort.NEWEST,p.getUniqueId(),0,size-9)).whenComplete((rows,error)->scheduler.runEntity(p,()->{if(error!=null){generic(p,error);return;}if(rows.isEmpty()){messages.send(p,"contract.none");return;}ContractMenuHolder h=new ContractMenuHolder(ContractMenuHolder.Type.BROWSE);Inventory inv=create(h,size,menuString("mine.title","<dark_gray>My listings"),Map.of());Session s=new Session(h,inv);s.browseMode=BrowseMode.MINE;int slot=0;for(ContractSummary row:rows){inv.setItem(slot,contractIcon(row));s.contracts.put(slot++,row);}place(inv,"mine.back",49,Map.of());open(p,s);}));}
    private void browseClick(Player p,Session s,int slot,boolean rightClick){
        if(s.browseMode==BrowseMode.LIBRARY&&slot==menuSlot("library.navigation",53,s.inventory)){if(rightClick&&s.page>0)openBrowse(p,withPage(s.filter,s.page-1));else if(!rightClick&&s.hasNext)openBrowse(p,withPage(s.filter,s.page+1));return;}
        if(s.browseMode==BrowseMode.LIBRARY&&slot==menuSlot("library.back",45,s.inventory)){openMain(p);return;}if(s.browseMode==BrowseMode.MINE&&slot==menuSlot("mine.back",49,s.inventory)){openBook(p);return;}if(s.browseMode==BrowseMode.SEARCH&&slot==menuSlot("search.back",49,s.inventory)){openMain(p);return;}
        ContractSummary row=s.contracts.get(slot);if(row!=null)storage.contract(row.id()).whenComplete((found,error)->scheduler.runEntity(p,()->{if(error!=null){generic(p,error);return;}if(found.isEmpty()||found.get().version()!=row.version()){messages.send(p,"error.changed");refreshBrowse(p,s);return;}openDetail(p,found.get(),s.browseMode,s.filter);}));
    }
    private void openContribution(Player p,Session s,int slot){UUID contractId=s.contributionContracts.get(slot);if(contractId==null)return;storage.contract(contractId).whenComplete((found,error)->scheduler.runEntity(p,()->{if(error!=null){generic(p,error);return;}if(found.isEmpty()){messages.send(p,"error.changed");openContributions(p);return;}openDetail(p,found.get(),BrowseMode.CONTRIBUTIONS,null);}));}
    private void refreshBrowse(Player p,Session s){if(s.browseMode==BrowseMode.LIBRARY&&s.filter!=null)openBrowse(p,s.filter);else if(s.browseMode==BrowseMode.MINE)openMine(p);else openMain(p);}
    private void returnFromDetail(Player p,Session s){if(s.browseMode==BrowseMode.LIBRARY&&s.filter!=null)openBrowse(p,s.filter);else if(s.browseMode==BrowseMode.MINE)openMine(p);else if(s.browseMode==BrowseMode.CONTRIBUTIONS)openContributions(p);else openMain(p);}
    private static ContractFilter withPage(ContractFilter f,int page){return new ContractFilter(f.status(),f.rewardType(),f.material(),f.creatorId(),f.sort(),f.viewerId(),page,f.pageSize());}
    private void startAssassination(Player p,Session s){if(!s.processing.compareAndSet(false,true))return;int slot=menuSlot("detail.start",31,s.inventory);s.inventory.setItem(slot,item("detail.starting",Map.of()));boolean allowOwn=config.get().allowOwnFulfillment()||p.hasPermission("duskcontracts.fulfill.own");storage.joinAssassination(s.contract.id(),p.getUniqueId(),Instant.now(),allowOwn).whenComplete((joined,error)->scheduler.runEntity(p,()->{s.processing.set(false);if(error!=null){Throwable actual=unwrap(error);playSound(p,"sounds.error");s.inventory.setItem(slot,item("detail.start",Map.of()));messages.send(p,"assassination.start-failed",Map.of("reason",Objects.toString(actual.getMessage(),"unknown error")));return;}s.participating=true;s.inventory.setItem(slot,item("detail.started",Map.of()));playSound(p,"sounds.success");messages.send(p,"assassination.started",Map.of("contract_id",s.contract.shortId(),"target",targetName(s.contract.targetId())));}));}
    private void confirmDelivery(Player p,Session s){if(!s.processing.compareAndSet(false,true))return;int confirm=menuSlot("delivery.confirm",53,s.inventory);s.inventory.setItem(confirm,item("delivery.processing",Map.of()));scheduler.runEntityLater(p,Duration.ofMillis(50),()->{List<ItemStack> captured=new ArrayList<>();for(int i=0;i<s.depositSlots;i++){ItemStack value=s.inventory.getItem(i);if(value!=null&&!value.getType().isAir()){captured.add(value.clone());s.inventory.setItem(i,null);}}if(captured.isEmpty()){s.processing.set(false);playSound(p,"sounds.error");messages.send(p,"error.invalid-item");s.inventory.setItem(confirm,item("delivery.confirm",Map.of()));return;}contracts.contribute(p,s.contract,captured,new ResultCallback<>(){public void success(ContributionResult value){sessions.remove(p.getUniqueId(),s);p.closeInventory();playSound(p,"sounds.success");messages.send(p,"contract.delivered",Map.of("contract_id",s.contract.shortId()));}public void failure(Throwable error,String correlation){boolean quarantined=error instanceof DomainException de&&de.kind()==DomainException.Kind.AMBIGUOUS;if(!quarantined)returnItems(p,s.contract.id(),captured,"delivery failed: "+correlation);s.processing.set(false);playSound(p,"sounds.error");messages.send(p,quarantined?"error.recovery":error instanceof DomainException de&&de.kind()==DomainException.Kind.CONFLICT?"error.changed":"error.generic",Map.of("correlation_id",correlation));}});});}
    private void claim(Player p,Session s,int slot,ClaimRecord claim){
        if(!s.processing.compareAndSet(false,true))return;
        s.claims.remove(slot);s.inventory.setItem(slot,null);
        claims.claim(p,claim,new ResultCallback<>(){
            public void success(ClaimRecord value){s.processing.set(false);messages.send(p,"claim.success");playSound(p,"sounds.success");}
            public void failure(Throwable error,String correlation){s.processing.set(false);s.claims.put(slot,claim);s.inventory.setItem(slot,claimIcon(claim));playSound(p,"sounds.error");messages.send(p,"claim.failed",Map.of("reason",error.getMessage()));}
        });
    }
    private void returnDeposit(Player p,Session s,String reason){List<ItemStack> values=new ArrayList<>();for(int i=0;i<s.depositSlots;i++){ItemStack item=s.inventory.getItem(i);if(item!=null&&!item.getType().isAir()){values.add(item.clone());s.inventory.setItem(i,null);}}returnItems(p,s.contract.id(),values,reason);}
    private static void returnDepositOnShutdown(Player p,Session s){List<ItemStack> values=new ArrayList<>();for(int i=0;i<s.depositSlots;i++){ItemStack item=s.inventory.getItem(i);if(item!=null&&!item.getType().isAir()){values.add(item.clone());s.inventory.setItem(i,null);}}if(values.isEmpty())return;Map<Integer,ItemStack> leftovers=p.getInventory().addItem(values.toArray(ItemStack[]::new));for(ItemStack item:leftovers.values())p.getWorld().dropItemNaturally(p.getLocation(),item);}
    private void returnItems(Player p,UUID contract,List<ItemStack> items,String reason){if(items.isEmpty())return;UUID playerId=p.getUniqueId();Map<Integer,ItemStack> left=p.getInventory().addItem(items.toArray(ItemStack[]::new));if(left.isEmpty())return;for(ItemStack item:left.values()){byte[] payload;try{payload=ItemBundleCodec.encode(List.of(serializer.serialize(item)),config.get().maxSerializedItemBytes()*8);}catch(RuntimeException error){byte[] emergency=serializer.serializeForRecovery(item);byte[] emergencyBundle=ItemBundleCodec.encode(List.of(emergency),Math.addExact(emergency.length,16));scheduler.runAsync(()->recovery.write(playerId,contract,emergencyBundle,reason+"; oversized item bypassed database claim: "+error.getMessage()));continue;}byte[] stored=payload;storage.storeItemReturn(playerId,contract,stored,reason).whenComplete((ok,error)->{if(error!=null)scheduler.runAsync(()->recovery.write(playerId,contract,stored,reason));});}}
    private boolean deliveryDepositAction(Player player,InventoryClickEvent event,Session session){
        event.setCancelled(true);if(session.processing.get())return true;Inventory top=event.getView().getTopInventory();int raw=event.getRawSlot();
        if(event.getClickedInventory()==top&&raw>=0&&raw<session.depositSlots){if(event.isShiftClick())moveDepositToPlayer(player,event);else if(safeDepositAction(event))event.setCancelled(false);return true;}
        if(event.getClickedInventory()==event.getView().getBottomInventory()&&event.isShiftClick()){movePlayerToDeposit(event,top,session.depositSlots);return true;}
        if(event.getClickedInventory()==event.getView().getBottomInventory()&&safeDepositAction(event)){event.setCancelled(false);return true;}
        return event.getClickedInventory()!=top;
    }
    private static boolean safeDepositAction(InventoryClickEvent event){return event.getClick()!=ClickType.DOUBLE_CLICK&&event.getClick()!=ClickType.NUMBER_KEY&&event.getClick()!=ClickType.SWAP_OFFHAND&&event.getAction()!=InventoryAction.COLLECT_TO_CURSOR;}
    private static void movePlayerToDeposit(InventoryClickEvent event,Inventory top,int limit){ItemStack source=event.getCurrentItem();if(source==null||source.getType().isAir())return;int remaining=source.getAmount();for(int slot=0;slot<limit&&remaining>0;slot++){ItemStack present=top.getItem(slot);if(present==null||!present.isSimilar(source)||present.getAmount()>=present.getMaxStackSize())continue;int moved=Math.min(remaining,present.getMaxStackSize()-present.getAmount());present.setAmount(present.getAmount()+moved);remaining-=moved;}for(int slot=0;slot<limit&&remaining>0;slot++){ItemStack present=top.getItem(slot);if(present!=null&&!present.getType().isAir())continue;int moved=Math.min(remaining,source.getMaxStackSize());ItemStack placed=source.clone();placed.setAmount(moved);top.setItem(slot,placed);remaining-=moved;}if(remaining==source.getAmount())return;if(remaining==0)event.setCurrentItem(null);else{ItemStack leftover=source.clone();leftover.setAmount(remaining);event.setCurrentItem(leftover);}}
    private static void moveDepositToPlayer(Player player,InventoryClickEvent event){ItemStack source=event.getCurrentItem();if(source==null||source.getType().isAir())return;Map<Integer,ItemStack> leftovers=player.getInventory().addItem(source.clone());event.setCurrentItem(leftovers.isEmpty()?null:leftovers.values().iterator().next());}
    private void open(Player p,Session s){p.openInventory(s.inventory);sessions.put(p.getUniqueId(),s);playSound(p,"sounds.open");}
    private Inventory create(ContractMenuHolder h,int size,String title,Map<String,?> values){Inventory inv=Bukkit.createInventory(h,size,messages.parseTemplate(title,values));h.inventory(inv);return inv;}
    private ItemStack item(String path,Map<String,?> values){ConfigurationSection c=menuSection(path);if(c==null)return named(Material.BARRIER,"<red>Missing "+path,List.of(),values);Material material=Material.matchMaterial(c.getString("material","BARRIER"));return decorate(named(material==null?Material.BARRIER:material,c.getString("name"," "),c.getStringList("lore"),values),path);}
    private ItemStack dynamic(String path,Material material,Map<String,?> values){ConfigurationSection c=menuSection(path);return decorate(c==null?named(material," ",List.of(),values):named(material,c.getString("name"," "),c.getStringList("lore"),values),path);}
    private ItemStack playerHead(String path,UUID owner,Map<String,?> values){ItemStack item=dynamic(path,Material.PLAYER_HEAD,values);if(owner!=null&&item.getItemMeta() instanceof SkullMeta skull){skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));item.setItemMeta(skull);}return item;}
    private String menuString(String path,String fallback){return menus.getString("locales."+config.get().locale()+"."+path,menus.getString(path,fallback));}
    private ConfigurationSection menuSection(String path){ConfigurationSection localized=menus.getConfigurationSection("locales."+config.get().locale()+"."+path);return localized==null?menus.getConfigurationSection(path):localized;}
    private int menuInt(String path,int fallback){return menus.getInt("locales."+config.get().locale()+"."+path,menus.getInt(path,fallback));}
    private boolean menuBoolean(String path,boolean fallback){return menus.getBoolean("locales."+config.get().locale()+"."+path,menus.getBoolean(path,fallback));}
    private int menuSize(String path,int fallback){int value=menuInt(path+".size",fallback);return value>=18&&value<=54&&value%9==0?value:fallback;}
    private int menuSlot(String path,int fallback,Inventory inventory){int configured=menuInt(path+".slot",fallback);if(configured>=0&&configured<inventory.getSize())return configured;return Math.min(Math.max(0,fallback),inventory.getSize()-1);}
    private void place(Inventory inventory,String path,int fallback,Map<String,?> values){inventory.setItem(menuSlot(path,fallback,inventory),item(path,values));}
    private ItemStack decorate(ItemStack item,String path){ItemMeta meta=item.getItemMeta();int cmd=menuInt(path+".custom-model-data",0);if(cmd>0)meta.setCustomModelData(cmd);if(menuBoolean(path+".hide-item-specifics",false))hideItemSpecifics(meta);if(menuBoolean(path+".glow",false)){Enchantment glow=Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));if(glow!=null){meta.addEnchant(glow,1,true);meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);}}item.setItemMeta(meta);return item;}
    private static void hideItemSpecifics(ItemMeta meta){for(String name:List.of("HIDE_ADDITIONAL_TOOLTIP","HIDE_ITEM_SPECIFICS","HIDE_POTION_EFFECTS"))try{meta.addItemFlags(ItemFlag.valueOf(name));return;}catch(IllegalArgumentException ignored){}}
    private void playSound(Player player,String path){String name=menus.getString(path+".name","");if(name.isBlank())return;try{player.playSound(player.getLocation(),Sound.valueOf(name.toUpperCase(Locale.ROOT)),(float)menus.getDouble(path+".volume",1.0),(float)menus.getDouble(path+".pitch",1.0));}catch(IllegalArgumentException error){plugin.getLogger().warning("Unknown menu sound at "+path+": "+name);}}
    private ItemStack named(Material material,String name,List<String> lore,Map<String,?> values){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(messages.parseTemplate(name,values).decoration(TextDecoration.ITALIC,false));meta.lore(lore.stream().map(line->messages.parseTemplate(line,values).decoration(TextDecoration.ITALIC,false)).toList());item.setItemMeta(meta);return item;}
    private ItemStack contractIcon(ContractSummary c){Map<String,Object> values=new HashMap<>();values.put("contract_id",c.shortId());values.put("material",c.material());values.put("creator",c.creatorName());values.put("delivered",c.deliveredAmount());values.put("total",c.totalAmount());values.put("remaining",c.remaining());values.put("reward",reward(c.rewardType(),c.rewardMinor()));values.put("matching",c.matchMode());values.put("status",c.status());values.put("time_left",time(c.expiresAt()));values.put("target",targetName(c.targetId()));if(c.assassination())return playerHead("listing.assassination",c.targetId(),values);Material requested=Material.matchMaterial(c.material());ItemStack icon=dynamic("listing.contract",requested==null?Material.PAPER:requested,values);icon.setAmount(listingDisplayAmount(c.totalAmount()));return icon;}
    private ItemStack contributionIcon(ContributionSummary c){Map<String,Object> values=new HashMap<>(Map.of("contract_id",c.contractShortId(),"material",c.material(),"amount",c.amount(),"payout",c.payoutMinor(),"date",c.createdAt()));values.put("target",targetName(c.targetId()));if(c.assassination())return playerHead("listing.assassination-contribution",c.targetId(),values);Material requested=Material.matchMaterial(c.material());ItemStack icon=dynamic("listing.contribution",requested==null?Material.PAPER:requested,values);icon.setAmount(listingDisplayAmount(c.totalAmount()));return icon;}
    private ItemStack detailIcon(Contract c){Map<String,Object> values=new HashMap<>();values.put("contract_id",c.shortId());values.put("total",c.totalAmount());values.put("material",c.material());values.put("delivered",c.deliveredAmount());values.put("remaining",c.remaining());values.put("reward",reward(c.rewardType(),c.rewardMinor()));values.put("matching",c.matchMode());values.put("fulfillment",c.fulfillmentMode());values.put("visibility",c.assassination()?"PUBLIC":c.directed()?"DIRECTED":"PUBLIC");values.put("target",targetName(c.targetId()));if(c.assassination())return playerHead("detail.assassination",c.targetId(),values);Material m=Material.matchMaterial(c.material());ItemStack icon=dynamic("detail.contract",m==null?Material.PAPER:m,values);icon.setAmount(listingDisplayAmount(c.totalAmount()));return icon;}
    private ItemStack claimIcon(ClaimRecord c){Material m=c.type()==dev.isylxnt.duskcontracts.domain.ClaimType.MONEY_REWARD||c.type()==dev.isylxnt.duskcontracts.domain.ClaimType.MONEY_RETURN?Material.GOLD_INGOT:Material.CHEST;return dynamic("claims.entry",m,Map.of("type",c.type(),"contract_id",Objects.toString(c.contractShortId(),"—"),"date",c.createdAt()));}
    private String reward(RewardType type,long minor){return type==RewardType.ITEM?"item reward":java.math.BigDecimal.valueOf(minor,config.get().decimalPlaces()).toPlainString();}
    private static String time(Instant expires){long seconds=Math.max(0,Duration.between(Instant.now(),expires).toSeconds());return seconds/3600+"h "+(seconds%3600)/60+"m";}
    static int listingDisplayAmount(long requested){return (int)Math.max(1,Math.min(64,requested));}
    private static boolean canView(Player player,Contract contract){return contract.assassination()||!contract.directed()||contract.creatorId().equals(player.getUniqueId())||contract.targetId().equals(player.getUniqueId())||player.hasPermission("duskcontracts.admin.inspect");}
    private static String targetName(UUID target){if(target==null)return "—";String name=Bukkit.getOfflinePlayer(target).getName();return name==null?target.toString().substring(0,8):name;}
    private void generic(Player p,Throwable error){String id=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);plugin.getLogger().warning("Menu operation failed [correlation="+id+"]: "+error);messages.send(p,"error.generic",Map.of("correlation_id",id));}
    private static Throwable unwrap(Throwable error){while((error instanceof java.util.concurrent.CompletionException||error instanceof java.util.concurrent.ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    private enum BrowseMode { LIBRARY, MINE, SEARCH, CONTRIBUTIONS }
    private static final class Session{final ContractMenuHolder holder;final Inventory inventory;final Map<Integer,ContractSummary> contracts=new HashMap<>();final Map<Integer,UUID> contributionContracts=new HashMap<>();final Map<Integer,ClaimRecord> claims=new HashMap<>();final AtomicBoolean processing=new AtomicBoolean();int page;int depositSlots;boolean hasNext;boolean participating;boolean cancelArmed;Contract contract;ContractFilter filter;BrowseMode browseMode;Session(ContractMenuHolder holder,Inventory inventory){this.holder=holder;this.inventory=inventory;this.depositSlots=Math.max(0,inventory.getSize()-9);}}
}
