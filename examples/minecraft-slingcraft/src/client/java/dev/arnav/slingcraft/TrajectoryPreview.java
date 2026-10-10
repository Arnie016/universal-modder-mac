package dev.arnav.slingcraft;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Side-on estimate of the central bird's flight. The main camera is untouched. */
public final class TrajectoryPreview {
    private static final List<Vec3> points=new ArrayList<>();
    public static Vec3 impactPoint;
    private static Vec3 origin=Vec3.ZERO;
    private static double range;
    private static int tick;
    public static void update(Minecraft mc){
        if(mc.player==null||mc.level==null||mc.gui.screen()!=null||!mc.player.isUsingItem()||Slingcraft.bowKind(mc.player.getMainHandItem())<0){points.clear();impactPoint=null;tick=0;return;}
        if(tick++%3!=0)return;
        points.clear();impactPoint=null;
        Vec3 look=mc.player.getLookAngle();origin=mc.player.getEyePosition().add(look.scale(.7));
        Vec3 p=origin,v=look.scale(1.15+BowItem.getPowerForTime(mc.player.getTicksUsingItem())*1.25);
        double gravity=new Snowball(EntityTypes.SNOWBALL,mc.level).getGravity();
        points.add(p);
        for(int i=0;i<100;i++){
            // ThrowableProjectile in 26.3 applies gravity, then air drag, then moves.
            v=v.add(0,-gravity,0).scale((double).99f);
            Vec3 next=p.add(v);
            var hit=mc.level.clip(new ClipContext(p,next,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()!=HitResult.Type.MISS){impactPoint=hit.getLocation();points.add(impactPoint);break;}
            points.add(next);p=next;if(p.distanceTo(origin)>90)break;
        }
        Vec3 end=points.getLast();range=Math.hypot(end.x-origin.x,end.z-origin.z);
    }
    public static void render(GuiGraphicsExtractor g,Minecraft mc){
        if(points.size()<2)return;
        int w=134,h=77,x=g.guiWidth()-w-10,y=g.guiHeight()-h-87;
        if(g.guiWidth()<370)y=84;
        g.fill(x,y,x+w,y+h,0xDB1B2026);g.fill(x,y,x+2,y+h,0xFFFFD878);
        g.text(mc.font,"FLIGHT PREVIEW",x+8,y+6,0xFFFFD878);
        double min=-3,max=3;
        for(Vec3 p:points){min=Math.min(min,p.y-origin.y);max=Math.max(max,p.y-origin.y);}
        double extent=Math.max(16,range),height=Math.max(6,max-min);
        int left=x+9,right=x+w-8,top=y+22,bottom=y+h-19;
        int baseline=bottom-(int)((-min)/height*(bottom-top));
        g.fill(left,baseline,right,baseline+1,0xFF5A646F);
        for(int i=0;i<points.size();i+=2){Vec3 p=points.get(i);
            int px=left+(int)(Math.hypot(p.x-origin.x,p.z-origin.z)/extent*(right-left));
            int py=bottom-(int)((p.y-origin.y-min)/height*(bottom-top));
            g.fill(px-1,py-1,px+1,py+1,0xFFFFDC8B);
        }
        if(impactPoint!=null){Vec3 p=impactPoint;
            int px=left+(int)(range/extent*(right-left)),py=bottom-(int)((p.y-origin.y-min)/height*(bottom-top));
            g.fill(px-3,py-3,px+3,py+3,0xFFF17155);
        }
        g.text(mc.font,(impactPoint!=null?"Block at ":"Range ")+(int)Math.round(range)+"m (est.)",x+8,y+h-12,0xFFE3E4E6);
    }
}
