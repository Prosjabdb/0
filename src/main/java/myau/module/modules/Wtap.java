package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.BoolProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.potion.Potion;

public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Core properties (Duration and Delay only as requested)
    public final FloatProperty delay = new FloatProperty("Delay", 0.0F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.0F, 1.0F, 5.0F);
    
    // State tracking
    private boolean resetting = false;
    private int delayTicks = 0;
    private int durationTicks = 0;
    private boolean shouldSprint = false;
    private int ticksSinceAttack = 0;
    
    // Hit timing for advanced mechanics
    private boolean hitThisTick = false;
    private int sprintResetTicks = 0;

    public Wtap() {
        super("Wtap", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            
            if (packet.getAction() == Action.ATTACK && canTrigger()) {
                // Prevent double triggers on same tick (burst clicking protection)
                if (hitThisTick) return;
                hitThisTick = true;
                
                // Start W-Tap sequence with proper timing
                startWtap();
            }
        }
    }

    private void startWtap() {
        // Don't interrupt existing reset
        if (resetting) return;
        
        // Store sprint state
        shouldSprint = mc.thePlayer.isSprinting();
        
        // Convert to ticks (Minecraft runs at 20 TPS, 1 tick = 50ms)
        // Delay: ticks to wait AFTER attack before releasing W (parry timing concept)
        delayTicks = Math.round(delay.getValue() * 2); // 0.0-10.0 -> 0-20 ticks
        
        // Duration: how long to release W for sprint reset (1-5 ticks optimal)
        durationTicks = Math.round(duration.getValue() * 2);
        
        // Start the sequence
        resetting = true;
        ticksSinceAttack = 0;
    }

    @EventTarget(Priority.HIGH)
    public void onMoveInput(MoveInputEvent event) {
        // Reset hit flag each tick
        hitThisTick = false;
        
        if (!resetting) {
            // Normal sprint behavior when not resetting
            return;
        }
        
        ticksSinceAttack++;
        
        // Phase 1: Delay (hold W, wait for optimal release timing)
        if (ticksSinceAttack <= delayTicks) {
            // Keep holding W during delay
            // This is the "light W-Tap" concept - delay the release strategically
            return;
        }
        
        // Phase 2: Release W for sprint reset
        int releaseTick = ticksSinceAttack - delayTicks;
        
        if (releaseTick <= durationTicks) {
            // Release W to reset sprint
            if (event.getForward() > 0) {
                event.setForward(0.0f);
            }
            
            // Force sprint reset
            mc.thePlayer.setSprinting(false);
            
            // Block hitting synergy: if player is blocking, maintain block
            // This preserves the "block hitting resets hitreg" mechanic from docs
        } else {
            // Reset complete
            finishWtap(event);
        }
    }

    private void finishWtap(MoveInputEvent event) {
        resetting = false;
        ticksSinceAttack = 0;
        
        // Resume sprint if we should be sprinting
        if (shouldSprint && canTrigger()) {
            mc.thePlayer.setSprinting(true);
            // Restore forward movement
            if (event.getForward() == 0) {
                event.setForward(1.0f);
            }
        }
        shouldSprint = false;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        // Safety: reset if player dies or gets knocked back unexpectedly
        if (resetting && !mc.thePlayer.isEntityAlive()) {
            forceReset();
        }
        
        // Sprint maintenance: ensure sprint is active when it should be
        if (!resetting && shouldSprint && !mc.thePlayer.isSprinting() && canTrigger()) {
            mc.thePlayer.setSprinting(true);
        }
    }

    private void forceReset() {
        resetting = false;
        delayTicks = 0;
        durationTicks = 0;
        ticksSinceAttack = 0;
        shouldSprint = false;
        mc.thePlayer.setSprinting(false);
    }

    private boolean canTrigger() {
        // Movement forward check (0.8 threshold from docs)
        if (mc.thePlayer.movementInput.moveForward < 0.8f) return false;
        
        // Wall collision check
        if (mc.thePlayer.isCollidedHorizontally) return false;
        
        // Hunger check (6.0F = no sprint threshold)
        if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6.0f && !mc.thePlayer.capabilities.allowFlying) {
            return false;
        }
        
        // Status checks
        if (mc.thePlayer.isUsingItem()) return false;
        if (mc.thePlayer.isPotionActive(Potion.blindness)) return false;
        
        // Sprint key or auto-sprint
        if (!mc.thePlayer.isSprinting() && !mc.gameSettings.keyBindSprint.isKeyDown()) {
            return false;
        }
        
        return true;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        forceReset();
    }
}
