package at.vystorm.insomnia.model;

public final class ActivityState {
    private boolean sprinting;
    private boolean swimming;
    private long miningUntil;
    private long combatUntil;
    private long bossCombatUntil;

    public boolean sprinting() { return sprinting; }
    public void sprinting(boolean value) { sprinting = value; }
    public boolean swimming() { return swimming; }
    public void swimming(boolean value) { swimming = value; }
    public long miningUntil() { return miningUntil; }
    public void miningUntil(long value) { miningUntil = value; }
    public long combatUntil() { return combatUntil; }
    public void combatUntil(long value) { combatUntil = value; }
    public long bossCombatUntil() { return bossCombatUntil; }
    public void bossCombatUntil(long value) { bossCombatUntil = value; }
}
