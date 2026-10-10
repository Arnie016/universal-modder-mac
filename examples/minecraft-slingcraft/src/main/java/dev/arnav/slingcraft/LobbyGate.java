package dev.arnav.slingcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Scenery, walking triggers, effects and tests share these coordinates. v points into the garden. */
public record LobbyGate(int x,int z,int rightX,int rightZ,int frontX,int frontZ,String title,String detail,int color) {
 public static final int HEIGHT=11;
 public static final LobbyGate[] ALL={
  new LobbyGate(-61,0,0,1,1,0,"SLING SIEGE","Break the fortress · Clear every target",0xffd177),
  new LobbyGate(61,0,0,-1,-1,0,"FROSTLINE GP","Tune your pod · Race three laps",0x90eaff),
  new LobbyGate(0,-61,1,0,0,1,"BLOOM","Silence the roots · Return alive",0xd7a0ee)
 };
 public BlockPos at(BlockPos o,int u,int y,int v){return o.offset(x+rightX*u+frontX*v,y,z+rightZ*u+frontZ*v);}
 public Vec3 point(BlockPos o,double u,double y,double v){return new Vec3(o.getX()+x+.5+rightX*u+frontX*v,o.getY()+y,o.getZ()+z+.5+rightZ*u+frontZ*v);}
 public Direction.Axis axis(){return rightX==0?Direction.Axis.Z:Direction.Axis.X;}
 public float approachYaw(){return (float)Math.toDegrees(Math.atan2(frontX,-frontZ));}
 public boolean contains(BlockPos o,Vec3 p,double depth){double dx=p.x-o.getX()-x-.5,dz=p.z-o.getZ()-z-.5,y=p.y-o.getY();return Math.abs(dx*rightX+dz*rightZ)<3.6&&Math.abs(dx*frontX+dz*frontZ)<depth&&y>=-.2&&y<HEIGHT;}
}
