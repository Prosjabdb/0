package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.potion.Potion;

public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final FloatProperty delay = new FloatProperty("Delay", 2.0F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.0F, 0.5F, 3.0F);
    
    private boolean resetting = false;
    private int delayTicks = 0;
    private int durationTicks = 0;
    private boolean shouldSprint = false;

    public Wtap() {
        super("WTap", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            
            if (packet.getAction() == Action.ATTACK && canTrigger()) {
                if (resetting) return;
                startWtap();
            }
        }
    }

    private void startWtap() {
        resetting = true;
        shouldSprint = mc.thePlayer.isSprinting();
        delayTicks = Math.round(delay.getValue() * 2);
        durationTicks = Math.max(1, Math.round(duration.getValue() * 2));
        
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @EventTarget(Priority.HIGH)
    public void onMoveInput(MoveInputEvent event) {
        if (!resetting) return;
        
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        
        if (durationTicks > 0) {
            durationTicks--;
            mc.thePlayer.setSprinting(false);
        } else {
            finishWtap();
        }
    }

    private void finishWtap() {
        resetting = false;
        delayTicks = 0;
        durationTicks = 0;
        
        if (shouldSprint && canTrigger()) {
            mc.thePlayer.setSprinting(true);
        }
        shouldSprint = false;
    }

    private boolean canTrigger() {
        if (mc.thePlayer.movementInput.moveForward < 0.8f) return false;
        if (mc.thePlayer.isCollidedHorizontally) return false;
        if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6 && !mc.thePlayer.capabilities.allowFlying) return false;
        if (mc.thePlayer.isUsingItem()) return false;
        if (mc.thePlayer.isPotionActive(Potion.blindness)) return false;
        if (!mc.thePlayer.onGround) return false;
        return true;
    }

    public void onDisabled() {
        resetting = false;
        delayTicks = 0;
        durationTicks = 0;
        shouldSprint = false;
    }
}
