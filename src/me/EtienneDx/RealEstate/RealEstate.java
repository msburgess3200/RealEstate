package me.EtienneDx.RealEstate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.earth2me.essentials.Essentials;

import co.aikar.commands.BukkitCommandManager;
import co.aikar.commands.ConditionFailedException;
import me.EtienneDx.RealEstate.ClaimAPI.IClaim;
import me.EtienneDx.RealEstate.ClaimAPI.IClaimAPI;
import me.EtienneDx.RealEstate.ClaimAPI.GriefDefender.GriefDefenderAPI;
import me.EtienneDx.RealEstate.ClaimAPI.GriefPrevention.GriefPreventionAPI;
import me.EtienneDx.RealEstate.Transactions.BoughtTransaction;
import me.EtienneDx.RealEstate.Transactions.ClaimAuction;
import me.EtienneDx.RealEstate.Transactions.ClaimLease;
import me.EtienneDx.RealEstate.Transactions.ClaimRent;
import me.EtienneDx.RealEstate.Transactions.ClaimSell;
import me.EtienneDx.RealEstate.Transactions.ExitOffer;
import me.EtienneDx.RealEstate.Transactions.Transaction;
import me.EtienneDx.RealEstate.Transactions.TransactionsStore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;

public class RealEstate extends JavaPlugin
{
	public Logger log;
    public Config config;
	public Messages messages;
    BukkitCommandManager manager;
	public final static String pluginDirPath = "plugins" + File.separator + "RealEstate" + File.separator;
	final static String languagesDirectory = RealEstate.pluginDirPath + "languages";
    public static boolean vaultPresent = false;
    public static Economy econ = null;
    public static Permission perms = null;
    public static Essentials ess = null;
    
    public static RealEstate instance = null;
    
    public static TransactionsStore transactionsStore = null;

	public static IClaimAPI claimAPI = null;
	
	@SuppressWarnings("deprecation")
	public void onEnable()
	{
		RealEstate.instance = this;
        this.log = getLogger();
        
        if (checkVault())
        {
            this.log.info("Vault has been detected and enabled.");
        }
		else
		{
			this.log.info("Vault has not been detected. RealEstate will not work without Vault.");
			this.log.info("Disabling RealEstate...");
			getPluginLoader().disablePlugin(this);
			return;
		}
		if (setupEconomy())
		{
			this.log.info("Vault is using " + econ.getName() + " as the economy plugin.");
		}
		else
		{
			this.log.warning("No compatible economy plugin detected [Vault].");
			this.log.warning("Disabling RealEstate...");
			getPluginLoader().disablePlugin(this);
			return;
		}
		if (setupPermissions())
		{
			this.log.info("Vault is using " + perms.getName() + " for the permissions.");
		}
		else
		{
			this.log.warning("No compatible permissions plugin detected [Vault].");
			this.log.warning("Disabling RealEstate...");
			getPluginLoader().disablePlugin(this);
			return;
		}

		if(setupGriefPreventionAPI()) {
		    this.log.info("RealEstate is using GriefPrevention as a claim management plugin.");
		} else if(setupGriefDefenderAPI()) {
		    this.log.info("RealEstate is using GriefDefender as a claim management plugin.");
		} else if(setupWorldGuardAPI()) {
		    this.log.info("RealEstate is using WorldGuard as a claim management plugin.");
		} else {
		    this.log.severe("No compatible Claim API detected. Please install GriefPrevention, GriefDefender, or WorldGuard.");
		    this.log.severe("Disabling RealEstate...");
		    getPluginLoader().disablePlugin(this);
		    return;
		}

        if((ess = (Essentials)getServer().getPluginManager().getPlugin("Essentials")) != null)
        {
        	this.log.info("Found Essentials, using version " + ess.getDescription().getVersion());
        }
        checkForOldFiles();
        this.config = new Config();
        this.config.loadConfig();// loads config or default
        this.config.saveConfig();// save eventual default

		this.messages = new Messages();
		this.messages.loadConfig();// loads customizable messages or defaults
		this.messages.saveConfig();// save eventual default

        ConfigurationSerialization.registerClass(ClaimSell.class);
        ConfigurationSerialization.registerClass(ClaimRent.class);
        ConfigurationSerialization.registerClass(ClaimLease.class);
        ConfigurationSerialization.registerClass(ClaimAuction.class);
        ConfigurationSerialization.registerClass(ExitOffer.class);
        
        RealEstate.transactionsStore = new TransactionsStore();
        
        new REListener().registerEvents();
        
        manager = new BukkitCommandManager(this);
        manager.enableUnstableAPI("help");
        registerConditions();
        manager.registerCommand(new RECommand());
        
		copyResourcesIntoPluginDirectory();
	}

