package com.njguidi14.anvilsolver;

import com.njguidi14.anvilsolver.client.AnvilSolverKeys;
import com.njguidi14.anvilsolver.config.AnvilSolverConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

// This mod is a read-only client overlay: it never touches world state and has
// nothing to contribute on a dedicated server. Restricting the @Mod entrypoint to
// Dist.CLIENT means FML will not even construct this class on a dedicated server,
// so the mod can never become a server-side requirement.
@Mod(value = AnvilSolverMod.MOD_ID, dist = Dist.CLIENT)
public final class AnvilSolverMod {

    public static final String MOD_ID = "anvilsolver";

    public AnvilSolverMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, AnvilSolverConfig.CLIENT_SPEC);

        // RegisterKeyMappingsEvent is a mod-bus event. Registering it here rather than via
        // @EventBusSubscriber(bus = Bus.MOD) avoids that annotation element, which is deprecated
        // and marked for removal in NeoForge 21.1. Safe to reference a client class directly:
        // this whole mod is @Mod(dist = Dist.CLIENT), so nothing here loads on a dedicated server.
        modBus.addListener(AnvilSolverKeys::onRegisterKeyMappings);
    }
}
