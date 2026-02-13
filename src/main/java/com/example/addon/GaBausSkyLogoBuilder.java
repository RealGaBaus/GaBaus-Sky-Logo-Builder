package com.example.addon;

import com.example.addon.modules.AutoRestock;
import com.example.addon.modules.BaseGuardian;
import com.example.addon.modules.LogoBuilder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class GaBausSkyLogoBuilder extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("GaBaus Sky-logo Builder");

    @Override
    public void onInitialize() {
        LOG.info("Initializing GaBaus Sky-logo Builder Addon");

        // Modules
        Modules.get().add(new LogoBuilder());
        Modules.get().add(new AutoRestock());
        Modules.get().add(new BaseGuardian());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("RealGaBaus", "GaBaus-Sky-Logo-Builder");
    }
}