package net.dabbit.supercow.data;

import java.util.UUID;

public class PetData {
    public UUID ownerUuid;
    public String ownerName;
    public int level = 1;
    public int exp = 0;
    public double currentHealth = 24.0;
    public double maxHealth = 24.0;
    public boolean isAlive = true;
    public long deathTimestampMs = 0L;
    public String combatMode = "PROTECTING";
    public String summonMode = "DEFENSIVE";
    public int totalKills = 0;

    public PetData() {
    }

    public PetData(UUID ownerUuid, String ownerName, double baseHealth) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.level = 1;
        this.exp = 0;
        this.currentHealth = baseHealth;
        this.maxHealth = baseHealth;
        this.isAlive = true;
        this.deathTimestampMs = 0L;
        this.combatMode = "PROTECTING";
        this.summonMode = "DEFENSIVE";
        this.totalKills = 0;
    }
}
