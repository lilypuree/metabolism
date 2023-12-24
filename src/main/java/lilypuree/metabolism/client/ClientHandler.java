package lilypuree.metabolism.client;


import lilypuree.metabolism.metabolism.Metabolism;
import lilypuree.metabolism.metabolism.FoodDataDuck;
import lilypuree.metabolism.network.ClientSyncMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Handles information synced from the server. Only information actually needed by the client should
 * be synced. This information will likely not be updated every tick.
 */
public class ClientHandler {
    private ClientHandler() {
    }

    public static void handleSyncMessage(ClientSyncMessage msg, Supplier<NetworkEvent.Context> ctx) {
        Metabolism metabolism = getClientMetabolism(Minecraft.getInstance());
        metabolism.syncOnClient(msg);
    }

    public static Metabolism getClientMetabolism(Minecraft mc) {
        return ((FoodDataDuck) mc.player.getFoodData()).getMetabolism();
    }
}