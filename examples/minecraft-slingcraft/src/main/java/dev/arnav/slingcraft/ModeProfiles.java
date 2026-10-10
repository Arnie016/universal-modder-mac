package dev.arnav.slingcraft;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

/** Mode kits are disposable; the pre-hub inventory is archived before the first replacement. */
public final class ModeProfiles {
    public enum Mode { HUB, SIEGE, RACE, NIGHTFALL }
    private static final Map<UUID,Mode> modes=new HashMap<>();
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static Mode mode(ServerPlayer p){return modes.get(p.getUUID());}
    public static void forget(ServerPlayer p){modes.remove(p.getUUID());}
    public static void clear(){modes.clear();}
    private static Path folder(ServerPlayer p){return p.level().getServer().getWorldPath(LevelResource.ROOT).resolve("slingcraft-players");}
    private static void write(Path path,JsonObject value)throws java.io.IOException{
        Files.createDirectories(path.getParent());
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(tmp,JSON.toJson(value));
        try {Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public static boolean enter(ServerPlayer p,Mode mode){
        try {
            Path archive=folder(p).resolve(p.getUUID()+"-inventory.json");
            if(!Files.exists(archive)){
                JsonObject root=new JsonObject();root.addProperty("format",1);
                root.addProperty("selected",p.getInventory().getSelectedSlot());
                var ops=p.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                JsonArray slots=new JsonArray();
                for(int i=0;i<p.getInventory().getContainerSize();i++)
                    slots.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,p.getInventory().getItem(i)).getOrThrow());
                root.add("slots",slots);
                root.add("cursor",ItemStack.OPTIONAL_CODEC.encodeStart(ops,p.containerMenu.getCarried()).getOrThrow());
                write(archive,root);
            }else{
                // A damaged archive must never silently allow more inventory replacement.
                JsonObject archiveData=JsonParser.parseString(Files.readString(archive)).getAsJsonObject();
                if(!archiveData.has("slots")||!archiveData.get("slots").isJsonArray())throw new IllegalStateException("Inventory archive is invalid");
            }
        }catch(Exception e){
            Slingcraft.LOG.error("Inventory archive failed; preserving inventory",e);
            p.sendSystemMessage(Component.literal("Could not store your items. Return to the lobby after checking world storage."),false);
            return false;
        }
        p.closeContainer();p.getInventory().clearContent();p.inventoryMenu.setCarried(ItemStack.EMPTY);
        // An unlimited kit must not grant the creative catalogue or creative item packets.
        p.setGameMode(mode==Mode.SIEGE?GameType.SURVIVAL:GameType.ADVENTURE);
        // Adventure-to-adventure switches may not reset abilities. Never leak hub protection into a game.
        p.getAbilities().invulnerable=mode==Mode.HUB||mode==Mode.SIEGE;
        p.getAbilities().instabuild=false;
        p.getAbilities().mayfly=false;p.getAbilities().flying=false;
        p.resetFallDistance();p.onUpdateAbilities();
        p.getInventory().setSelectedSlot(0);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
        ItemStack menu=compass();var menuData=new net.minecraft.nbt.CompoundTag();menuData.putBoolean("hub",mode==Mode.HUB);
        menu.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(menuData));
        p.getInventory().setItem(8,menu);modes.put(p.getUUID(),mode);
        if(mode==Mode.HUB){p.getInventory().setItem(7,WorldsGuide.book(false));menu.set(DataComponents.CUSTOM_NAME,Component.literal("Worlds · Choose a game"));}
        syncWallet(p);p.inventoryMenu.broadcastChanges();return true;
    }
    public static ItemStack compass(){
        ItemStack s=new ItemStack(Items.COMPASS);
        s.set(DataComponents.CUSTOM_NAME,Component.literal("Worlds · Return to garden"));
        s.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("slingcraft","worlds_compass"));return s;
    }
    public static boolean isCompass(ItemStack s){return Identifier.fromNamespaceAndPath("slingcraft","worlds_compass").equals(s.get(DataComponents.ITEM_MODEL));}
    public static int xp(ServerPlayer p){var v=read(p);return v==null?0:v.get("xp").getAsInt();}
    public static int credits(ServerPlayer p){var v=read(p);return v==null?0:v.get("credits").getAsInt();}
    public static Set<String> badges(ServerPlayer p){
        var v=read(p);if(v==null)return Set.of();
        Set<String> result=new HashSet<>();
        for(var badge:v.getAsJsonArray("badges"))result.add(badge.getAsString());
        return Set.copyOf(result);
    }
    public static boolean progressAvailable(ServerPlayer p){return read(p)!=null;}
    public static boolean owns(ServerPlayer p,String id){var v=read(p);return v!=null&&v.getAsJsonArray("upgrades").contains(new JsonPrimitive(id));}
    public static long bestLapTicks(ServerPlayer p){var v=read(p);return v!=null&&v.has("bestLapTicks")?Math.max(0,v.get("bestLapTicks").getAsLong()):0;}
    public static void bestLap(ServerPlayer p,long ticks){
        var v=read(p);if(v==null||ticks<=0||ticks>12000)return;
        if(v.has("bestLapTicks")&&v.get("bestLapTicks").getAsLong()<=ticks)return;
        v.addProperty("bestLapTicks",ticks);try{write(folder(p).resolve(p.getUUID()+"-progress.json"),v);}catch(Exception e){Slingcraft.LOG.warn("Lap could not be saved",e);}
    }
    public static void syncWallet(ServerPlayer p){
        var item=p.getInventory().getItem(8);if(!isCompass(item))return;
        var data=item.get(DataComponents.CUSTOM_DATA);var tag=data==null?new net.minecraft.nbt.CompoundTag():data.copyTag();
        tag.putInt("credits",credits(p));tag.putInt("xp",xp(p));tag.putBoolean("incendiary",owns(p,"incendiary"));tag.putBoolean("fieldkit",owns(p,"fieldkit"));
        item.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));p.inventoryMenu.broadcastChanges();
    }
    public static boolean buy(ServerPlayer p,String id){
        if(!GameLobby.contains(p))return false;int price=switch(id){case "incendiary"->120;case "fieldkit"->160;default->-1;};if(price<0)return false;
        var v=read(p);if(v==null)return false;var upgrades=v.getAsJsonArray("upgrades");if(upgrades.contains(new JsonPrimitive(id)))return false;
        int balance=v.get("credits").getAsInt();if(balance<price){p.sendSystemMessage(Component.literal("Earn "+(price-balance)+" more credits by completing a game."),true);return false;}
        v.addProperty("credits",balance-price);upgrades.add(id);
        try{write(folder(p).resolve(p.getUUID()+"-progress.json"),v);syncWallet(p);p.sendSystemMessage(Component.literal("Upgrade unlocked · available on your next Bloom expedition."),true);return true;}
        catch(Exception e){Slingcraft.LOG.error("Could not save purchase; no items granted",e);return false;}
    }
    private static JsonObject read(ServerPlayer p){
        Path file=folder(p).resolve(p.getUUID()+"-progress.json");
        try {if(Files.exists(file)){
            var v=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(v.get("xp").getAsInt()<0||!v.get("badges").isJsonArray())throw new IllegalStateException("Invalid progress");
            for(var badge:v.getAsJsonArray("badges"))if(!badge.isJsonPrimitive()||!badge.getAsJsonPrimitive().isString())throw new IllegalStateException("Invalid badge");
            if(!v.has("credits"))v.addProperty("credits",v.get("xp").getAsInt());if(!v.has("upgrades"))v.add("upgrades",new JsonArray());
            if(v.get("credits").getAsInt()<0||!v.get("upgrades").isJsonArray())throw new IllegalStateException("Invalid wallet");return v;
        }}catch(Exception e){Slingcraft.LOG.warn("Could not read progress; preserving file",e);return null;}
        JsonObject value=new JsonObject();value.addProperty("xp",0);value.addProperty("credits",0);value.add("badges",new JsonArray());value.add("upgrades",new JsonArray());return value;
    }
    public static boolean award(ServerPlayer p,int points,String badge){
        JsonObject value=read(p);if(value==null||points<=0){p.sendSystemMessage(Component.literal("Progress storage needs attention; your saved file was preserved."),false);return false;}
        value.addProperty("xp",value.get("xp").getAsInt()+points);value.addProperty("credits",value.get("credits").getAsInt()+points);
        JsonArray badges=value.getAsJsonArray("badges");
        boolean fresh=badge!=null&&!badges.contains(new JsonPrimitive(badge));
        if(fresh)badges.add(badge);
        try {write(folder(p).resolve(p.getUUID()+"-progress.json"),value);p.giveExperiencePoints(points);
            syncWallet(p);p.sendSystemMessage(Component.literal("+"+points+" XP & credits"+(fresh?" · Badge: "+badge:"")),false);return true;
        }catch(Exception e){Slingcraft.LOG.error("Could not save earned progress",e);p.sendSystemMessage(Component.literal("Progress could not be saved. Check world storage."),false);return false;}
    }
}
