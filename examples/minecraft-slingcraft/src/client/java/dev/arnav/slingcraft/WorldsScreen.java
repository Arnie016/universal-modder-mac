package dev.arnav.slingcraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The compass is a player-facing menu. No operator commands or building knowledge are needed. */
public final class WorldsScreen extends Screen {
    public WorldsScreen(){super(Component.literal("Choose your world"));}
    protected void init(){
        String[] labels={"SLING SIEGE / Destruction","FROSTLINE GP / Racing","BLOOM / Fungal expedition"};
        int top=Math.max(66,height/2-42);
        for(int i=0;i<3;i++){final int mode=i;addRenderableWidget(Button.builder(Component.literal(labels[i]),b->{minecraft.player.connection.sendCommand("worlds-menu "+mode);minecraft.gui.setScreen(null);}).bounds(width/2-145,top+i*39,290,23).build());}
        addRenderableWidget(Button.builder(Component.literal("Upgrade shop"),b->minecraft.gui.setScreen(new WorldsShopScreen())).bounds(width/2-145,top+124,140,21).build());
        addRenderableWidget(Button.builder(Component.literal("Garden"),b->minecraft.gui.setScreen(null)).bounds(width/2+5,top+124,140,21).build());
    }
    public boolean isPauseScreen(){return false;}
    public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){
        g.fillGradient(0,0,width,height,0xF50D1925,0xF5213539);
        g.centeredText(font,"THE WORLDS GARDEN",width/2,22,0xFFAFEFE8);
        g.centeredText(font,"Choose a game. Every round starts and resets automatically.",width/2,40,0xFFDAE3DF);
        String[] details={"5 birds · 3 forts · break the supports","Tune a pod · 3 rivals · 3 timed laps","Burn the roots · recover a specimen · escape"};
        int top=Math.max(66,height/2-42);
        for(int i=0;i<3;i++)g.centeredText(font,details[i],width/2,top+25+i*39,0xFFACBDC2);
        super.extractRenderState(g,x,y,dt);
    }
}
