package lilypuree.metabolism.metabolism;

import lilypuree.metabolism.MetabolismGameRules;
import lilypuree.metabolism.Registration;
import lilypuree.metabolism.config.Config;
import lilypuree.metabolism.environment.Environment;
import lilypuree.metabolism.environment.EnvironmentEffect;
import lilypuree.metabolism.metabolite.Metabolite;
import lilypuree.metabolism.network.ClientSyncMessage;
import lilypuree.metabolism.network.Network;
import lilypuree.metabolism.network.ProgressSyncMessage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.network.NetworkDirection;

import static lilypuree.metabolism.metabolism.MetabolismConstants.*;

public class Metabolism {
    private static final double LN_10 = Math.log(10);

    //VARIABLES
    private float maxWarmth;
    private float warmth;
    private float heat;
    private float hydration;
    private float food;
    private float progress;

    /**
     * the amount of time it takes to reach approximately 90% of the given heat target, in ticks
     * changing this will vary how fast effects will cause heat changes to the player
     */
    private int adaptationTicks;
    private float heatCoefficent;

    //SYNCING
    private float lastSentWarmth;
    private float lastSentHeat;
    private float lastSentHydration;
    private float lastSentFood;
    private float lastSentProgress;

    //TICKING
    private int baseTick = 0;
    private int envCounter = 0;
    private int regenCounter = 0;
    private int damageCounter = 0;

    public Metabolism() {
        this.maxWarmth = MetabolismConstants.MAX_WARMTH;
        this.warmth = maxWarmth;
        this.heat = 0.0F;
        this.food = MetabolismConstants.START_FOOD;
        this.hydration = MetabolismConstants.START_HYDRATION;
        this.progress = 0.0F;
        this.setAdaptationTicks(MetabolismConstants.ADAPTATION_TICKS);
    }

    public static Metabolism get(Player player) {
        return ((FoodDataDuck) player.getFoodData()).getMetabolism();
    }

    public void tick(Player player) {
        boolean suppressDamage = false;
        baseTick++;

        if (baseTick >= BASE_TICK_COUNT) {
            envCounter++;
            damageCounter++;

            if (warmth > player.getHealth()) {
                //turns on fast regen
                regenCounter += REGEN_CYCLES;
                suppressDamage = player.isHurt();
            } else regenCounter++;

            baseTick = 0;
        }
        if (envCounter >= ENVIRONMENT_CYCLES) {
            //apply environmental effects
            ServerLevel level = (ServerLevel) player.level();
            EnvironmentEffect.Combined effect = Environment.get().getCurrentEffect(level, player);
            if (level.getGameRules().getBoolean(MetabolismGameRules.RULE_DO_TEMPERATURE))
                applyHeatTarget(effect.getCombinedHeatTarget());
            else
                applyHeatTarget(0);
            warm(effect.getCombinedWarmthEffect());
            envCounter = 0;
        }
        if (damageCounter >= DAMAGE_CYCLES) {
            if (!suppressDamage)
                causeDamage(player);
            damageCounter = 0;
        }
        if (regenCounter >= REGEN_CYCLES) {
            regenHealth(player);
            regenCounter = 0;
        }
        metabolismEffect(player);

        syncToClient((ServerPlayer) player);
    }

    private void regenHealth(Player player) {
        boolean regen = player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        if (regen && player.isHurt() && warmth > 0) {
            player.heal(1.0F);
            warmth = Math.max(0.0F, warmth - 1.0F);
        }
    }

    private void causeDamage(Player player) {
        if (heat > 0) {
            if (hydration > 0)
                consumeHydration(calculateDrain());
            else {
                consumeFood(1.0F);
                if (canBeHurt(player))
                    player.hurt(player.damageSources().starve(), 1.0F);
            }
        } else if (heat < 0) {
            if (food > 0)
                consumeFood(calculateDrain());
            else {
                consumeHydration(1.0F);
                if (canBeHurt(player))
                    player.hurt(player.damageSources().starve(), 1.0F);
            }
        }

        if (canBeHurt(player)) {
            if (heat == maxWarmth) {
                player.hurt(player.damageSources().inFire(), 1.0F);
            } else if (heat == -maxWarmth) {
                player.hurt(player.damageSources().freeze(), 1.0F);
            }
        }
    }

    private void applyHeatTarget(float heatTarget) {
        if (Math.abs(heat - heatTarget) < 1E-2) {
            heat = heatTarget;
        } else if (heat > 0) {
            //HOT
            heat = Mth.clamp(heat + heatChange(heatTarget, hydration > 0), 0.0F, maxWarmth);
        } else if (heat < 0) {
            //COLD
            heat = Mth.clamp(heat + heatChange(heatTarget, food > 0), -maxWarmth, 0.0F);
        } else if (heatTarget != 0) {
            //MILD
            heat = Mth.clamp(heatChange(heatTarget, false), -maxWarmth, maxWarmth);
        }
    }

    private float heatChange(float heatTarget, boolean feedback) {
        float diff = feedback ? heatTarget - heat : heatTarget;
        if (Config.SERVER.preciseFeedback()) {
            return diff * heatCoefficent + diff * heatCoefficent * heatCoefficent / 2;
        } else {
            return diff * heatCoefficent;
        }
    }

