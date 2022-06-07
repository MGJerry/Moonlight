package net.mehvahdjukaar.selene.resourcepack.recipe;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class TemplateRecipeManager {

    private static final Map<ResourceLocation, Function<JsonObject, ? extends IRecipeTemplate<?>>> DESERIALIZERS = new HashMap<>();

    /**
     * Registers a recipe template deserializer. Will be used to parse existing recipes and be able to create new ones
     *
     * @param deserializer usually IRecipeTemplate::new
     * @param serializer   recipe serializer type
     */
    public static <T extends IRecipeTemplate<?>> void registerTemplate(
            RecipeSerializer<?> serializer, Function<JsonObject, T> deserializer) {
        registerTemplate(serializer.getRegistryName(), deserializer);
    }

    public static <T extends IRecipeTemplate<?>> void registerTemplate(
            ResourceLocation serializerId, Function<JsonObject, T> deserializer) {
        DESERIALIZERS.put(serializerId, deserializer);
    }

    public static IRecipeTemplate<?> read(JsonObject recipe) throws UnsupportedOperationException {
        String type = GsonHelper.getAsString(recipe, "type");
        //RecipeSerializer<?> s = ForgeRegistries.RECIPE_SERIALIZERS.getValue(new ResourceLocation(type));

        var templateFactory = DESERIALIZERS.get(new ResourceLocation(type));

        if (templateFactory != null) {
            return templateFactory.apply(recipe);
        } else {
            throw new UnsupportedOperationException(String.format("Invalid recipe serializer: %s. Must be either shaped, shapeless or stonecutting", type));
        }
    }

    static {
        registerTemplate(RecipeSerializer.SHAPED_RECIPE, ShapedRecipeTemplate::new);
        registerTemplate(RecipeSerializer.SHAPELESS_RECIPE, ShapelessRecipeTemplate::new);
        registerTemplate(RecipeSerializer.STONECUTTER, StoneCutterRecipeTemplate::new);
    }
}