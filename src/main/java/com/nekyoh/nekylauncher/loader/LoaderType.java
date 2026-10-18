package com.nekyoh.nekylauncher.loader;

import java.awt.Color;

public enum LoaderType {
    VANILLA("Vanilla", "minecraft", new Color(0x4ADE80), null),
    FABRIC("Fabric", "fabric", new Color(0xDBB98A), "FabricMC"),
    QUILT("Quilt", "quilt", new Color(0xA78BFA), "QuiltMC"),
    FORGE("Forge", "forge", new Color(0x7C93C7), "MinecraftForge"),
    NEOFORGE("NeoForge", "neoforge", new Color(0xF59E0B), "neoforged");

    public final String display;
    /** Loader name as used by Modrinth. */
    public final String modrinth;
    public final Color color;
    /** GitHub organisation of the project; its avatar is the project's own logo. */
    private final String githubOrg;

    LoaderType(String display, String modrinth, Color color, String githubOrg) {
        this.display = display;
        this.modrinth = modrinth;
        this.color = color;
        this.githubOrg = githubOrg;
    }

    /**
     * Official logo of the loader project (anvil for Forge, fox for NeoForge...), fetched at runtime
     * from the project's own GitHub organisation and cached on disk. Null for vanilla.
     */
    public String iconUrl() {
        return githubOrg == null ? null : "https://github.com/" + githubOrg + ".png?size=128";
    }

    public boolean isModded() { return this != VANILLA; }

    public static LoaderType of(String s) {
        if (s == null) return VANILLA;
        for (LoaderType t : values()) {
            if (t.name().equalsIgnoreCase(s) || t.modrinth.equalsIgnoreCase(s) || t.display.equalsIgnoreCase(s)) return t;
        }
        return VANILLA;
    }

    @Override
    public String toString() { return display; }
}
