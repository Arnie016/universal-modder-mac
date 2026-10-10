package dev.arnav.slingcraft;

import net.minecraft.core.particles.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Short, budgeted effects. Expanding rings carry impact direction without continuous particle fog. */
public final class WorldVfx {
    private record Pulse(ServerLevel level,Vec3 center,ParticleOptions particle,double radius,int age){}
    private static final List<Pulse> pulses=new ArrayList<>();
    public static void impact(ServerLevel l,Vec3 pos,boolean fire){
        if(pulses.size()<24)pulses.add(new Pulse(l,pos,fire?ParticleTypes.FLAME:ParticleTypes.ELECTRIC_SPARK,fire?4.5:2.8,0));
        l.sendParticles(fire?ParticleTypes.LAVA:ParticleTypes.CRIT,pos.x,pos.y+.15,pos.z,fire?12:10,.2,.3,.2,.1);
    }
    public static void arrive(ServerLevel l,Vec3 pos){
        if(pulses.size()<24)pulses.add(new Pulse(l,pos,ParticleTypes.END_ROD,2.2,0));
    }
    public static void portal(ServerLevel l,Vec3 center,int colorTick){
        // Three differently paced strands create flowing motion instead of a static cloud.
        double phase=l.getGameTime()*.055;
        for(int i=0;i<3;i++){
            double a=phase*(1+i*.17)+i*Math.PI*2/3;
            l.sendParticles(i==0?ParticleTypes.END_ROD:ParticleTypes.PORTAL,center.x+Math.cos(a)*2.2,center.y+3.8+Math.sin(a)*3.2,center.z,1,0,0,0,0);
        }
        if(l.getGameTime()%8==0)l.sendParticles(ParticleTypes.REVERSE_PORTAL,center.x,center.y+3,center.z,5,1.4,2.3,.04,.025);
    }
    public static void gardenPortal(ServerLevel level,net.minecraft.core.BlockPos origin,LobbyGate gate){
        double phase=level.getGameTime()*.035;
        for(int i=0;i<3;i++){
            double a=phase+i*Math.PI*2/3;Vec3 p=gate.point(origin,Math.cos(a)*3.25,5.4+Math.sin(a)*5,.15);
            level.sendParticles(ParticleTypes.END_ROD,p.x,p.y,p.z,1,0,0,0,0);
        }
    }
    public static void tick(){
        for(var it=pulses.listIterator();it.hasNext();){
            Pulse p=it.next();if(p.age>=12){it.remove();continue;}
            if(p.age%2==0){double r=p.radius*(p.age+1)/12.0;
                for(int i=0;i<16;i++){double a=i*Math.PI/8+p.age*.035;
                    p.level.sendParticles(p.particle,p.center.x+Math.cos(a)*r,p.center.y+.14,p.center.z+Math.sin(a)*r,1,0,0,0,0);
                }
            }it.set(new Pulse(p.level,p.center,p.particle,p.radius,p.age+1));
        }
    }
    public static void clear(){pulses.clear();}
}