    private void metabolismEffect(Player player) {
        if (player.hasEffect(Registration.METABOLISM_EFFECT.get())) {
            int amp = player.getEffect(Registration.METABOLISM_EFFECT.get()).getAmplifier();
            progress += ((float) amp + 1) / BASE_TICK_COUNT / METABOLISM_CYCLES;
        }

        if (progress >= 1.0F) {
            // Metabolise food and hydration to warmth
            if (food > 0 && hydration > 0 && warmth < maxWarmth - Math.abs(heat)) {
                consumeFood(1.0F);
                consumeHydration(1.0F);
                warm(1.0F);
            }
            progress -= 1.0F;
        }
    }

    private void syncToClient(ServerPlayer player) {
        boolean changed = warmth != lastSentWarmth || heat != lastSentHeat || food != lastSentFood || hydration != lastSentHydration;
        if (changed) {
            ClientSyncMessage msg = new ClientSyncMessage(heat, warmth, food, hydration);
            Network.channel.sendTo(msg, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            lastSentWarmth = warmth;
            lastSentHeat = heat;
            lastSentFood = food;
            lastSentHydration = hydration;
        }

        if (Math.abs(progress - lastSentProgress) >= 0.05f) {
            ProgressSyncMessage msg = new ProgressSyncMessage(progress);
            Network.channel.sendTo(msg, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            lastSentProgress = progress;
        }
    }

    public void addProgress(float amount) {
        this.progress += amount;
    }

    public void consumeFood(float amount) {
        this.food = Math.max(0.0F, food - amount);
    }

    public void consumeHydration(float amount) {
        this.hydration = Math.max(0.0F, hydration - amount);
    }

    public void eat(LivingEntity entity, Metabolite metabolite) {
        this.food += metabolite.food();
        this.hydration += metabolite.hydration();
        if (metabolite.warmth() > 0 && entity != null) {
            entity.addEffect(new MetabolismEffect.Instance(metabolite.getEffectTicks(), metabolite.amplifier()));
        } else if (metabolite.warmth() < 0) {
            this.warm(metabolite.warmth());
        }
    }

    public void peacefulWarmth() {
        if (heat > 0)
            this.heat = Math.max(heat - 1, 0);
        else if (heat < 0)
            this.heat = Math.min(heat + 1, 0);

        this.warm(1.0F);
    }

    public void warm(float amount) {
        setWarmth(warmth + amount);
    }

    public float getMaxWarmth() {
        return maxWarmth;
    }

    public float getWarmth() {
        return warmth;
    }

    public float getHeat() {
        return heat;
    }

    public float getProgress() {
        return progress;
    }

    public float getFood() {
        return food;
    }


    public float getHydration() {
        return this.hydration;
    }

    public void setWarmth(float warmth) {
        this.warmth = Mth.clamp(warmth, 0.0F, maxWarmth - Mth.abs(heat));
    }

    public void setHeat(float heat) {
        this.heat = Mth.clamp(heat, -maxWarmth, maxWarmth);
        setWarmth(warmth);
    }

    public void setProgress(float progress) {
        this.progress = progress;
    }

    public void setFood(float food) {
        this.food = Mth.clamp(food, 0.0F, MAX_FOOD);
    }


    public void setHydration(float hydration) {
        this.hydration = Mth.clamp(hydration, 0.0F, MAX_FOOD);
    }


    //amount of ticks required for 1 resource to be drained
    public float drainDuration() {
        return BASE_TICK_COUNT * DAMAGE_CYCLES / calculateDrain();
    }

    private float calculateDrain() {
        return Mth.abs(heat) * DRAIN_COEFFICIENT;
    }

    private void setAdaptationTicks(int ticks) {
        this.adaptationTicks = ticks;
        this.heatCoefficent = (float) (LN_10 * ENVIRONMENT_CYCLES * BASE_TICK_COUNT / adaptationTicks);
    }


    private boolean canBeHurt(Player player) {
        Difficulty difficulty = player.level().getDifficulty();
        return player.getHealth() > 10.0F || difficulty == Difficulty.HARD || player.getHealth() > 1.0F && difficulty == Difficulty.NORMAL;
    }

    public boolean canEat(Metabolite metabolite) {
        if (metabolite == Metabolite.NONE) return false;
        boolean foodAllowed = food + metabolite.food() < MAX_FOOD;
        boolean hydrationAllowed = hydration + metabolite.hydration() < MAX_FOOD;
        return foodAllowed && hydrationAllowed;
    }

    public CompoundTag writeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.putFloat("maxWarmth", maxWarmth);
        nbt.putFloat("warmth", warmth);
        nbt.putFloat("heat", heat);
        nbt.putFloat("food", food);
        nbt.putFloat("hydration", hydration);
        nbt.putFloat("progress", progress);
        return nbt;
    }

    public void readNBT(CompoundTag nbt) {
        maxWarmth = nbt.getFloat("maxWarmth");
        warmth = nbt.getFloat("warmth");
        heat = nbt.getFloat("heat");
        food = nbt.getFloat("food");
        hydration = nbt.getFloat("hydration");
        progress = nbt.getFloat("progress");
    }

    public void syncOnClient(ClientSyncMessage msg) {
        heat = msg.heat;
        warmth = msg.warmth;
        hydration = msg.hydration;
        food = msg.food;
    }

    public void syncProgress(ProgressSyncMessage msg) {
        progress = msg.progress();
    }

}
