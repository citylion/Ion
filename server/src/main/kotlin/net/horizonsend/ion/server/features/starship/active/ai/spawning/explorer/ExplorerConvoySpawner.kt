package net.horizonsend.ion.server.features.starship.active.ai.spawning.explorer

import net.horizonsend.ion.server.IonServer
import net.horizonsend.ion.server.configuration.AIShipConfiguration
import net.horizonsend.ion.server.features.starship.active.ai.spawning.template.BasicSpawner
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.World

object ExplorerConvoySpawner : BasicSpawner(
	"EXPLORER_CONVOY",
	IonServer.aiShipConfiguration.spawners::explorationConvoy,
) {
	val defaultConfiguration = AIShipConfiguration.AISpawnerConfiguration()

	override fun findSpawnLocation(): Location? = ExplorerUtils.findSpawnLocation()

	override fun getStarshipTemplates(world: World): Collection<Pair<AIShipConfiguration.AIStarshipTemplate, Component>> {
		// If the value is null, it is trying to spawn a ship in a world that it is not configured for.
		val worldConfig = configuration.getWorld(world)!!
		val tierIdentifier = worldConfig.tierWeightedRandomList.random()
		val tier = configuration.getTier(tierIdentifier)
		val shipIdentifier = tier.shipsWeightedList.random()
		val name = MiniMessage.miniMessage().deserialize(tier.namesWeightedList.random())

		return listOf(
			IonServer.aiShipConfiguration.getShipTemplate(shipIdentifier) to name,
			IonServer.aiShipConfiguration.getShipTemplate(shipIdentifier) to name,
			IonServer.aiShipConfiguration.getShipTemplate(shipIdentifier) to name
		)
	}
}