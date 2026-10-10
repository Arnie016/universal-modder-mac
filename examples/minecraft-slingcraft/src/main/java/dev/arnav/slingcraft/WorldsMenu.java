package dev.arnav.slingcraft;

import java.util.Arrays;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Server-owned menu icons are actions; they can never be taken into an inventory. */
public final class WorldsMenu extends ChestMenu {
    public static final int SIEGE = 11, RACE = 13, BLOOM = 15, GUIDE = 18, PROGRESS = 22, SHOP = 24, SUPPORT = 26;
    private enum Page { GAMES, SHOP, PROGRESS }
    private final ServerPlayer owner;
    private final Page page;
    private WorldsMenu(int id, Inventory inventory, ServerPlayer player, Page page) {
        super(MenuType.GENERIC_9x3, id, inventory, new SimpleContainer(27), 3);
        this.owner = player;
        this.page = page;
        refresh();
    }
    public static void open(ServerPlayer p) { open(p, Page.GAMES); }
    public static void openShop(ServerPlayer p) { open(p, Page.SHOP); }
    public static void openProgress(ServerPlayer p) { open(p, Page.PROGRESS); }
    private static void open(ServerPlayer p, Page page) {
        if (!GameLobby.contains(p)) return;
        String title = switch(page) { case GAMES -> "Worlds · Choose a game"; case SHOP -> "Worlds · Upgrade shop"; case PROGRESS -> "Worlds · Achievements"; };
        p.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new WorldsMenu(id, inventory, p, page), Component.literal(title)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(SoundEvents.UI_BUTTON_CLICK, net.minecraft.sounds.SoundSource.PLAYERS, p.getX(), p.getY(), p.getZ(), .22f, 1.25f, p.getRandom().nextLong()));
    }
    private static ItemStack label(ItemStack stack, String title, ChatFormatting color, String... lore) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(color).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).map(line -> (Component)Component.literal(line).withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false))).toList()));
        return stack;
    }
    private void put(int slot, ItemStack icon, String title, ChatFormatting color, String... lines) { getContainer().setItem(slot, label(icon, title, color, lines)); }
    private void refresh() {
        for (int i = 0; i < 27; i++) put(i, new ItemStack(Items.STAINED_GLASS_PANE.gray()), " ", ChatFormatting.DARK_GRAY);
        if (page == Page.PROGRESS) { progress(); return; }
        boolean shop = page == Page.SHOP;
        put(4, new ItemStack(Items.COMPASS), shop ? "Spend earned credits" : "Choose your next adventure", ChatFormatting.AQUA, "Hover to read. Click an icon to choose.", "Esc closes this menu.");
        if (!shop) {
            put(SIEGE, Slingcraft.birdItem(0), "Sling Siege", ChatFormatting.GOLD, "Shatter towers. Clear every green pig.", "3 forts · 5 shots each · +50 per clear", GameLobby.ready() ? "Click to play" : "Garden is preparing…");
            put(RACE, new ItemStack(Items.OAK_BOAT), "Frostline GP", ChatFormatting.AQUA, "Tune your pod, then race three rivals.", "3 laps · 4 minutes · +100 for a finish", GameLobby.ready() ? "Click to enter the garage" : "Garden is preparing…");
            put(BLOOM, new ItemStack(Items.CRIMSON_FUNGUS), "Bloom", ChatFormatting.LIGHT_PURPLE, "Burn the roots. Recover the specimen.", "Survive & extract · +150", GameLobby.ready() ? "Click to enter the conservatory" : "Garden is preparing…");
            put(SHOP, new ItemStack(Items.EMERALD), "Upgrade shop", ChatFormatting.GREEN, "Spend credits you earn by playing.", "Permanent Bloom upgrades", "Click to browse");
            put(SUPPORT, new ItemStack(Items.POPPY), "Support Dooley Doo", ChatFormatting.LIGHT_PURPLE, "Help these worlds grow.", "Always optional · open the support page");
        } else {
            upgrade(11, "incendiary", new ItemStack(Items.FIRE_CHARGE), "Incendiary rounds", 120, "Burn a root cluster in three shots.");
            upgrade(15, "fieldkit", new ItemStack(Items.GOLDEN_APPLE), "Field supplies", 160, "Two golden apples each expedition.");
            put(SUPPORT, new ItemStack(Items.ARROW), "Back to games", ChatFormatting.AQUA, "Return to the game selector");
        }
        put(GUIDE, new ItemStack(Items.WRITTEN_BOOK), "Field guide", ChatFormatting.GOLD, "Controls, goals, bird powers & rewards", "Click to read. Your book is in hotbar slot 8.");
        put(PROGRESS, new ItemStack(Items.EXPERIENCE_BOTTLE), "Your achievements", ChatFormatting.GREEN, ModeProfiles.xp(owner) + " Worlds XP · " + ModeProfiles.credits(owner) + " credits", "Click to see earned and locked badges.", "Progress saves automatically.");
    }
    private void progress() {
        put(SUPPORT, new ItemStack(Items.ARROW), "Back to games", ChatFormatting.AQUA, "Return to the game selector");
        if (!ModeProfiles.progressAvailable(owner)) {
            put(13, new ItemStack(Items.BARRIER), "Progress unavailable", ChatFormatting.RED, "Your saved progress has been kept.", "Ask the server owner to check storage.");
            return;
        }
        int xp = ModeProfiles.xp(owner);
        var badges = ModeProfiles.badges(owner);
        String[] ids = {"Siege / Glass Roost", "Siege / Twin Nests", "Siege / Stone Crown", "Frostline finisher", "Bloom field researcher"};
        String[] names = {"Glass Roost", "Twin Nests", "Stone Crown", "Frostline finisher", "Bloom field researcher"};
        String[] goals = {"Clear the first Siege fort.", "Clear the second Siege fort.", "Clear the third Siege fort.", "Finish all 3 laps within 4 minutes.", "Recover the specimen and escape."};
        var icons = new net.minecraft.world.item.Item[]{Items.GLASS, Items.OAK_PLANKS, Items.STONE_BRICKS, Items.OAK_BOAT, Items.CRIMSON_FUNGUS};
        int earned = 0;
        for (int i = 0; i < ids.length; i++) {
            boolean unlocked = badges.contains(ids[i]);
            if (unlocked) earned++;
            put(10 + i, new ItemStack(unlocked ? icons[i] : Items.DYE.gray()), names[i], unlocked ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                unlocked ? "Earned" : "Locked", goals[i]);
        }
        put(4, new ItemStack(Items.EXPERIENCE_BOTTLE), "Level " + (1 + xp / 250) + " · " + xp + " Worlds XP", ChatFormatting.GREEN,
            (250 - xp % 250) + " XP to the next level", ModeProfiles.credits(owner) + " credits to spend", earned + " / 5 game badges earned");
        put(16, new ItemStack(earned == 5 ? Items.NETHER_STAR : Items.DYE.gray()), "Worlds explorer", earned == 5 ? ChatFormatting.GOLD : ChatFormatting.GRAY,
            earned == 5 ? "Earned · all five game badges" : "Locked · earn all five game badges", "Siege, Frostline and Bloom completed.");
        long lap = ModeProfiles.bestLapTicks(owner);
        put(20, new ItemStack(Items.CLOCK), "Best Frostline lap", ChatFormatting.AQUA,
            lap == 0 ? "Finish a race to set your first record." : String.format(java.util.Locale.ROOT, "%d:%02d.%02d", lap / 1200, (lap / 20) % 60, (lap % 20) * 5));
        put(SHOP, new ItemStack(Items.EMERALD), "Upgrade shop", ChatFormatting.GREEN, "Spend credits; your XP and badges stay.", "Click to browse");
    }
    private void upgrade(int slot, String id, ItemStack icon, String name, int price, String description) {
        boolean owned = ModeProfiles.owns(owner, id);
        int missing = price - ModeProfiles.credits(owner);
        put(slot, icon, name, owned ? ChatFormatting.GREEN : ChatFormatting.GOLD, description, owned ? "Owned · applies next expedition" : price + " credits", owned ? "Already unlocked" : missing > 0 ? "Earn " + missing + " more credits" : "Click to unlock");
    }
    @Override public boolean stillValid(Player p) { return p == owner && GameLobby.contains(owner); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot, int button, ContainerInput input, Player p) {
        if (!stillValid(p)) { owner.closeContainer(); return; }
        // Ignore transfers, hotbar swaps, drops and drag operations. Correct vanilla prediction.
        if (input != ContainerInput.PICKUP || button < 0 || button > 1 || slot < 0 || slot >= 27) { broadcastFullState(); return; }
        if (page == Page.PROGRESS) {
            if (slot == SUPPORT) { open(owner); return; }
            if (slot == SHOP) { openShop(owner); return; }
            broadcastFullState(); return;
        }
        if (slot == GUIDE) { WorldsGuide.open(owner, false); return; }
        if (slot == PROGRESS) { openProgress(owner); return; }
        if (page == Page.SHOP) {
            if (slot == SUPPORT) { open(owner); return; }
            if (slot == 11 || slot == 15) { ModeProfiles.buy(owner, slot == 11 ? "incendiary" : "fieldkit"); refresh(); }
        } else {
            if (slot == SUPPORT) { WorldsGuide.open(owner, true); return; }
            if (slot == SHOP) { openShop(owner); return; }
            int mode = slot == SIEGE ? 0 : slot == RACE ? 1 : slot == BLOOM ? 2 : -1;
            if (mode >= 0 && GameLobby.ready()) { owner.closeContainer(); GameLobby.choose(owner, mode); return; }
        }
        broadcastFullState();
    }
}
