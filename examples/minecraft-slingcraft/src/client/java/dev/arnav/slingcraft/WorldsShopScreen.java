package dev.arnav.slingcraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;

public final class WorldsShopScreen extends Screen {
 private final Button[] buy=new Button[2];private static final String[] IDS={"incendiary","fieldkit"};private static final int[] COST={120,160};
 public WorldsShopScreen(){super(Component.literal("Expedition outfitter"));}
 private CompoundTag wallet(){var d=minecraft.player.getInventory().getItem(8).get(DataComponents.CUSTOM_DATA);return d==null?new CompoundTag():d.copyTag();}
 protected void init(){for(int i=0;i<2;i++){final int n=i;buy[i]=addRenderableWidget(Button.builder(Component.literal("Unlock"),b->minecraft.player.connection.sendCommand("worlds-shop "+IDS[n])).bounds(width/2+58,88+i*52,92,22).build());}addRenderableWidget(Button.builder(Component.literal("Back to worlds"),b->{minecraft.gui.setScreen(null);minecraft.player.connection.sendCommand("worlds-menu");}).bounds(width/2-90,202,180,20).build());}
 public boolean isPauseScreen(){return false;}
 public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){
  var tag=wallet();int credits=tag.getIntOr("credits",0);g.fillGradient(0,0,width,height,0xFA161C21,0xFA263939);g.centeredText(font,"EXPEDITION OUTFITTER",width/2,24,0xFFAAF2D1);g.centeredText(font,credits+" credits available · "+tag.getIntOr("xp",0)+" lifetime XP",width/2,44,0xFFFFFFFF);
  String[] names={"Incendiary rounds","Field supplies"},details={"Burn growths in three shots","Two golden apples each expedition"};
  for(int i=0;i<2;i++){int yy=83+i*52;g.fill(width/2-158,yy-7,width/2+158,yy+38,0xBB0A151B);g.text(font,names[i],width/2-148,yy,0xFFEBE6D7);g.text(font,details[i],width/2-148,yy+13,0xFFA3BABC);boolean owned=tag.getBooleanOr(IDS[i],false);buy[i].active=!owned&&credits>=COST[i];buy[i].setMessage(Component.literal(owned?"Owned":COST[i]+" credits"));}
  g.centeredText(font,"Earn credits by completing games. No real-money purchases.",width/2,186,0xFFB2C7C5);super.extractRenderState(g,x,y,dt);
 }
}
