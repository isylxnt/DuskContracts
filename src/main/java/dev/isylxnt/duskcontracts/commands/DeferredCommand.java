package dev.isylxnt.duskcontracts.commands;

import dev.isylxnt.duskcontracts.localization.Messages;
import org.bukkit.command.*;
import java.util.List;

public final class DeferredCommand implements CommandExecutor, TabCompleter {
    private final Messages messages; private volatile ContractsCommand delegate;
    public DeferredCommand(Messages messages){this.messages=messages;}
    public void delegate(ContractsCommand delegate){this.delegate=delegate;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){ContractsCommand current=delegate;if(current==null){messages.send(sender,"admin.initializing");return true;}return current.onCommand(sender,command,label,args);}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){ContractsCommand current=delegate;return current==null?List.of():current.onTabComplete(sender,command,alias,args);}
}
