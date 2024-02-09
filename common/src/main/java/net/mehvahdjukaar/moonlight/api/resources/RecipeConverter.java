package net.mehvahdjukaar.moonlight.api.resources;

import net.mehvahdjukaar.moonlight.api.set.BlockType;
import net.mehvahdjukaar.moonlight.core.Moonlight;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class RecipeConverter {

    private static final Map<Class<?>, RecipeConverter> CONVERTERS = new HashMap<>();

    private List<Field> fieldToConvert = new ArrayList<>();

    private RecipeConverter(List<Field> fields) {
        this.fieldToConvert = fields;
    }

    @Nullable
    private <R, T extends BlockType> R convert(R recipe, T originalMat, T destinationMat, Item unlockedBy, String id) throws IllegalAccessException {
        for (var f : fieldToConvert) {
            Object value = f.get(recipe);
            if (value instanceof List<?> list) {
                boolean oneChanged = false;
                ListIterator<Object> iterator = ((List<Object>) list).listIterator();
                while (iterator.hasNext()) {
                    Object currentValue = iterator.next();
                    Object newValue = tryConverting(originalMat, destinationMat, currentValue);
                    if (newValue != null) {
                        oneChanged = true;
                        iterator.set(newValue);
                    }
                }
                if (!oneChanged) {
                    throw new RuntimeException(String.format("Failed to convert some fields for recipe %s from type %s to type %s", recipe, originalMat, destinationMat));
                }
            } else if (value instanceof Map<?, ?> map) {
                Map<Object, Object> omap = (Map<Object, Object>) map;
                boolean oneChanged = false;
                for (Map.Entry<Object, Object> entry : new HashSet<>(omap.entrySet())) {
                    Object currentKey = entry.getKey();
                    Object currentValue = entry.getValue();

                    Object newKey = tryConverting(originalMat, destinationMat, currentKey);
                    Object newValue = tryConverting(originalMat, destinationMat, currentValue);

                    if (newKey != null || newValue != null) {
                        omap.remove(currentKey);
                        oneChanged = true;
                    }

                    if (newKey != null) {
                        omap.put(newKey, newValue != null ? newValue : currentValue);
                    } else if (newValue != null) {
                        entry.setValue(newValue);
                    }
                }
                if (!oneChanged) {
                    throw new RuntimeException(String.format("Failed to convert some fields for recipe %s from type %s to type %s", recipe, originalMat, destinationMat));
                }
            } else if (value instanceof Record) {
                var innerConv = getOrCreateConverter(value.getClass());
                if (innerConv != null) {
                    innerConv.convert(value, originalMat, destinationMat, unlockedBy, id);
                }
            } else if (value instanceof Optional<?> opt) {
                if (opt.isPresent()) {
                    value = opt.get();
                    var innerConv = getOrCreateConverter(value.getClass());
                    if (innerConv != null) {
                        f.setAccessible(true);
                        f.set(recipe, Optional.of(innerConv.convert(value, originalMat, destinationMat, unlockedBy, id)));
                    }
                }
            } else {
                Object newValue = tryConverting(originalMat, destinationMat, value);
                if (newValue == null)
                    throw new RuntimeException(String.format("Failed to convert item %s for recipe %s from type %s to type %s", value, recipe, originalMat, destinationMat));
                f.set(recipe, newValue);
            }
        }
        return recipe;
    }

    @Nullable
    private <V, T extends BlockType> V tryConverting(T originalMat, T destinationMat, V value) {
        if (value instanceof ItemStack stack) {
            Item item = BlockType.changeItemType(stack.getItem(), originalMat, destinationMat);
            if (item == null) return null;
            return (V) item.getDefaultInstance();
        } else if (value instanceof Item il) {
            return (V) BlockType.changeItemType(il, originalMat, destinationMat);
        } else if (value instanceof Ingredient ing) {
            return (V) convertIngredients(originalMat, destinationMat, ing);
        }
        return null;
    }

    @Nullable
    private <T extends BlockType> Ingredient convertIngredients(T originalMat, T destinationMat, Ingredient ing) {
        for (var in : ing.getItems()) {
            Item it = in.getItem();
            if (it != Items.BARRIER) {
                ItemLike i = BlockType.changeItemType(it, originalMat, destinationMat);
                if (i != null) {
                    //converts first ingredient it finds
                    return Ingredient.of(i);
                }
            }
        }
        return null;
    }

    @Nullable
    public static <T extends BlockType, R extends Recipe<?>> R createSimilar(R recipe, T originalMat, T destinationMat, Item unlockItem, @Nullable String id) {
        recipe = (R) RPUtils.readRecipe(RPUtils.writeRecipe(recipe));
        Class<?> clazz = recipe.getClass();
        RecipeConverter conv = getOrCreateConverter(clazz);
        if (conv == null) throw new RuntimeException("Failed to convert recipe of class " + clazz);

        try {
            return conv.convert(recipe, originalMat, destinationMat, unlockItem, id);
        } catch (Exception e) {
            Moonlight.LOGGER.error("Recipe conversion error: " + e.getMessage());
        }
        return null;
    }

    @Nullable
    private static RecipeConverter getOrCreateConverter(Class<?> clazz) {
        return CONVERTERS.computeIfAbsent(clazz, c -> {
            try {
                var fields = findFieldsByType(clazz, ItemStack.class, Item.class, Ingredient.class, Record.class);
                fields.forEach(f -> f.setAccessible(true));
                return new RecipeConverter(fields);
            } catch (Exception ignored) {
            }
            return null;
        });
    }

    private static List<Field> findFieldsByType(Class<?> clazz, Class<?>... targetTypes) {
        List<Field> foundFields = new ArrayList<>();

        // Find fields in the current class
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();

            for (Class<?> targetType : targetTypes) {
                if (targetType.isAssignableFrom(fieldType)) {
                    foundFields.add(field);
                    break;
                } else if (List.class.isAssignableFrom(fieldType)) {
                    ParameterizedType listType = (ParameterizedType) field.getGenericType();
                    Class<?> listElementType = (Class<?>) listType.getActualTypeArguments()[0];
                    if (listElementType.equals(targetType)) {
                        foundFields.add(field);
                        break;
                    }
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    ParameterizedType mapType = (ParameterizedType) field.getGenericType();
                    Class<?> mapKeyType = (Class<?>) mapType.getActualTypeArguments()[0];
                    Class<?> mapValueType = (Class<?>) mapType.getActualTypeArguments()[1];
                    if (targetType.isAssignableFrom(mapKeyType) || targetType.isAssignableFrom(mapValueType)) {
                        foundFields.add(field);
                        break;
                    }
                }
            }
        }

        // Recursively search fields in parent classes
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) {
            foundFields.addAll(findFieldsByType(superClass, targetTypes));
        }
        return foundFields;
    }

}