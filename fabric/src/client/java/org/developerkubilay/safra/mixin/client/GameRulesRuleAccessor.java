package org.developerkubilay.safra.mixin.client;

import net.minecraft.world.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRules.Rule.class)
public interface GameRulesRuleAccessor {
    @Invoker("deserialize")
    void safra$deserialize(String value);
}