    private void checkForOldFiles() {
		File oldConfig = new File("plugins" + File.separator + "RealEstate" + File.separator + "transactions.data");
		if(oldConfig.exists())
		{
			this.log.info("Found old transactions.data file, reformatting it...");
			// rename old file
			oldConfig.renameTo(new File("plugins" + File.separator + "RealEstate" + File.separator + "transactions.yml"));
		}
		
	}

	private void registerConditions()
    {
        manager.getCommandConditions().addCondition("inClaim", (context) -> {
        	if(context.getIssuer().isPlayer() && 
        			claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation()) != null)
        	{
        		return;
        	}
        	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        });
        manager.getCommandConditions().addCondition("claimHasTransaction", (context) -> {
        	if(!context.getIssuer().isPlayer())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
        	}
        	IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
        	if(c == null || c.isWilderness())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        	}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNoOngoingTransaction));
        	}
        });
        manager.getCommandConditions().addCondition("inPendingTransactionClaim", (context) -> {
        	if(!context.getIssuer().isPlayer())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
        	}
        	IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
        	if(c == null || c.isWilderness())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        	}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNotRentNorLease));
        	}
        	else if(tr instanceof BoughtTransaction && ((BoughtTransaction)tr).getBuyer() != null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorAlreadyBought));
        	}
        });
        manager.getCommandConditions().addCondition("inBoughtClaim", (context) -> {
        	if(!context.getIssuer().isPlayer())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
        	}
        	IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
        	if(c == null || c.isWilderness())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        	}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null || !(tr instanceof BoughtTransaction))
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNotRentNorLease));
        	}
        });
        manager.getCommandConditions().addCondition("partOfBoughtTransaction", context -> {
        	if(!context.getIssuer().isPlayer())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
        	}
        	IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
        	if(c == null || c.isWilderness())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        	}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNoOngoingTransaction));
        	}
        	if(!(tr instanceof BoughtTransaction))
        	{
            	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNotRentNorLease));
        	}
        	if((((BoughtTransaction)tr).buyer != null && ((BoughtTransaction)tr).buyer.equals(context.getIssuer().getPlayer().getUniqueId())) || 
        			(tr.getOwner() != null && (tr.getOwner().equals(context.getIssuer().getPlayer().getUniqueId()))) || 
        			(c.isAdminClaim() && RealEstate.perms.has(context.getIssuer().getPlayer(), "realestate.admin")))
        	{
        		return;
        	}
        	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNotPartOfTransaction));
        });
        manager.getCommandConditions().addCondition("partOfRent", context -> {
        	if(!context.getIssuer().isPlayer())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
        	}
        	IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
        	if(c == null || c.isWilderness())
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
        	}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNoOngoingTransaction));
        	}
        	if(!(tr instanceof ClaimRent))
        	{
            	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorRentOnly));
        	}
        	if((((ClaimRent)tr).buyer != null && ((ClaimRent)tr).buyer.equals(context.getIssuer().getPlayer().getUniqueId())) || 
        			(tr.getOwner() != null && (tr.getOwner().equals(context.getIssuer().getPlayer().getUniqueId()))) || 
        			(c.isAdminClaim() && RealEstate.perms.has(context.getIssuer().getPlayer(), "realestate.admin")))
        	{
        		return;
        	}
        	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNotPartOfTransaction));
        });
        manager.getCommandConditions().addCondition(Double.class, "positiveDouble", (c, exec, value) -> {
        	if(value > 0) return;
        	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorValueGreaterThanZero));
        });
		manager.getCommandConditions().addCondition("claimIsAuctioned", (context) -> {
			if(!context.getIssuer().isPlayer())
			{
				throw new ConditionFailedException(Messages.getMessage(messages.msgErrorPlayerOnly));
			}
			IClaim c = claimAPI.getClaimAt(context.getIssuer().getPlayer().getLocation());
			if(c == null || c.isWilderness())
			{
				throw new ConditionFailedException(Messages.getMessage(messages.msgErrorOutOfClaim));
			}
        	Transaction tr = transactionsStore.getTransaction(c);
        	if(tr == null)
        	{
        		throw new ConditionFailedException(Messages.getMessage(messages.msgErrorNoOngoingTransaction));
        	}
        	if(!(tr instanceof ClaimAuction))
        	{
            	throw new ConditionFailedException(Messages.getMessage(messages.msgErrorAuctionOnly));
        	}
		});
	}

	public void addLogEntry(String entry)
    {
        try
        {
            File logFile = new File(this.config.logFilePath);
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);

            pw.println(entry);
            pw.flush();
            pw.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private boolean checkVault()
    {
        vaultPresent = getServer().getPluginManager().getPlugin("Vault") != null;
        return vaultPresent;
    }

	private boolean setupGriefPreventionAPI()
	{
		if(getServer().getPluginManager().getPlugin("GriefPrevention") != null)
		{
			claimAPI = new GriefPreventionAPI();
			return true;
		}
		return false;
	}

	private boolean setupGriefDefenderAPI()
	{
		if(getServer().getPluginManager().getPlugin("GriefDefender") != null)
		{
			claimAPI = new GriefDefenderAPI();
			return true;
		}
		return false;
	}

    private boolean setupEconomy()
    {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = (Economy)rsp.getProvider();
        return econ != null;
    }

    private boolean setupPermissions()
    {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = (Permission)rsp.getProvider();
        return perms != null;
    }
    
    private boolean setupWorldGuardAPI() {
        if(getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            claimAPI = new me.EtienneDx.RealEstate.ClaimAPI.WorldGuard.WorldGuardAPI();
            return true;
        }
        return false;
    }

	private void copyResourcesIntoPluginDirectory()
	{
		this.log.info("Checking language files...");
		Path pluginPath = Paths.get(RealEstate.pluginDirPath);
		File pluginDirectory = pluginPath.toFile();
		if(!pluginDirectory.exists())
		{
			pluginDirectory.mkdirs();
		}
		// for each file in the resource folder
		FileSystem fileSystem = null;
		try
		{
			URI uri = RealEstate.class.getResource("/resources").toURI();
			Path myPath;

			if (uri.getScheme().equals("jar"))
			{
				fileSystem = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
				myPath = fileSystem.getPath("/resources");
			}
			else
			{
				myPath = Paths.get(uri);
			}
			
			try(Stream<Path> walk = Files.walk(myPath, 3))
			{
				Iterator<Path> it = walk.iterator();
				it.next();// skip first
				for (; it.hasNext();)
				{
					Path path = it.next();
					Path targetPath = pluginPath.resolve(path.toString().substring(11));
					if(!targetPath.toFile().exists())
					{
						Files.createDirectories(targetPath.getParent());
						try(InputStream s = RealEstate.class.getResourceAsStream(path.toString()))
						{
							if(s.available() > 0)
							{
								Files.copy(s, targetPath);
								this.log.info("Adding language file: " + targetPath.getFileName());
							}
						}
						catch(NoSuchFileException e)
						{
							// ignore
						}
					}
				}
			}
		}
		catch(Exception e)
		{
			this.log.warning("Couldn't copy resources to plugin directory...");
			e.printStackTrace();
		}
		if(fileSystem != null)
		{
			try
			{
				fileSystem.close();
			}
			catch(IOException e)
			{
				// do nothing in case of error
			}
		}
	}
}
