package dev.arnav.slingcraft.test;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.arnav.slingcraft.Slingcraft;

/** Explicit scope selection keeps targeted retests from claiming unexecuted flows. */
public final class WorldsProofSuite implements FabricClientGameTest {
 public void runTest(ClientGameTestContext ctx){
  String scope=System.getenv().getOrDefault("SLINGCRAFT_PROOF","all");
  Slingcraft.LOG.info("Selected client proof scope: {}",scope);
  switch(scope){
   case "all" -> {new LobbyClientTest().runTest(ctx);new WorldsMenuClientTest().runTest(ctx);new NightfallClientTest().runTest(ctx);new RaceCompletionClientTest().runTest(ctx);}
   case "race" -> new RaceCompletionClientTest().runTest(ctx);
   case "menu" -> new WorldsMenuClientTest().runTest(ctx);
   case "garden" -> new GardenArchitectureClientTest().runTest(ctx);
   case "public-siege" -> new PublicSiegeClientTest().runTest(ctx);
   default -> throw new IllegalArgumentException("Unknown proof scope: "+scope);
  }
 }
}
