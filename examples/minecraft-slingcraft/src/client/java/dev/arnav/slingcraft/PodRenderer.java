package dev.arnav.slingcraft;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Authored turbine racer with separate chassis, graphite machinery and cyan engine cores. */
public final class PodRenderer extends BoatRenderer {
 private static final class State extends BoatRenderState {boolean pod;}
 private final AbstractBoatRenderer[] layers;
 public PodRenderer(EntityRendererProvider.Context c){
  super(c,net.minecraft.client.model.geom.ModelLayers.OAK_BOAT);
  String[] textures={"iron_block","polished_blackstone","cyan_concrete","sea_lantern"};
  layers=new AbstractBoatRenderer[textures.length];
  for(int i=0;i<layers.length;i++){
   final int part=i;
   layers[i]=new AbstractBoatRenderer(c,Identifier.fromNamespaceAndPath("minecraft","textures/block/"+textures[i]+".png")){
    private final EntityModel<BoatRenderState> shape=createModel(part);
    protected EntityModel<BoatRenderState> model(){return shape;}
   };
  }
 }
 public BoatRenderState createRenderState(){return new State();}
 public void extractRenderState(AbstractBoat e,BoatRenderState state,float dt){super.extractRenderState(e,state,dt);((State)state).pod=e.hasCustomName()&&e.getCustomName().getString().startsWith("Frostline");}
 public void submit(BoatRenderState s,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
  if(((State)s).pod){for(var layer:layers)layer.submit(s,pose,collector,camera);}else super.submit(s,pose,collector,camera);
 }
 private static final JsonObject SPEC=load();
 private static JsonObject load(){
  try(var input=PodRenderer.class.getResourceAsStream("/assets/slingcraft/pods/aurora.json")){
   if(input==null)throw new IOException("Missing Aurora parts");
   var spec=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
   if(spec.get("format").getAsInt()!=1||spec.getAsJsonArray("parts").size()>128)throw new IOException("Unsupported Aurora parts");return spec;
  }catch(IOException e){throw new IllegalStateException("Could not load the shared pod model",e);}
 }
 private static float v(JsonObject p,String key,int i){return p.getAsJsonArray(key).get(i).getAsFloat();}
 private static EntityModel<BoatRenderState> createModel(int part){
  MeshDefinition mesh=new MeshDefinition();var root=mesh.getRoot();
  for(var entry:SPEC.getAsJsonArray("parts")){
   var p=entry.getAsJsonObject();if(p.get("material").getAsInt()!=part)continue;
   float x=v(p,"size",0),y=v(p,"size",1),z=v(p,"size",2);
   root.addOrReplaceChild(p.get("name").getAsString(),CubeListBuilder.create().texOffs(0,0).addBox(-x/2,-y/2,-z/2,x,y,z),
    PartPose.offsetAndRotation(v(p,"position",0),v(p,"position",1),v(p,"position",2),v(p,"rotation",0),v(p,"rotation",1),v(p,"rotation",2)));
  }
  return new EntityModel<BoatRenderState>(LayerDefinition.create(mesh,16,16).bakeRoot()){};
 }
}
