package dev.arnav.slingcraft.mixin;

import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.client.renderer.entity.state.PigRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PigRenderer.class)
public abstract class PigRendererMixin {
    @Inject(
        method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/PigRenderState;)Lnet/minecraft/resources/Identifier;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void slingcraft$greenPig(PigRenderState state, CallbackInfoReturnable<Identifier> cir) {
        cir.setReturnValue(Identifier.fromNamespaceAndPath("slingcraft", "textures/entity/green_pig.png"));
    }
}
