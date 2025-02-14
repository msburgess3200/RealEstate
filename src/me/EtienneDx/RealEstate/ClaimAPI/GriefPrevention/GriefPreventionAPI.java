package me.EtienneDx.RealEstate.ClaimAPI.GriefPrevention;

import java.util.UUID;

import org.bukkit.Location;

import me.EtienneDx.RealEstate.ClaimAPI.IClaim;
import me.EtienneDx.RealEstate.ClaimAPI.IClaimAPI;
import me.EtienneDx.RealEstate.ClaimAPI.IPlayerData;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.Claim;

public class GriefPreventionAPI implements IClaimAPI
{
    @Override
    public IClaim getClaimAt(Location location) {
        Claim gpclaim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);

        if (gpclaim == null) {
            return null;
        }

        return new GPClaim(gpclaim);
    }

    @Override
    public void saveClaim(IClaim claim) {
        if(claim instanceof GPClaim)
            GriefPrevention.instance.dataStore.saveClaim(((GPClaim) claim).getClaim());
    }

    @Override
    public IPlayerData getPlayerData(UUID player) {
        return new GPPlayerData(GriefPrevention.instance.dataStore.getPlayerData(player));
    }

    @Override
    public void changeClaimOwner(IClaim claim, UUID newOwner) {
        if(claim instanceof GPClaim)
            GriefPrevention.instance.dataStore.changeClaimOwner(((GPClaim) claim).getClaim(), newOwner);
    }

    @Override
    public void registerEvents() {
        new ClaimPermissionListener().registerEvents();
    }
    
}
