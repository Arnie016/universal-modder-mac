package dev.arnav.slingcraft;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
public final class RaceHud {
 public static double speed,boost;
 public static CompoundTag data(Minecraft mc){
  if(mc.player==null)return new CompoundTag();
  var item=mc.player.getInventory().getItem(8);var value=item.get(DataComponents.CUSTOM_DATA);
  return ModeProfiles.isCompass(item)&&value!=null?value.copyTag():new CompoundTag();
 }
 public static String time(long ticks){return String.format(java.util.Locale.ROOT,"%d:%05.2f",Math.max(0,ticks)/1200,(Math.max(0,ticks)%1200)/20.0);}
}
