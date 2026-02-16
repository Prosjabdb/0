package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.TickEvent;
import myau.events.AttackEvent;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Random;
import java.util.Objects;

public class AutoClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();
    
    public final IntProperty minCPS = new IntProperty("MinCPS", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("MaxCPS", 12, 1, 20);
    
    public final ModeProperty clickMode = new ModeProperty("Mode", 0, 
        new String[]{"NORMAL", "BURST", "JITTER", "LEGIT"});
    
    public final IntProperty burstCPS = new IntProperty("BurstCPS", 16, 12, 20, () -> clickMode.getValue() == 1);
    public final IntProperty burstDuration = new IntProperty("BurstDuration", 3, 1, 10, () -> clickMode.getValue() == 1);
    public final IntProperty burstPause = new IntProperty("BurstPause", 5, 1, 20, () -> clickMode.getValue() == 1);
    
    public final BooleanProperty blockHit = new BooleanProperty("BlockHit", false);
    public final FloatProperty blockHitChance = new FloatProperty("BlockHitChance", 30.0F, 0.0F, 100.0F, blockHit::getValue);
    public final FloatProperty blockHitMs = new FloatProperty("BlockHitMs", 50.0F, 25.0F, 150.0F, blockHit::getValue);
    
    public final BooleanProperty tickSync = new BooleanProperty("TickSync", true);
    public final BooleanProperty perfectSpacing = new BooleanProperty("PerfectSpacing", false);
    
    public final BooleanProperty weaponsOnly = new BooleanProperty("WeaponsOnly", true);
    public final BooleanProperty allowTools = new BooleanProperty("AllowTools", false, weaponsOnly::getValue);
    public final BooleanProperty breakBlocks = new BooleanProperty("BreakBlocks", true);
    public final FloatProperty range = new FloatProperty("Range", 3.0F, 3.0F, 6.0F, breakBlocks::getValue);
    public final FloatProperty hitBoxExpand = new FloatProperty("HitBoxExpand", 0.1F, 0.0F, 0.3F);

    private long nextClickTime = 0;
    private int burstTicks = 0;
    private int pauseTicks = 0;
    private boolean inBurst = false;
    private int currentCPS = 10;
    private int clickCounter = 0;
    private long lastClickTime = 0;
    private boolean shouldBlockHit = false;
    private int blockHitTicks = 0;
    private boolean wasBlocking = false;

    public AutoClicker() {
        super("AutoClicker", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.currentScreen != null) return;
        if (!canClick()) return;
        
        long currentTime = System.currentTimeMillis();
        
        switch (clickMode.getValue()) {
            case 1: handleBurstMode(); break;
            case 2: handleJitterMode(); break;
            case 3: handleLegitMode(currentTime); break;
            default: handleNormalMode(currentTime); break;
        }
        
        handleBlockHitting(currentTime);
        executeClicks(currentTime);
    }

    private void handleBurstMode() {
        if (inBurst) {
            burstTicks--;
            if (burstTicks <= 0) {
                inBurst = false;
                pauseTicks = burstPause.getValue();
                currentCPS = randomBetween(minCPS.getValue(), maxCPS.getValue());
            } else {
                currentCPS = burstCPS.getValue();
            }
        } else {
            pauseTicks--;
            if (pauseTicks <= 0) {
                inBurst = true;
                burstTicks = burstDuration.getValue();
                currentCPS = burstCPS.getValue();
            } else {
                currentCPS = randomBetween(minCPS.getValue(), maxCPS.getValue());
            }
        }
    }

    private void handleJitterMode() {
        if (System.currentTimeMillis() >= nextClickTime) {
            currentCPS = randomBetween(minCPS.getValue(), maxCPS.getValue());
        }
    }

    private void handleLegitMode(long currentTime) {
        int targetCPS = randomBetween(minCPS.getValue(), maxCPS.getValue());
        if (currentCPS < targetCPS) currentCPS++;
        else if (currentCPS > targetCPS) currentCPS--;
        if (random.nextInt(100) < 5) {
            currentCPS = Math.max(1, currentCPS - 3);
        }
    }

    private void handleNormalMode(long currentTime) {
        currentCPS = randomBetween(minCPS.getValue(), maxCPS.getValue());
    }

    private void handleBlockHitting(long currentTime) {
        if (!blockHit.getValue() || !isHoldingSword()) return;
        
        if (blockHitTicks > 0) {
            blockHitTicks--;
            if (blockHitTicks <= 0 && wasBlocking) {
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                wasBlocking = false;
            }
        } else if (shouldBlockHit) {
            if (mc.gameSettings.keyBindAttack.isKeyDown()) {
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                wasBlocking = true;
                blockHitTicks = Math.max(1, (int)((float)blockHitMs.getValue() / 50f));
                shouldBlockHit = false;
            }
        }
        
        if (System.currentTimeMillis() >= nextClickTime && mc.gameSettings.keyBindAttack.isKeyDown()) {
            if (random.nextFloat() * 100 < (float)blockHitChance.getValue()) {
                shouldBlockHit = true;
            }
        }
    }

    private void executeClicks(long currentTime) {
        if (!mc.gameSettings.keyBindAttack.isKeyDown()) return;
        if (mc.thePlayer.isUsingItem() && !shouldBlockHit) return;
        
        if (tickSync.getValue()) {
            long tickBoundary = (currentTime / 50) * 50;
            if (currentTime - tickBoundary > 10) return;
        }
        
        long clickInterval = 1000L / currentCPS;
        
        if (perfectSpacing.getValue() && clickMode.getValue() != 1) {
            clickInterval = 1000L / currentCPS;
        } else {
            clickInterval += random.nextInt(20) - 10;
        }
        
        if (currentTime >= nextClickTime) {
            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
            
            if (shouldBlockHit && !wasBlocking) {
                nextClickTime = currentTime + clickInterval + (long)(float)blockHitMs.getValue();
            } else {
                nextClickTime = currentTime + clickInterval;
            }
            
            clickCounter++;
            lastClickTime = currentTime;
        }
    }

    private boolean canClick() {
        if (weaponsOnly.getValue()) {
            if (!isHoldingWeapon()) {
                if (!allowTools.getValue() || !isHoldingTool()) {
                    return false;
                }
            }
        }
        
        if (breakBlocks.getValue() && isBreakingBlock()) {
            if (!hasValidTarget()) {
                GameType gameType = mc.playerController.getCurrentGameType();
                return gameType != GameType.SURVIVAL && gameType != GameType.CREATIVE;
            }
        }
        
        return true;
    }

    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    private boolean hasValidTarget() {
        return mc.theWorld.loadedEntityList.stream()
            .filter(e -> e instanceof EntityPlayer)
            .map(e -> (EntityPlayer) e)
            .anyMatch(this::isValidTarget);
    }

    private boolean isValidTarget(EntityPlayer entity) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity) return false;
        if (entity == mc.getRenderViewEntity() || entity == mc.getRenderViewEntity().ridingEntity) return false;
        if (entity.deathTime > 0) return false;
        
        float expand = entity.getCollisionBorderSize() + (float)hitBoxExpand.getValue();
        return RotationUtil.rayTrace(
            entity.getEntityBoundingBox().expand(expand, expand, expand),
            mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch,
            (float)range.getValue()
        ) != null;
    }

    private boolean isHoldingWeapon() {
        return mc.thePlayer.getHeldItem() != null && 
               (mc.thePlayer.getHeldItem().getItem() instanceof ItemSword ||
                ItemUtil.hasRawUnbreakingEnchant());
    }

    private boolean isHoldingTool() {
        return ItemUtil.isHoldingTool();
    }

    private boolean isHoldingSword() {
        return mc.thePlayer.getHeldItem() != null && 
               mc.thePlayer.getHeldItem().getItem() instanceof ItemSword;
    }

    private int randomBetween(int min, int max) {
        if (min == max) return min;
        return min + random.nextInt(max - min + 1);
    }

    @EventTarget(Priority.LOWEST)
    public void onClick(LeftClickMouseEvent event) {
        if (!isEnabled() || event.isCancelled()) return;
        if (!event.isCancelled()) {
            nextClickTime = System.currentTimeMillis() + (1000L / currentCPS);
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (clickMode.getValue() == 1 && inBurst) {
            burstTicks = Math.min(burstTicks + 1, burstDuration.getValue());
        }
    }

    public void onEnabled() {
        nextClickTime = System.currentTimeMillis();
        burstTicks = 0;
        pauseTicks = 0;
        inBurst = false;
        currentCPS = minCPS.getValue();
        clickCounter = 0;
        shouldBlockHit = false;
        blockHitTicks = 0;
        wasBlocking = false;
    }

    public void onDisabled() {
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
    }

    public void verifyValue(String mode) {
        if (minCPS.getName().equals(mode)) {
            if (minCPS.getValue() > maxCPS.getValue()) {
                maxCPS.setValue(minCPS.getValue());
            }
        } else if (maxCPS.getName().equals(mode)) {
            if (minCPS.getValue() > maxCPS.getValue()) {
                minCPS.setValue(maxCPS.getValue());
            }
        }
    }

    public String[] getSuffix() {
        String modeStr = clickMode.toString();
        String cpsStr = minCPS.getValue().equals(maxCPS.getValue()) 
            ? minCPS.getValue().toString() 
            : String.format("%d-%d", minCPS.getValue(), maxCPS.getValue());
        return new String[]{modeStr, cpsStr};
    }
}
