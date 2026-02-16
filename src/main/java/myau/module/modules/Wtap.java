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
    
    // Core timing properties (kept as requested)
    public final FloatProperty delay = new FloatProperty("Delay", 2.0F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.0F, 0.5F, 3.0F);
    
    // State tracking
    private boolean resetting = false;
    private int delayTicks = 0;
    private int durationTicks = 0;
    private boolean shouldSprint = false;
    private int ticksSinceAttack = 0;
    
    // Hit tracking for advanced mechanics
    private long lastAttackTime = 0;
    private int comboCount = 0;
    
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
                // Check if we can attack (not on cooldown)
                if (mc.thePlayer.getCooledAttackStrength(0.0f) < 0.9f) {
                    return;
                }
                
                // Prevent double triggers
                if (resetting) return;
                
                // Start W-Tap sequence
                startWtap();
            }
        }
    }

    private void startWtap() {
        resetting = true;
        shouldSprint = mc.thePlayer.isSprinting();
        
        // Convert float seconds to ticks (20 TPS = 50ms per tick)
        // Delay: time before releasing W
        delayTicks = Math.round(delay.getValue() * 20.0f / 10.0f); // Scale down for tick precision
        // Duration: how long to hold W release
        durationTicks = Math.max(1, Math.round(duration.getValue() * 20.0f / 10.0f));
        
        // Immediate sprint reset for optimal KB
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @EventTarget(Priority.HIGH)
    public void onMoveInput(MoveInputEvent event) {
        if (!resetting) return;
        
        // Handle delay phase (waiting before release)
        if (delayTicks > 0) {
            delayTicks--;
            // Keep normal movement during delay
            return;
        }
        
        // Handle release phase
        if (durationTicks > 0) {
            durationTicks--;
            
            // Stop forward movement to reset sprint
            if (event.getForward() > 0) {
                event.setForward(0.0f);
            }
            
            // Ensure sprint is disabled
            mc.thePlayer.setSprinting(false);
            mc.gameSettings.keyBindSprint.pressed = false;
        } else {
            // Reset complete
            finishWtap();
        }
    }

    private void finishWtap() {
        resetting = false;
        delayTicks = 0;
        durationTicks = 0;
        
        // Restore sprint if we were sprinting before
        if (shouldSprint && canTrigger()) {
            mc.thePlayer.setSprinting(true);
            mc.gameSettings.keyBindSprint.pressed = true;
        }
        shouldSprint = false;
    }

    private boolean canTrigger() {
        // Basic movement checks
        if (mc.thePlayer.movementInput.moveForward < 0.8f) return false;
        if (mc.thePlayer.isCollidedHorizontally) return false;
        
        // Hunger check (6 or below = no sprint)
        if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6 && !mc.thePlayer.capabilities.allowFlying) {
            return false;
        }
        
        // Status checks
        if (mc.thePlayer.isUsingItem()) return false;
        if (mc.thePlayer.isPotionActive(Potion.blindness)) return false;
        
        // Must be on ground for consistent KB
        if (!mc.thePlayer.onGround) return false;
        
        return true;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        finishWtap();
    }
}
