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
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, 
        new String[]{"PARRY", "QUARTER", "DEEP", "MIDTRADE", "LIGHT", "LONG", "ZEST"});
    
    public final FloatProperty delay = new FloatProperty("Delay", 2.0F, 0.0F, 20.0F);
    public final FloatProperty pingComp = new FloatProperty("PingComp", 0.0F, 0.0F, 10.0F);
    public final BooleanProperty groundOnly = new BooleanProperty("GroundOnly", true);
    public final BooleanProperty momentumCheck = new BooleanProperty("MomentumCheck", true);
    public final BooleanProperty firstHitTrap = new BooleanProperty("FirstHitTrap", false);
    
    private boolean sprintState = false;
    private boolean shouldBlock = false;
    private int blockTicks = 0;
    private int comboCount = 0;
    private long lastHitTime = 0;
    private boolean wasHit = false;
    private int hurtTimeTicks = 0;
    private EntityLivingBase currentTarget = null;
    private int targetHurtTime = 0;
    private boolean targetWasHurt = false;
    private TimerUtil timer = new TimerUtil();
    private int selectDelay = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            if (packet.getAction() == C0BPacketEntityAction.Action.START_SPRINTING) {
                this.sprintState = true;
            } else if (packet.getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                this.sprintState = false;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball || !(target instanceof EntityLivingBase)) return;

            EntityLivingBase living = (EntityLivingBase) target;
            this.currentTarget = living;
            
            boolean allow = shouldAllowHit(living);
            
            if (!allow) {
                event.setCancelled(true);
                this.shouldBlock = true;
                this.blockTicks = getBlockDuration();
                preserveMomentum();
            } else {
                this.shouldBlock = false;
                this.comboCount++;
                this.lastHitTime = System.currentTimeMillis();
            }
        }
    }

    private boolean shouldAllowHit(EntityLivingBase target) {
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround) return true;
        if (mc.thePlayer.hurtTime > 0) return true;
        if (target.hurtTime > 0) return true;
        if (mc.thePlayer.isCollidedHorizontally) return true;
        
        double distance = mc.thePlayer.getDistanceToEntity(target);
        
        switch (this.mode.getValue()) {
            case 0: return handleParrySelect(target, distance);
            case 1: return handleQuarterSelect(target, distance);
            case 2: return handleDeepSelect(target, distance);
            case 3: return handleMidTrade(target, distance);
            case 4: return handleLightSelect(target, distance);
            case 5: return handleLongSelect(target, distance);
            case 6: return handleZestTap(target, distance);
        }
        return true;
    }

    private boolean handleParrySelect(EntityLivingBase target, double distance) {
        if (wasHit && hurtTimeTicks <= 5) return true;
        if (distance >= 2.0 && distance <= 3.0 && isMovingTowards(target, mc.thePlayer, 45.0)) {
            selectDelay = 2 + (int)(pingComp.getValue() * 0.5f);
            return timer.hasTimeElapsed(selectDelay * 50L);
        }
        return true;
    }

    private boolean handleQuarterSelect(EntityLivingBase target, double distance) {
        if (comboCount % 2 == 0) {
            selectDelay = 10 + (int)(float)delay.getValue();
            return timer.hasTimeElapsed(selectDelay * 50L);
        }
        return true;
    }

    private boolean handleDeepSelect(EntityLivingBase target, double distance) {
        if (this.momentumCheck.getValue() && mc.thePlayer.motionX * mc.thePlayer.motionX + 
            mc.thePlayer.motionZ * mc.thePlayer.motionZ < 0.01) return true;
        
        selectDelay = 20 + (int)((float)delay.getValue() * 2);
        return timer.hasTimeElapsed(selectDelay * 50L);
    }

    private boolean handleMidTrade(EntityLivingBase target, double distance) {
        if (target.hurtTime > 0 && mc.thePlayer.hurtTime == 0) {
            selectDelay = 1 + (int)(pingComp.getValue() * 0.3f);
            return timer.hasTimeElapsed(selectDelay * 50L);
        }
        if (mc.thePlayer.hurtTime > target.hurtTime) return true;
        return timer.hasTimeElapsed((long)((float)delay.getValue() * 50L));
    }

    private boolean handleLightSelect(EntityLivingBase target, double distance) {
        if (pingComp.getValue() > 3.0f) return true;
        if (wasHit && hurtTimeTicks <= 2) return false;
        return true;
    }

    private boolean handleLongSelect(EntityLivingBase target, double distance) {
        if (pingComp.getValue() < 1.6f) return true;
        selectDelay = 8 + (int)(pingComp.getValue() * 2);
        return timer.hasTimeElapsed(selectDelay * 50L);
    }

    private boolean handleZestTap(EntityLivingBase target, double distance) {
        int hitPattern = comboCount % 3;
        if (hitPattern == 2) {
            return timer.hasTimeElapsed(50L);
        }
        return true;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        
        if (mc.thePlayer.hurtTime > hurtTimeTicks) {
            wasHit = true;
            timer.reset();
        } else if (mc.thePlayer.hurtTime == 0) {
            wasHit = false;
        }
        hurtTimeTicks = mc.thePlayer.hurtTime;
        
        if (currentTarget != null) {
            if (currentTarget.hurtTime < targetHurtTime) {
                targetWasHurt = false;
            } else if (currentTarget.hurtTime > 0) {
                targetWasHurt = true;
            }
            targetHurtTime = currentTarget.hurtTime;
        }
        
        if (shouldBlock) {
            blockTicks--;
            if (blockTicks <= 0) {
                shouldBlock = false;
                restoreMomentum();
            }
        }
        
        if (firstHitTrap.getValue() && comboCount == 0 && currentTarget != null) {
            if (isMovingTowards(currentTarget, mc.thePlayer, 30.0) && currentTarget.isSprinting()) {
                shouldBlock = true;
                blockTicks = 3;
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (System.currentTimeMillis() - lastHitTime > 1000) {
            comboCount = 0;
        }
    }

    private void preserveMomentum() {
        try {
            KeepSprint ks = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (ks != null && !ks.isEnabled()) {
                ks.toggle();
                if (ks.slowdown != null) {
                    ks.slowdown.setValue(0);
                }
            }
        } catch (Exception ignored) {}
    }

    private void restoreMomentum() {
        try {
            KeepSprint ks = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (ks != null && ks.isEnabled()) {
                ks.toggle();
            }
        } catch (Exception ignored) {}
    }

    private int getBlockDuration() {
        switch (mode.getValue()) {
            case 0: return 3;
            case 1: return 10;
            case 2: return 20;
            case 3: return 2;
            case 4: return 2;
            case 5: return 8;
            case 6: return 1;
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

    public void onDisabled() {
        restoreMomentum();
        this.sprintState = false;
        this.shouldBlock = false;
        this.blockTicks = 0;
        this.comboCount = 0;
        this.wasHit = false;
        this.currentTarget = null;
    }

    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
