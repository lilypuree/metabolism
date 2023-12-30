package lilypuree.metabolism.data;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import lilypuree.metabolism.client.ClientMetabolites;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.function.Consumer;

public class Metabolites extends SimpleJsonResourceReloadListener {
    private static Metabolites currentInstance = null;
    private static Metabolites reloadingInstance = null;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Logger LOGGER = LogManager.getLogger("Metabolites");
    public static final String FOLDER = "metabolites";

    private ImmutableMap<Item, Metabolite> metaboliteMap;

    public Metabolites() {
        super(GSON, FOLDER);
        if (currentInstance == null)
            currentInstance = this;
        else
            reloadingInstance = this;
    }

    public static Metabolite getMetabolite(ItemStack item, LivingEntity entity) {
        var map = getMetabolites();
        if (map.containsKey(item.getItem())) {
            return map.get(item.getItem());
        } else {
            FoodProperties properties = item.getFoodProperties(entity);
            if (properties != null)
                return Metabolite.createVanilla(properties.getNutrition(), properties.getSaturationModifier());
            else return Metabolite.NONE;
        }
    }

    public static Map<Item, Metabolite> getMetabolites() {
        return DistExecutor.unsafeRunForDist(
                () -> ClientMetabolites::getClientMetabolites,
                () -> Metabolites::getMetaboliteMap
        );
    }

    public static Map<Item, Metabolite> getMetaboliteMap() {
        if (currentInstance == null)
            throw new RuntimeException("Tried to access Metabolites too early!");
        return currentInstance.metaboliteMap;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        ImmutableMap.Builder<Item, Metabolite> builder = ImmutableMap.builder();
        map.forEach((location, value) -> {
            Item item = ForgeRegistries.ITEMS.getValue(location);
            if (item != Items.AIR) {
                Metabolite metabolite = Metabolite.CODEC.parse(JsonOps.INSTANCE, value)
                        .getOrThrow(false, prefix("Metabolite for " + location + ": "));
                builder.put(item, metabolite);
            } else
                LOGGER.warn("defined metabolite for nonexistent item " + location);
        });
        this.metaboliteMap = builder.build();

        LOGGER.debug("Finished parsing metabolites");
        if (this == reloadingInstance) {
            currentInstance = this;
            reloadingInstance = null;
        }
    }

    private static Consumer<String> prefix(String pre) {
        return s -> LOGGER.error(pre + s);
    }
}
