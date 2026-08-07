package com.njguidi14.anvilsolver;

import com.njguidi14.anvilsolver.client.AnvilSolverKeys;
import com.njguidi14.anvilsolver.config.AnvilSolverConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This mod is a read-only client overlay: it never touches world state and has
// nothing to contribute on a dedicated server. Restricting the @Mod entrypoint to
// Dist.CLIENT means FML will not even construct this class on a dedicated server,
// so the mod can never become a server-side requirement.
@Mod(value = AnvilSolverMod.MOD_ID, dist = Dist.CLIENT)
public final class AnvilSolverMod {

    public static final String MOD_ID = "anvilsolver";

    public AnvilSolverMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, AnvilSolverConfig.CLIENT_SPEC);

        // Puts a "Config" button on this mod's entry in the Mods list, opening NeoForge's own
        // generated screen for CLIENT_SPEC. Without this the only way to change an option is to
        // hand-edit config/anvilsolver-client.toml, which most players will never do.
        //
        // ConfigurationScreen and IConfigScreenFactory are CLIENT-ONLY classes. Referencing them
        // from the mod constructor is safe here *only* because the whole mod is
        // @Mod(dist = Dist.CLIENT) (see above), so FML never constructs this class on a dedicated
        // server and these types are never loaded there. If that dist restriction is ever removed,
        // this call must move into a client-only class behind a dist check, or a dedicated server
        // will crash with NoClassDefFoundError while constructing the mod.
        //
        // The labels the generated screen shows come from lang keys of the form
        // "anvilsolver.configuration.<option>" (plus ".tooltip"); they live in
        // assets/anvilsolver/lang/en_us.json. Renaming a config key there means renaming the lang
        // key too, or the screen shows the raw key and NeoForge logs it as a missing translation.
        modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (mc, parent) -> new ConfigurationScreen(modContainer, parent));

        // RegisterKeyMappingsEvent is a mod-bus event. Registering it here rather than via
        // @EventBusSubscriber(bus = Bus.MOD) avoids that annotation element, which is deprecated
        // and marked for removal in NeoForge 21.1. Safe to reference a client class directly:
        // this whole mod is @Mod(dist = Dist.CLIENT), so nothing here loads on a dedicated server.
        modBus.addListener(AnvilSolverKeys::onRegisterKeyMappings);
    }
}
