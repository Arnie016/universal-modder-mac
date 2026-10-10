package dev.arnav.slingcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;

/** A real signed Minecraft book, usable without commands or operator permission. */
public final class WorldsGuide {
    private WorldsGuide() {}
    private static Component page(String heading, String body) {
        return Component.literal(heading + "\n\n").withStyle(ChatFormatting.BOLD)
            .append(Component.literal(body).withStyle(s -> s.withBold(false)));
    }
    public static ItemStack book(boolean supportFirst) {
        List<Component> pages = new ArrayList<>();
        Component support = page("SUPPORT DOOLEY DOO", "Bring a friend. Share a favourite run. Report a bug with the game name and what happened.\n\nPlaying is enough.\nSupport is optional.");
        if (supportFirst) pages.add(support);
        pages.add(page("THE WORLDS GARDEN", "9: hold the compass, then right-click.\n8: read this book.\n\nHover over a game icon to learn more. Click it to play.\n\nUse your compass during a game to return here."));
        pages.add(page("SLING SIEGE", "Clear every green pig.\n\n1-3: choose a bird.\nHold right-click to charge; release to fire.\n\n5 shots per fort.\n3 forts.\nAutomatic rounds."));
        pages.add(page("BIRD POWERS", "Red: a heavy hit.\nBlue: splits into three.\nBlack: explosive blast.\n\nAim at weak supports!\n\n4-8: build / ignite.\nHold X: break.\nBuild inside the fort.\nRounds restart for you."));
        pages.add(page("FROSTLINE GP", "Pick a setup. Hit Ready.\n\nW: accelerate.\nS: brake.\nA / D: steer.\nSpace: boost.\n\n4 gates, in order.\n3 laps. 4 minutes.\n3 rivals."));
        pages.add(page("AT THE FINISH", "See your time, best lap and reward.\n\nChoose Race again, Change setup, or Garden.\n\nAfter 8 seconds, a new race begins. A timeout gives no finish reward."));
        pages.add(page("BLOOM", "A sealed conservatory. Something is listening.\n\nClick: fire.\nR: reload.\nShift: crouch quietly.\nG: interact up close.\n\nNoise attracts the infected. Move carefully."));
        pages.add(page("THE SPECIMEN", "Burn 3 root clusters.\n\nTake the specimen at the altar.\n\nReturn to the entrance. Press G to extract.\n\nTen minutes. Watch your ammunition."));
        pages.add(page("XP & CREDITS", "Fort cleared: +50.\nRace finished: +100.\nBloom extracted: +150.\n\nXP records progress.\nCredits buy upgrades.\n\nBoth save automatically. Visit the compass shop between games."));
        pages.add(page("UPGRADE SHOP", "Incendiary rounds\n120 credits\nBurn roots in 3 shots.\n\nField supplies\n160 credits\n2 golden apples.\n\nPermanent unlocks. Apply on your next Bloom expedition."));
        if (!supportFirst) pages.add(support);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.CUSTOM_NAME, Component.literal(supportFirst ? "Support Dooley Doo" : "Worlds Garden · Field guide").withStyle(ChatFormatting.GOLD));
        book.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Controls, goals, rewards & creator support").withStyle(ChatFormatting.GRAY))));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Worlds Garden Guide"), "Dooley Doo", 0, pages.stream().map(Filterable::passThrough).toList(), true));
        return book;
    }
    public static void open(ServerPlayer player, boolean supportFirst) {
        if (!GameLobby.contains(player)) return;
        player.closeContainer();
        var book = book(supportFirst);
        player.getInventory().setItem(7, book);
        player.getInventory().setSelectedSlot(7);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(7));
        player.inventoryMenu.broadcastChanges();
        player.openItemGui(book, InteractionHand.MAIN_HAND);
    }
}
