/*
 * This file is part of Almura.
 *
 * Copyright (c) AlmuraDev <https://github.com/AlmuraDev/>
 *
 * All Rights Reserved.
 */
package com.almuradev.almura;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

@IFMLLoadingPlugin.MCVersion("1.12.2")
public final class AlmuraLoadingPlugin implements IFMLLoadingPlugin {

    public AlmuraLoadingPlugin() {
        MixinBootstrap.init();
        Mixins.addConfigurations(
                "mixins.almura.core.json",
                "mixins.almura.accessors.json",
                "mixins.almura.content.json",
                "mixins.almura.content.block.json",
                "mixins.almura.content.block.horizontal.json",
                "mixins.almura.content.blocksoundgroup.json",
                "mixins.almura.content.item.food.json",
                "mixins.almura.content.item.json",
                "mixins.almura.content.item.seed.json",
                "mixins.almura.content.itemgroup.json",
                "mixins.almura.content.mapcolor.json",
                "mixins.almura.content.material.json",
                "mixins.almura.feature.animal.json",
                "mixins.almura.feature.cache.json",
                "mixins.almura.feature.healthbar.json",
                "mixins.almura.feature.almanac.json",
                "mixins.almura.feature.sign.json",
                "mixins.almura.feature.title.json",
                "mixins.almura.feature.biome.json",
                "mixins.almura.feature.offhand.json",
                "mixins.almura.feature.nick.json",
                "mixins.almura.feature.worldload.json"
        );
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"com.almuradev.almura.asm.transformer.NeiJeiProxyTransformer"};
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return "com.almuradev.almura.asm.transformer.AlmuraAccessTransformer";
    }
}
