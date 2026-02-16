package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
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
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, 
        new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET", "SYNC_HIT"});
    
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("OnlyGround", true);
    public final FloatProperty pingCompensation = new FloatProperty("PingComp", 0.0F, 0.0F, 5.0F);
    
    private boolean shouldSprintReset = false;
    private EntityLivingBase target = null;
    private boolean resetNextTick = false;
    private int sprintResetTicks = 0;
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
            hasResetThisCombo = false;
            
            if (this.mode.getValue() == 1 && canSprintReset()) {
                performSprintReset();
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) return;
        
        if (this.mode.getValue() == 5 && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() == C02PacketUseEntity.Action.ATTACK) {
                resetNextTick = true;
                sprintResetTicks = 1 + Math.round(pingCompensation.getValue());
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) return;
        
        if (resetNextTick) {
            sprintResetTicks--;
            if (sprintResetTicks <= 0) {
                performPacketSprintReset(1);
                resetNextTick = false;
            }
            return;
        }
        
        if (this.mode.getValue() == 1) return;
        
        EntityLivingBase entity = getTargetEntity();
        if (entity == null) return;
        
        if (this.intelligent.getValue() && !isOpponentVulnerable(entity)) return;
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) return;
        
        if (entity.hurtTime == 10 && !hasResetThisCombo) {
            executeModeLogic();
            hasResetThisCombo = true;
        }
        
        if (entity.hurtTime == 0) {
            hasResetThisCombo = false;
        }
    }

    private void executeModeLogic() {
        switch (this.mode.getValue()) {
            case 0: performLegitReset(); break;
            case 2: performLessPacketReset(); break;
            case 3: performPacketSprintReset(1); break;
            case 4: performPacketSprintReset(2); break;
            case 5: break;
        }
    }

    private void performLegitReset() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
        }
    }

    private void performLessPacketReset() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
        mc.getNetHandler().addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
        );
        mc.thePlayer.setSprinting(true);
    }

    private void performPacketSprintReset(int count) {
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
        mc.thePlayer.sprintingTicksLeft = 0;
    }

    private EntityLivingBase getTargetEntity() {
        if (this.target != null && !this.target.isDead) return this.target;
        
        if (mc.objectMouseOver != null && 
            mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY &&
            mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            return (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        return null;
    }

    private boolean isOpponentVulnerable(EntityLivingBase entity) {
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
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
        if (this.target != null && (this.target.isDead || this.target.getDistanceToEntity(mc.thePlayer) > 6.0)) {
            this.target = null;
            hasResetThisCombo = false;
        }
        if (this.target != null && this.target.hurtTime == 0) {
            hasResetThisCombo = false;
        }
    }

    public void onDisabled() {
        this.target = null;
        this.shouldSprintReset = false;
        this.resetNextTick = false;
        this.hasResetThisCombo = false;
    }

    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
