package com.drtshock.playervaults.vaultmanagement;

import com.mojang.datafixers.DSL;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CardboardBox {
    private static final int OLD_DATA_VERSION = 1343;

    private static Method itemStackFromCompound;
    private static Method itemStackSave;
    private static Method nbtCompressedStreamToolsRead;
    private static Method nbtCompressedStreamToolsWrite;
    private static Method nbtTagCompoundGetInt;
    private static Method nbtTagCompoundSetInt;
    private static Method dataFixerUpdate;
    private static Method nbtAccounterUnlimitedHeap;
    private static Constructor<?> dynamic;
    private static Constructor<?> nbtTagCompoundConstructor;
    private static Constructor<?> itemStackConstructor;

    private static Class<?> craftItemStack;
    private static Field craftItemStackHandle;
    private static Method craftItemStackAsNMSCopy;
    private static Method craftItemStackAsCraftMirror;
    private static Method dynamicGetValue;

    private static Object dynamicOpsNBT;
    private static Object dataConverterTypesItemStack;
    private static Object dataConverterRegistryDataFixer;

    private static int dataVersion;
    private static boolean hasDataVersion = false;

    private static boolean failure = true;
    private static boolean modernPaper = false;
    private static Exception exception;
    private static boolean testPending = true;

    static {
        init();
    }

    public static boolean init() {
        try {
            ItemStack.class.getDeclaredMethod("deserializeBytes", byte[].class);
            ItemStack.class.getDeclaredMethod("serializeAsBytes");
            modernPaper = true;
            failure = false;
            return true;
        } catch (Exception ignored) {
        }
        try {
            Pattern versionPattern = Pattern.compile("1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?");
            Matcher versionMatcher = versionPattern.matcher(Bukkit.getVersion());
            if (!versionMatcher.find()) {
                throw new RuntimeException("Could not parse version");
            }
            int minor = Integer.parseInt(versionMatcher.group(1));
            String patchS = versionMatcher.group(2);
            int patch = (patchS == null || patchS.isEmpty()) ? 0 : Integer.parseInt(patchS);
            int ver = (minor * 100) + patch;
            String[] packageSplit = Bukkit.getServer().getClass().getPackage().getName().split("\\.");
            String packageVersion = packageSplit[packageSplit.length - 1] + '.';

            String obc = "org.bukkit.craftbukkit." + packageVersion;
            String nmsItemStack, nmsNBTCompressedStreamTools, nmsNBTTagCompound, nmsDynamicOpsNBT, nmsDataConverterTypes, nmsDataConverterRegistry;
            String nmsNBTReadLimiter = "net.minecraft.nbt.NBTReadLimiter";

            if (ver < 1700) {
                String nms = "net.minecraft.server." + packageVersion;
                nmsItemStack = nms + "ItemStack";
                nmsNBTTagCompound = nms + "NBTTagCompound";
                nmsNBTCompressedStreamTools = nms + "NBTCompressedStreamTools";
                nmsDynamicOpsNBT = nms + "DynamicOpsNBT";
                nmsDataConverterTypes = nms + "DataConverterTypes";
                nmsDataConverterRegistry = nms + "DataConverterRegistry";
            } else {
                nmsItemStack = "net.minecraft.world.item.ItemStack";
                nmsNBTTagCompound = "net.minecraft.nbt.NBTTagCompound";
                nmsNBTCompressedStreamTools = "net.minecraft.nbt.NBTCompressedStreamTools";
                nmsDynamicOpsNBT = "net.minecraft.nbt.DynamicOpsNBT";
                nmsDataConverterTypes = "net.minecraft.util.datafix.fixes.DataConverterTypes";
                nmsDataConverterRegistry = "net.minecraft.util.datafix.DataConverterRegistry";
            }

            // Time to load ALL the things!

            // NMS
            Class<?> itemStack = Class.forName(nmsItemStack);
            Class<?> nbtCompressedStreamTools = Class.forName(nmsNBTCompressedStreamTools);
            Class<?> nbtTagCompound = Class.forName(nmsNBTTagCompound);
            nbtTagCompoundConstructor = nbtTagCompound.getConstructor();
            try {
                itemStackFromCompound = itemStack.getMethod("fromCompound", nbtTagCompound);
            } catch (NoSuchMethodException e) {
                try {
                    itemStackFromCompound = itemStack.getMethod("createStack", nbtTagCompound);
                } catch (NoSuchMethodException e2) {
                    try {
                        itemStackFromCompound = itemStack.getMethod("a", nbtTagCompound);
                    } catch (NoSuchMethodException e3) {
                        itemStackConstructor = itemStack.getConstructor(nbtTagCompound);
                    }
                }
            }
            itemStackSave = itemStack.getMethod(ver >= 1800 ? "b" : "save", nbtTagCompound);
            if (ver < 2004) {
                nbtCompressedStreamToolsRead = nbtCompressedStreamTools.getMethod("a", InputStream.class);
            } else {
                Class<?> nbtAccounter = Class.forName(nmsNBTReadLimiter);
                nbtCompressedStreamToolsRead = nbtCompressedStreamTools.getMethod("a", InputStream.class, nbtAccounter);
                nbtAccounterUnlimitedHeap = nbtAccounter.getMethod("a");
            }
            nbtCompressedStreamToolsWrite = nbtCompressedStreamTools.getMethod("a", nbtTagCompound, OutputStream.class);
            nbtTagCompoundGetInt = nbtTagCompound.getMethod(ver >= 1800 ? "h" : "getInt", String.class);
            nbtTagCompoundSetInt = nbtTagCompound.getMethod(ver >= 1800 ? "a" : "setInt", String.class, int.class);

            // OBC
            craftItemStack = Class.forName(obc + "inventory.CraftItemStack");
            Class<?> craftMagicNumbers = Class.forName(obc + "util.CraftMagicNumbers");
            craftItemStackHandle = craftItemStack.getDeclaredField("handle");
            craftItemStackHandle.setAccessible(true);
            Field craftMagicNumbersInstance = craftMagicNumbers.getField("INSTANCE");
            craftItemStackAsNMSCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            craftItemStackAsCraftMirror = craftItemStack.getMethod("asCraftMirror", itemStack);

            // DataFixer
            try {
                craftMagicNumbers.getMethod("getDataVersion");
                dataVersion = (int) craftMagicNumbers.getMethod("getDataVersion").invoke(craftMagicNumbersInstance.get(null));
                Class<?> dataFixer = Class.forName("com.mojang.datafixers.DataFixer");
                for (Method method : dataFixer.getMethods()) {
                    if (method.getName().equals("update") && method.getParameterCount() == 4) {
                        dataFixerUpdate = method;
                        break;
                    }
                }

                Class<?> dataConverterRegistry = Class.forName(nmsDataConverterRegistry);
                for (Field field : dataConverterRegistry.getDeclaredFields()) {
                    if (dataFixer.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        dataConverterRegistryDataFixer = field.get(null);
                        break;
                    }
                }
                if (dataConverterRegistryDataFixer == null) {
                    throw new IllegalStateException("No sign of data fixer");
                }
                if (ver < 1700) {
                    dataConverterTypesItemStack = Class.forName(nmsDataConverterTypes).getField("ITEM_STACK").get(null);
                } else {
                    dataConverterTypesItemStack = (DSL.TypeReference) () -> "item_stack";
                }
                dynamicOpsNBT = Class.forName(nmsDynamicOpsNBT).getField("a").get(null);
                Class<?> dynamicClass = dataFixerUpdate.getParameterTypes()[1];
                for (Constructor<?> constructor : dynamicClass.getConstructors()) {
                    if (constructor.getParameterCount() == 2) {
                        dynamic = constructor;
                        break;
                    }
                }
                dynamicGetValue = dynamicClass.getMethod("getValue");
                hasDataVersion = true;
            } catch (Throwable ignored) {
            }
            failure = false;
            return true;
        } catch (Exception e) {
            failure = true;
            exception = e;
            return false;
        }
    }

    public static String toBase64(ItemStack item) {
        if (failure) {
            throw new IllegalStateException("Exception in CardboardBox", exception);
        }
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        try {
            Object nmsItem;
            if (modernPaper) {
                nmsItem = item.getClass().getMethod("serializeAsBytes").invoke(item);
            } else {
                nmsItem = craftItemStackAsNMSCopy.invoke(null, item);
                if (nmsItem == null) {
                    return null;
                }
                if (hasDataVersion) {
                    Object compound = nbtTagCompoundConstructor.newInstance();
                    itemStackSave.invoke(nmsItem, compound);
                    nbtTagCompoundSetInt.invoke(compound, "DataVersion", dataVersion);
                    nmsItem = nbtCompressedStreamToolsWrite.invoke(null, compound, (OutputStream) null);
                } else {
                    nmsItem = nbtCompressedStreamToolsWrite.invoke(null, itemStackSave.invoke(nmsItem, nbtTagCompoundConstructor.newInstance()), (OutputStream) null);
                }
            }
            return Base64.getEncoder().encodeToString((byte[]) nmsItem);
        } catch (Exception e) {
            throw new IllegalStateException("Exception in CardboardBox", e);
        }
    }

    public static ItemStack fromBase64(byte[] data) {
        if (failure) {
            throw new IllegalStateException("Exception in CardboardBox", exception);
        }
        if (data == null) {
            return new ItemStack(Material.AIR);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            Object nmsItem;
            if (modernPaper) {
                nmsItem = ItemStack.class.getDeclaredMethod("deserializeBytes", byte[].class).invoke(null, decoded);
            } else {
                Object nbtTagCompound = nbtCompressedStreamToolsRead.invoke(null, new ByteArrayInputStream(decoded), (InputStream) nbtAccounterUnlimitedHeap.invoke(null));
                if (itemStackConstructor != null) {
                    nmsItem = itemStackConstructor.newInstance(nbtTagCompound);
                } else {
                    if (hasDataVersion && (int) nbtTagCompoundGetInt.invoke(nbtTagCompound, "DataVersion") < OLD_DATA_VERSION) {
                        nbtTagCompound = dynamicGetValue.invoke(dataFixerUpdate.invoke(dataConverterRegistryDataFixer, dataConverterTypesItemStack, newDynamic(nbtTagCompound), (int) nbtTagCompoundGetInt.invoke(nbtTagCompound, "DataVersion"), OLD_DATA_VERSION));
                    }
                    nmsItem = itemStackFromCompound.invoke(null, nbtTagCompound);
                }
            }
            return (ItemStack) craftItemStackAsCraftMirror.invoke(null, nmsItem);
        } catch (Exception e) {
            throw new IllegalStateException("Exception in CardboardBox", e);
        }
    }

    private static Object newDynamic(Object nbtTagCompound) throws Exception {
        return dynamic.newInstance(dynamicOpsNBT, nbtTagCompound);
    }

    public static byte[] playerInventoryToBase64(ItemStack playerInventory) throws IllegalStateException {
        try {
            String content = toBase64(playerInventory);
            return Base64.getEncoder().encode(content.getBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save player inventory.", e);
        }
    }

    public static String itemStackArrayToBase64(ItemStack[] items) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);

            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save item stacks.", e);
        }
    }

    public static String toBase64(Inventory inventory) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(inventory.getSize());

            for (int i = 0; i < inventory.getSize(); i++) {
                dataOutput.writeObject(inventory.getItem(i));
            }

            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save item stacks.", e);
        }
    }

    public static Inventory inventoryFromBase64(String data) throws IllegalStateException {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            Inventory inventory = Bukkit.getServer().createInventory(null, dataInput.readInt());

            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, (ItemStack) dataInput.readObject());
            }

            dataInput.close();
            return inventory;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decode class type.", e);
        }
    }

    public static ItemStack[] itemStackArrayFromBase64(String data) throws IllegalStateException {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];

            for (int i = 0; i < items.length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decode class type.", e);
        }
    }
}
