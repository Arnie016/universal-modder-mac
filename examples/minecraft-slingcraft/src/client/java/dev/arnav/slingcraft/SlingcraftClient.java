package dev.arnav.slingcraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BowItem;

public final class SlingcraftClient implements ClientModInitializer {
    private boolean wasUse,wasBreak,wasReload,nightfallView;
    private int fireTick;
    @Override public void onInitializeClient(){
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(net.minecraft.world.entity.EntityTypes.OAK_BOAT,PodRenderer::new);
        var cat=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("slingcraft","controls"));
        var use=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slingcraft.use",InputConstants.KEY_R,cat));
        var reset=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slingcraft.reset",InputConstants.KEY_N,cat));
        var next=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slingcraft.next",InputConstants.KEY_V,cat));
        var mine=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slingcraft.break",InputConstants.KEY_X,cat));
        var interact=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slingcraft.interact",InputConstants.KEY_G,cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc->{
            if(mc.player!=null&&mc.gui.screen()==null){
                var item=mc.player.getInventory().getItem(0);
                if(RaceHud.data(mc).getStringOr("racePhase","").equals("results"))mc.gui.setScreen(new RaceResultsScreen());
                else if(item.is(net.minecraft.world.item.Items.REDSTONE)&&item.getHoverName().getString().startsWith("Tune ·"))mc.gui.setScreen(new GarageScreen());
            }
            TrajectoryPreview.update(mc);
            boolean night=mc.player!=null&&Nightfall.isRifle(mc.player.getInventory().getItem(0));
            if(night&&!nightfallView)mc.options.setCameraType(CameraType.FIRST_PERSON);
            nightfallView=night;
            while(interact.consumeClick())if(night&&mc.gui.screen()==null)mc.player.connection.sendCommand("nightfall-action interact");
            boolean gun=mc.player!=null&&mc.gui.screen()==null&&Nightfall.isRifle(mc.player.getMainHandItem());
            if(gun){
                if(mc.options.keyAttack.isDown()&&fireTick++%5==0)mc.player.connection.sendCommand("nightfall-action fire");
                while(use.consumeClick())mc.player.connection.sendCommand("nightfall-action reload");
            }else fireTick=0;
            wasReload=gun&&use.isDown();
            boolean active=mc.player!=null&&mc.gui.screen()==null&&Slingcraft.bowKind(mc.player.getInventory().getItem(0))>=0;
            while(reset.consumeClick())if(active)mc.player.connection.sendCommand("sling reset");
            while(next.consumeClick())if(active)mc.player.connection.sendCommand("sling next");
            boolean u=active&&use.isDown(),b=active&&mine.isDown();
            if(u&&!wasUse)KeyMapping.click(KeyMappingHelper.getBoundKeyOf(mc.options.keyUse));
            if(b&&!wasBreak)KeyMapping.click(KeyMappingHelper.getBoundKeyOf(mc.options.keyAttack));
            if(u||wasUse)mc.options.keyUse.setDown(u);
            if(b||wasBreak)mc.options.keyAttack.setDown(b);
            wasUse=u;wasBreak=b;
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slingcraft","bird_bow"),(g,delta)->{
            var mc=Minecraft.getInstance();if(mc.player==null||mc.gui.hud.isHidden()||mc.gui.screen()!=null)return;
            if(Nightfall.isRifle(mc.player.getMainHandItem())){
                var data=mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                if(data!=null){var tag=data.copyTag();int y=g.guiHeight()-132;
                    g.fill(11,y-5,203,y+34,0xCB141520);
                    g.text(mc.font,"WARD-12 / CONTAINMENT",16,y,0xFFA1EDF0);
                    boolean reloading=tag.getLongOr("reloadUntil",0)>mc.level.getGameTime();
                    g.text(mc.font,reloading?"RELOADING…":tag.getIntOr("ammo",0)+" loaded  ·  "+tag.getIntOr("reserve",0)+" reserve",16,y+12,0xFFFFFFFF);
                    g.text(mc.font,"Click fire · R reload · G interact",16,y+24,0xFFC4C8D0);
                }return;
            }
            var vehicle=mc.player.getVehicle();
            if(vehicle!=null&&vehicle.hasCustomName()&&vehicle.getCustomName().getString().startsWith("Frostline")){
                int x=14,y=g.guiHeight()-148;var race=RaceHud.data(mc);
                boolean grid=race.getStringOr("racePhase","").equals("grid");
                long start=race.getLongOr("raceStart",mc.level.getGameTime());
                g.fill(x-5,y-5,x+230,y+51,0xC0162430);
                g.text(mc.font,grid?"GRID · START IN "+Math.max(0,(start-mc.level.getGameTime()+19)/20):"FROSTLINE · LAP "+race.getIntOr("raceLap",1)+" / 3",x,y,0xFF7DE9FF);
                g.text(mc.font,"POSITION "+race.getIntOr("racePlace",1)+" / 4   ·   "+RaceHud.time(grid?0:mc.level.getGameTime()-start),x,y+12,0xFFFFFFFF);
                g.text(mc.font,String.format(java.util.Locale.ROOT,"%.0f blocks/s",RaceHud.speed),x,y+24,0xFFFFFFFF);
                g.fill(x+85,y+25,x+185,y+31,0xFF334454);
                g.fill(x+85,y+25,x+85+(int)RaceHud.boost,y+31,0xFFFFC56E);
                g.text(mc.font,"W go · S brake · A/D steer · SPACE boost",x,y+40,0xFFCEDDE4);
                return;
            }
            int kind=Slingcraft.bowKind(mc.player.getMainHandItem());if(kind<0)return;
            TrajectoryPreview.render(g,mc);
            int width=g.guiWidth(),height=g.guiHeight(),x=12,y=44;
            g.fill(x-5,y-5,x+187,y+36,0xB81B2026);
            g.item(Slingcraft.birdItem(kind),x,y+3);
            g.text(mc.font,"SLING SIEGE",x+23,y,0xFFFFD878);
            g.text(mc.font,Slingcraft.birdName(kind),x+23,y+12,0xFFFFFFFF);
            g.text(mc.font,"1-3 birds   4-8 build   9 compass",x,y+25,0xFFE3E4E6);
            int by=height-63,cx=width/2;
            boolean drawing=mc.player.isUsingItem();
            g.centeredText(mc.font,drawing?"Release to launch":"Hold R / right-click   N retry   V next",cx,by-12,0xFFFFFFFF);
            float power=drawing?BowItem.getPowerForTime(mc.player.getTicksUsingItem()):0;
            g.fill(cx-48,by,cx+48,by+5,0xBB15171B);
            g.fill(cx-47,by+1,cx-47+(int)(94*power),by+4,power>.95?0xFFFFCA65:0xFFF17155);
        });
    }
}
