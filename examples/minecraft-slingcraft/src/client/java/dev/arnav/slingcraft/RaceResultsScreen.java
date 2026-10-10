package dev.arnav.slingcraft;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;

/** Server-owned results, with a short automatic replay and explicit exits. */
public final class RaceResultsScreen extends Screen {
    public RaceResultsScreen(){super(Component.literal("Frostline results"));}
    protected void init(){
        int w=Math.min(370,width-28),x=(width-w)/2,y=Math.max(8,(height-212)/2);
        int button=(w-16)/3;
        String[] labels={"Race again","Change setup","Garden"};
        String[] actions={"replay","setup","leave"};
        for(int i=0;i<3;i++){
            String action=actions[i];
            addRenderableWidget(Button.builder(Component.literal(labels[i]),b->{
                b.active=false;minecraft.player.connection.sendCommand("frostline-result "+action);
            }).bounds(x+i*(button+8),y+173,button,23).build());
        }
    }
    public boolean isPauseScreen(){return false;}
    public boolean shouldCloseOnEsc(){return false;}
    public void tick(){if(!RaceHud.data(minecraft).getStringOr("racePhase","").equals("results"))minecraft.gui.setScreen(null);}
    public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
        CompoundTag tag=RaceHud.data(minecraft);
        boolean completed=tag.getBooleanOr("raceCompleted",true);
        int w=Math.min(370,width-28),x=(width-w)/2,y=Math.max(8,(height-212)/2),middle=x+w/2;
        g.fill(0,0,width,height,0xAA061018);
        g.fill(x-10,y-6,x+w+10,y+205,0xF0142732);
        g.fill(x-10,y-6,x+w+10,y-4,0xFF86EFF3);
        g.text(font,completed?"FROSTLINE / FINISH":"FROSTLINE / TIME LIMIT",x,y+7,0xFF86EFF3);
        g.text(font,completed?"POSITION  "+tag.getIntOr("racePlace",4)+" / 4":"DID NOT FINISH",x,y+26,0xFFFFFFFF);
        g.text(font,completed?"THREE LAPS COMPLETE":"Complete all three laps within four minutes.",x,y+42,0xFFBACED7);
        g.horizontalLine(x,x+w,y+58,0xFF36515F);
        g.text(font,"RACE TIME",x,y+72,0xFFBACED7);
        g.text(font,"BEST LAP",middle+8,y+72,0xFFBACED7);
        g.text(font,RaceHud.time(tag.getLongOr("raceResultTime",0)),x,y+87,0xFFFFFFFF);
        long best=tag.getLongOr("raceResultBest",0);
        g.text(font,best>0?RaceHud.time(best):"No completed lap",middle+8,y+87,0xFFFFFFFF);
        boolean saved=tag.getIntOr("raceCredits",0)>0;
        g.text(font,saved?"+100 XP  ·  +100 CREDITS":completed?"Rewards not saved. Check world storage.":"No completion credits for this run.",x,y+110,saved?0xFFFFC478:0xFFFFA5A5);
        g.text(font,tag.getBooleanOr("raceNewBest",false)?"PERSONAL BEST LAP":completed?"FINISHER / EVERY LAP COUNTS":"TRY GRIP SETUP FOR EASIER CORNERS",x,y+128,0xFF86EFF3);
        long left=minecraft.level==null?0:Math.max(0,(tag.getLongOr("raceResultUntil",0)-minecraft.level.getGameTime()+19)/20);
        g.text(font,"Next grid in "+left+"s  ·  Choose an option to leave",x,y+152,0xFFBACED7);
        super.extractRenderState(g,mx,my,dt);
    }
}
