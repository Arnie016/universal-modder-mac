package dev.arnav.slingcraft.mixin;
import net.minecraft.world.entity.Display;
import com.mojang.math.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Display.class)
public interface DisplayAccess {
 @Invoker("setTransformation") void slingcraft$transform(Transformation value);
}
