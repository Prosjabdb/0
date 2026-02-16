package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Modes optimized for different ping ranges per docs
    public final ModeProperty mode = new ModeProperty("Mode", 0, 
        new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET", "SYNC_HIT"});
    
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("OnlyGround", true);
    
    // New: Ping compensation for high-ping players (150ms+)
    public final FloatProperty pingCompensation = new FloatProperty("PingComp", 0.0F, 0.0F, 5.0F);
    
    // State tracking
    private boolean shouldSprintReset = false;
    private EntityLivingBase target = null;
    private int ticksSinceAttack = 0;
    private boolean resetNextTick = false;
    private int sprintResetTicks = 0;
    
    // Hit timing tracking
    private long lastAttackTime = 0;
    private boolean hasResetThisCombo = false;

    public MoreKB() {
        super("MoreKB", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;
        
        Entity targetEntity = event.getTarget();
        if (targetEntity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) targetEntity;
            this.ticksSinceAttack = 0;
            this.hasResetThisCombo = false;
            
            // LEGIT_FAST mode: immediate sprint reset on attack
            if (this.mode.getValue() == 1 && canSprintReset()) {
                performSprintReset();
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != myau.event.types.EventType.SEND) return;
        
        // SYNC_HIT mode: sync sprint reset with attack packet for optimal timing
        if (this.mode.getValue() == 5 && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() == C02PacketUseEntity.Action.ATTACK) {
                // Queue reset for next tick to ensure packet order
                resetNextTick = true;
                sprintResetTicks = 1 + Math.round(pingCompensation.getValue());
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) return;
        
        // Handle SYNC_HIT mode delayed reset
        if (resetNextTick) {
            sprintResetTicks--;
            if (sprintResetTicks <= 0) {
                performPacketSprintReset(1); // Single packet reset synced with hit
                resetNextTick = false;
            }
            return;
        }
        
        // LEGIT_FAST already handled in onAttack
        if (this.mode.getValue() == 1) return;
        
        // Validate target
        EntityLivingBase entity = getTargetEntity();
        if (entity == null) return;
        
        // Intelligent mode: check if opponent is looking away (vulnerable)
        if (this.intelligent.getValue() && !isOpponentVulnerable(entity)) {
            return;
        }
        
        // Ground check per docs: sprint resets are more consistent on ground
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) {
            return;
        }
        
        // Hit timing: hurtTime == 10 means just damaged, optimal for KB manipulation
        // Per docs: this is when KB is applied, so we reset sprint to maximize it
        if (entity.hurtTime == 10 && !hasResetThisCombo) {
            executeModeLogic();
            hasResetThisCombo = true;
        }
        
        // Reset combo tracking when hurtTime resets
        if (entity.hurtTime == 0) {
            hasResetThisCombo = false;
        }
    }

    private void executeModeLogic() {
        switch (this.mode.getValue()) {
            case 0: // LEGIT - Toggle sprint client-side only
                performLegitReset();
                break;
            case 2: // LESS_PACKET - Minimal packet spam, client toggle + packet
                performLessPacketReset();
                break;
            case 3: // PACKET - Standard packet sprint reset
                performPacketSprintReset(1);
                break;
            case 4: // DOUBLE_PACKET - Aggressive double reset for high KB
                performPacketSprintReset(2);
                break;
            case 5: // SYNC_HIT - Handled in onPacket
                break;
        }
    }

    private void performLegitReset() {
        // Mimic legit W-tap: stop sprint, immediately resume
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
        }
    }

    private void performLessPacketReset() {
        // Stop sprint client-side, send start packet
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
        mc.getNetHandler().addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
        );
        mc.thePlayer.setSprinting(true);
    }

    private void performPacketSprintReset(int count) {
        // Per docs: sprint reset sends STOP then START to server
        // This resets sprint server-side for full KB
        for (int i = 0; i < count; i++) {
            mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
            );
        }
        mc.thePlayer.setSprinting(true);
    }

    private void performSprintReset() {
        // Universal sprint reset used by LEGIT_FAST
        mc.thePlayer.sprintingTicksLeft = 0;
    }

    private EntityLivingBase getTargetEntity() {
        // Prioritize last attacked target
        if (this.target != null && !this.target.isDead) {
            return this.target;
        }
        
        // Fallback to crosshair target
        if (mc.objectMouseOver != null && 
            mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY &&
            mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            return (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        
        return null;
    }

    private boolean isOpponentVulnerable(EntityLivingBase entity) {
        // Calculate angle difference to check if opponent is looking away
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
        
        // > 120 degrees means opponent is looking away (per original logic)
        // But we want to reset when they're vulnerable, so invert: return true if diffY <= 120
        return diffY <= 120.0F;
    }

    private boolean canSprintReset() {
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) return false;
        return isMoving();
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        // Cleanup if target dies or disappears
        if (this.target != null && (this.target.isDead || this.target.getDistanceToEntity(mc.thePlayer) > 6.0)) {
            this.target = null;
            hasResetThisCombo = false;
        }
        
        // Reset hasReset flag when appropriate
        if (this.target != null && this.target.hurtTime == 0) {
            hasResetThisCombo = false;
        }
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.target = null;
        this.shouldSprintReset = false;
        this.resetNextTick = false;
        this.hasResetThisCombo = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeAsString()};
    }
}
