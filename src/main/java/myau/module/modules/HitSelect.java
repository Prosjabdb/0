package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.BooleanProperty;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Hit select types per docs: Parry, Quarter Select, Deep Select, Mid-Trading
    public final ModeProperty mode = new ModeProperty("Mode", 0, 
        new String[]{"PARRY", "QUARTER", "DEEP", "MIDTRADE", "LIGHT", "LONG", "ZEST"});
    
    // Timing properties (ticks, 20 TPS = 50ms per tick)
    public final FloatProperty delay = new FloatProperty("Delay", 2.0F, 0.0F, 20.0F);
    public final FloatProperty pingComp = new FloatProperty("PingComp", 0.0F, 0.0F, 10.0F);
    
    // Advanced options
    public final BooleanProperty groundOnly = new BooleanProperty("GroundOnly", true);
    public final BooleanProperty momentumCheck = new BooleanProperty("MomentumCheck", true);
    public final BooleanProperty firstHitTrap = new BooleanProperty("FirstHitTrap", false);
    
    // State tracking
    private boolean sprintState = false;
    private boolean shouldBlock = false;
    private int blockTicks = 0;
    private int comboCount = 0;
    private long lastHitTime = 0;
    private boolean wasHit = false;
    private int hurtTimeTicks = 0;
    
    // Target tracking
    private EntityLivingBase currentTarget = null;
    private int targetHurtTime = 0;
    private boolean targetWasHurt = false;
    
    // Timing
    private TimerUtil timer = new TimerUtil();
    private int selectDelay = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }

        // Track sprint state
        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            if (packet.getAction() == C0BPacketEntityAction.Action.START_SPRINTING) {
                this.sprintState = true;
            } else if (packet.getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                this.sprintState = false;
            }
            return;
        }

        // Handle attack packets
        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball || !(target instanceof EntityLivingBase)) {
                return;
            }

            EntityLivingBase living = (EntityLivingBase) target;
            this.currentTarget = living;
            
            // Determine if we should select this hit
            boolean allow = shouldAllowHit(living);
            
            if (!allow) {
                event.setCancelled(true);
                this.shouldBlock = true;
                this.blockTicks = getBlockDuration();
                
                // Apply momentum preservation via KeepSprint if available
                preserveMomentum();
            } else {
                this.shouldBlock = false;
                this.comboCount++;
                this.lastHitTime = System.currentTimeMillis();
            }
        }
    }

    private boolean shouldAllowHit(EntityLivingBase target) {
        // Ground check per docs: hit select works best on ground
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround) {
            return true; // Allow if not on ground (can't properly select)
        }

        // Basic conditions that always allow hits
        if (mc.thePlayer.hurtTime > 0) return true; // We got hit, trade back
        if (target.hurtTime > 0) return true; // Target already hurt, combo
        if (mc.thePlayer.isCollidedHorizontally) return true; // Trapped, must hit
        
        double distance = mc.thePlayer.getDistanceToEntity(target);
        
        // Mode-specific logic based on docs
        switch (this.mode.getValue()) {
            case 0: // PARRY (~0.1-0.25s after hit, 2-5 ticks)
                return handleParrySelect(target, distance);
                
            case 1: // QUARTER (~0.5-1s after hit, 10-20 ticks)
                return handleQuarterSelect(target, distance);
                
            case 2: // DEEP (1s+ after hit, 20+ ticks)
                return handleDeepSelect(target, distance);
                
            case 3: // MIDTRADE (hit select on every hit - P5/M8 difficulty)
                return handleMidTrade(target, distance);
                
            case 4: // LIGHT (1-2 ticks/100ms after opponent hits - safest)
                return handleLightSelect(target, distance);
                
            case 5: // LONG (wait longer, abuse KB reduction, 80ms+ recommended)
                return handleLongSelect(target, distance);
                
            case 6: // ZEST (Hypixel specific: hold W 2 hits, release on 3rd)
                return handleZestTap(target, distance);
        }
        
        return true;
    }

    // PARRY: Quick select 0.1-0.25s after being hit (2-5 ticks)
    private boolean handleParrySelect(EntityLivingBase target, double distance) {
        // If we just got hit (hurtTime high), allow immediate counter
        if (wasHit && hurtTimeTicks <= 5) {
            return true;
        }
        
        // If target is at optimal range for parry (2-3 blocks)
        if (distance >= 2.0 && distance <= 3.0) {
            // Check if they're about to hit us (sprinting towards us)
            if (isMovingTowards(target, mc.thePlayer, 45.0)) {
                // Delay slightly for parry timing
                selectDelay = 2 + (int)(pingComp.getValue() * 0.5f);
                return timer.hasTimeElapsed(selectDelay * 50L);
            }
        }
        
        return true;
    }

    // QUARTER: 0.5-1s delay (10-20 ticks), good for catching opponents off-guard
    private boolean handleQuarterSelect(EntityLivingBase target, double distance) {
        // Block first hit, allow second with timing
        if (comboCount % 2 == 0) {
            selectDelay = 10 + (int)delay.getValue();
            return timer.hasTimeElapsed(selectDelay * 50L);
        }
        return true;
    }

    // DEEP: 1s+ delay, forces opponent to mess up their timing
    private boolean handleDeepSelect(EntityLivingBase target, double distance) {
        // Only deep select if we have space and momentum
        if (this.momentumCheck.getValue() && mc.thePlayer.motionX * mc.thePlayer.motionX + 
            mc.thePlayer.motionZ * mc.thePlayer.motionZ < 0.01) {
            return true; // No momentum, don't deep select
        }
        
        selectDelay = 20 + (int)(delay.getValue() * 2);
        return timer.hasTimeElapsed(selectDelay * 50L);
    }

    // MIDTRADE: Select every hit - requires perfect timing (P8/M10)
    private boolean handleMidTrade(EntityLivingBase target, double distance) {
        // Per docs: parry timing every hit while W-tapping
        // This is advanced: only select if we have advantage
        if (target.hurtTime > 0 && mc.thePlayer.hurtTime == 0) {
            // We have hit advantage, select to maintain it
            selectDelay = 1 + (int)(pingComp.getValue() * 0.3f);
            return timer.hasTimeElapsed(selectDelay * 50L);
        }
        
        // If opponent has advantage, don't select (trade back)
        if (mc.thePlayer.hurtTime > target.hurtTime) {
            return true;
        }
        
        // Default: slight delay for mid-trade
        return timer.hasTimeElapsed((long)(delay.getValue() * 50L));
    }

    // LIGHT: 1-2 ticks/100ms after opponent hit - safest method
    private boolean handleLightSelect(EntityLivingBase target, double distance) {
        // Per docs: mainly used vs low ping first-hit players
        // Any ping except 150ms+ can use
        
        if (pingComp.getValue() > 3.0f) {
            // High ping, don't light select
            return true;
        }
        
        // Select if opponent just hit us (within 100ms/2 ticks)
        if (wasHit && hurtTimeTicks <= 2) {
            return false; // Block and counter
        }
        
        return true;
    }

    // LONG: Wait longer after hit, abuse KB reduction to walk through
    private boolean handleLongSelect(EntityLivingBase target, double distance) {
        // Per docs: more efficient at higher ping (80ms+ recommended)
        if (pingComp.getValue() < 1.6f) { // 80ms = ~1.6 ticks
            return true; // Low ping, don't long select
        }
        
        // Wait for KB reduction to kick in, then walk through opponent
        selectDelay = 8 + (int)(pingComp.getValue() * 2);
        
        // Jump into fight for momentum (per docs)
        if (mc.thePlayer.onGround && isMoving()) {
            // Queue jump for next tick to reduce KB on first hit
        }
        
        return timer.hasTimeElapsed(selectDelay * 50L);
    }

    // ZEST: Hypixel specific - hold W 2 hits, release on 3rd
    private boolean handleZestTap(EntityLivingBase target, double distance) {
        // Per docs: Zest Tapping = hold W first 2 hits, release on 3rd when opponent falls
        int hitPattern = comboCount % 3;
        
        if (hitPattern == 2) {
            // Third hit - release W (select this one)
            // In real implementation, this would sync with movement
            return timer.hasTimeElapsed(50L); // Slight delay for release timing
        }
        
        return true;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        
        // Track our hurt time
        if (mc.thePlayer.hurtTime > hurtTimeTicks) {
            wasHit = true;
            timer.reset();
        } else if (mc.thePlayer.hurtTime == 0) {
            wasHit = false;
        }
        hurtTimeTicks = mc.thePlayer.hurtTime;
        
        // Track target hurt time
        if (currentTarget != null) {
            if (currentTarget.hurtTime < targetHurtTime) {
                // Target hurt time decreased, they recovered
                targetWasHurt = false;
            } else if (currentTarget.hurtTime > 0) {
                targetWasHurt = true;
            }
            targetHurtTime = currentTarget.hurtTime;
        }
        
        // Handle blocking state
        if (shouldBlock) {
            blockTicks--;
            if (blockTicks <= 0) {
                shouldBlock = false;
                restoreMomentum();
            }
        }
        
        // First hit trap logic (per docs)
        if (firstHitTrap.getValue() && comboCount == 0 && currentTarget != null) {
            // If opponent is first-hitting, delay our hit to counter
            if (isMovingTowards(currentTarget, mc.thePlayer, 30.0) && 
                currentTarget.isSprinting()) {
                // They're rushing us, select to counter
                shouldBlock = true;
                blockTicks = 3;
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        // Reset combo if we haven't hit in a while
        if (System.currentTimeMillis() - lastHitTime > 1000) {
            comboCount = 0;
        }
    }

    private void preserveMomentum() {
        // Interact with KeepSprint to maintain momentum during select
        try {
            KeepSprint ks = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (ks != null && !ks.isEnabled()) {
                ks.toggle();
                // Set slowdown to 0 for full momentum preservation
                if (ks.slowdown != null) {
                    ks.slowdown.setValue(0);
                }
            }
        } catch (Exception ignored) {}
    }

    private void restoreMomentum() {
        // Restore KeepSprint state
        try {
            KeepSprint ks = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (ks != null && ks.isEnabled()) {
                ks.toggle();
            }
        } catch (Exception ignored) {}
    }

    private int getBlockDuration() {
        // Return appropriate block duration based on mode
        switch (mode.getValue()) {
            case 0: return 3;  // Parry
            case 1: return 10; // Quarter
            case 2: return 20; // Deep
            case 3: return 2;  // Midtrade
            case 4: return 2;  // Light
            case 5: return 8;  // Long
            case 6: return 1;  // Zest
            default: return 2;
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        if (movementLength < 0.001) return false;

        mx /= movementLength;
        mz /= movementLength;

        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        if (targetLength < 0.001) return false;

        tx /= targetLength;
        tz /= targetLength;

        double dotProduct = mx * tx + mz * tz;
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisable() {
        super.onDisable();
        restoreMomentum();
        this.sprintState = false;
        this.shouldBlock = false;
        this.blockTicks = 0;
        this.comboCount = 0;
        this.wasHit = false;
        this.currentTarget = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeAsString()};
    }
}
