package dev.arnav.slingcraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;

/** Compact tuning board with an original top view of the selected pod. */
public final class GarageScreen extends Screen {
 private int selected;
 public GarageScreen(){super(Component.literal("Frostline garage"));}
 protected void init(){
  var data=minecraft.player.getInventory().getItem(0).get(DataComponents.CUSTOM_DATA);
  if(data!=null)selected=data.copyTag().getIntOr("setup",0);
  String[] names={"GRIP · Easy corners","BALANCED · All-round","SPEED · Faster straights"};
  int x=width/2+8,w=Math.min(218,width/2-24),y=Math.max(72,height/2-58);
  for(int i=0;i<3;i++){final int n=i;addRenderableWidget(Button.builder(Component.literal(names[i]),b->{selected=n;minecraft.player.connection.sendCommand("frostline-menu setup "+n);}).bounds(x,y+i*25,w,21).build());}
  addRenderableWidget(Button.builder(Component.literal("READY — RACE"),b->minecraft.player.connection.sendCommand("frostline-menu ready")).bounds(x,y+83,w,23).build());
  addRenderableWidget(Button.builder(Component.literal("Return to garden"),b->minecraft.player.connection.sendCommand("frostline-menu leave")).bounds(x,y+112,w,20).build());
 }
 public boolean isPauseScreen(){return false;}
 public boolean shouldCloseOnEsc(){return false;}
 public void tick(){if(minecraft.player==null||!minecraft.player.getInventory().getItem(0).is(net.minecraft.world.item.Items.REDSTONE))minecraft.gui.setScreen(null);}
 public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
  g.fillGradient(0,0,width,height,0xFF0B1924,0xFF172E38);
  for(int x=0;x<width;x+=24)g.verticalLine(x,59,height-20,0x1123C8D9);
  for(int y=60;y<height-20;y+=24)g.horizontalLine(0,width,y,0x1123C8D9);
  g.text(font,"FROSTLINE / GARAGE",18,17,0xFF86EFF3);
  g.text(font,"3 laps. 4 minutes. Choose handling, then Ready.",18,34,0xFFCEDDE4);
  var data=minecraft.player.getInventory().getItem(0).get(DataComponents.CUSTOM_DATA);
  long until=data==null?minecraft.level.getGameTime():data.copyTag().getLongOr("garageUntil",minecraft.level.getGameTime());
  long secs=Math.max(0,(until-minecraft.level.getGameTime()+19)/20);
  g.text(font,"AUTO START  "+secs/60+":"+String.format(java.util.Locale.ROOT,"%02d",secs%60)+"   ·   3 rivals / 3 laps",18,49,0xFFFFC478);
  int cx=width/4,cy=Math.max(102,height/2-10);
  int accent=new int[]{0xFF79E7E9,0xFFFFC478,0xFFFF8883}[selected];
  g.fill(cx-40,cy-37,cx-22,cy+26,0xFF80969D);g.fill(cx+22,cy-37,cx+40,cy+26,0xFF80969D);
  g.fill(cx-38,cy-42,cx-24,cy-34,accent);g.fill(cx+24,cy-42,cx+38,cy-34,accent);
  g.fill(cx-28,cy-8,cx+28,cy+1,0xFFE1E6D9);g.fill(cx-12,cy-27,cx+12,cy+26,0xFFE1E6D9);
  g.fill(cx-8,cy-19,cx+8,cy+2,0xFF264956);g.fill(cx-6,cy+6,cx+6,cy+22,accent);
  g.fill(cx-47,cy+19,cx-25,cy+27,0xFFD7DED4);g.fill(cx+25,cy+19,cx+47,cy+27,0xFFD7DED4);
  double pulse=(Math.sin(System.nanoTime()/180000000.0)+1)*3;
  g.fill(cx-35,cy+27,cx-27,cy+32+(int)pulse,accent);g.fill(cx+27,cy+27,cx+35,cy+32+(int)pulse,accent);
  g.centeredText(font,new String[]{"GRIP / CANOPY","BALANCED / TOURING","SPEED / GLACIER"}[selected],cx,cy+44,accent);
  int barY=cy+59;
  for(int i=0;i<2;i++){g.text(font,i==0?"TURN":"PACE",Math.max(18,cx-62),barY+i*14,0xFFCBDCE4);g.fill(cx-19,barY+i*14,cx+55,barY+5+i*14,0xFF344850);int value=i==0?new int[]{74,59,43}[selected]:new int[]{49,61,74}[selected];g.fill(cx-19,barY+i*14,cx-19+value,barY+5+i*14,accent);}
  g.text(font,"W accelerate · S brake · A/D steer · SPACE boost",18,height-15,0xFFBCD1D9);
  super.extractRenderState(g,mx,my,dt);
 }
}
