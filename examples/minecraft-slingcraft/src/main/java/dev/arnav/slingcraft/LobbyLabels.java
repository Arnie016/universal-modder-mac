package dev.arnav.slingcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.util.Brightness;
import com.mojang.math.Transformation;
import org.joml.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LobbyLabels {
 static void ensure(ServerLevel world,BlockPos origin){
  for(int i=0;i<LobbyGate.ALL.length;i++){
   var gate=LobbyGate.ALL[i];var at=gate.point(origin,0,12.5,2);
   UUID id=UUID.nameUUIDFromBytes(("worlds-garden-v4-label-"+origin.toShortString()+"-"+i).getBytes(StandardCharsets.UTF_8));
   if(world.getEntity(id)!=null)continue;
   var label=EntityTypes.TEXT_DISPLAY.create(world,EntitySpawnReason.COMMAND);if(label==null)continue;
   label.setUUID(id);label.setPos(at);label.addTag("worlds_garden_label");
   label.setText(Component.literal(gate.title()).withColor(gate.color()).append(Component.literal("\n"+gate.detail()+"\nWalk through to play").withColor(0xe8e7dc)));
   label.setLineWidth(280);label.setBackgroundColor(0xb018202a);label.setFlags(Display.TextDisplay.FLAG_SHADOW);
   label.setBillboardConstraints(Display.BillboardConstraints.CENTER);label.setBrightnessOverride(new Brightness(15,15));label.setViewRange(1.5f);
   label.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(2.2f),new Quaternionf()));world.addFreshEntity(label);
  }
 }
}
