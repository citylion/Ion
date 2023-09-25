package net.horizonsend.ion.server.features.starship.control.controllers.ai

import net.horizonsend.ion.server.features.space.Space
import net.horizonsend.ion.server.features.starship.active.ActiveControlledStarship
import net.horizonsend.ion.server.features.starship.active.ActiveStarship
import net.horizonsend.ion.server.features.starship.control.controllers.Controller
import net.horizonsend.ion.server.features.starship.control.controllers.ai.interfaces.CombatController
import net.horizonsend.ion.server.features.starship.control.movement.AIControlUtils
import net.horizonsend.ion.server.features.starship.control.movement.StarshipCruising
import net.horizonsend.ion.server.miscellaneous.utils.CARDINAL_BLOCK_FACES
import net.horizonsend.ion.server.miscellaneous.utils.Tasks
import net.horizonsend.ion.server.miscellaneous.utils.distance
import net.horizonsend.ion.server.miscellaneous.utils.vectorToBlockFace
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.jvm.optionals.getOrNull

/**
 * This class is designed for an easy low block count opponent
 * Assumes:
 *  - No weapon sets
 *  - Forward only weaponry
 *
 * It does not use DC, only shift flies and cruises
 **/
class StarfighterCombatController(
	starship: ActiveStarship,
	override var target: ActiveStarship,
	private val previousController: Controller,
	private var aggressivenessLevel: AggressivenessLevel
) : AIController(starship, "combat"), CombatController {
	override val pilotName: Component = starship.getDisplayNameComponent().append(text(" [AGGRESSIVE]", NamedTextColor.RED))
	override fun getDisplayName(): Component = starship.getDisplayNameComponent()
	override fun getTargetLocation(): Location = target.centerOfMass.toLocation(target.world)

	private val shields get() = starship.shields
	private val shieldCount get() = shields.size
	private val averageHealth get() = shields.sumOf { it.powerRatio } / shieldCount.toDouble()

	/** The location that should be navigated towards */
	private var locationObjective: Location = target.centerOfMass.toLocation(target.world)

	/** Current state of the AI */
	var state: State = State.FOCUS_LOCATION

	enum class State {
		COMBAT, /** Focus on the combat loop */
		FOCUS_LOCATION, /** Only worry about moving towards the location objective */
	}

	/**
	 * Function to find the standoff distance when in combat
	 *
	 * Based on shield health
	 **/
	private fun getStandoffDistance() : Double {
		val min = 25.0

		val shieldMultiplier = averageHealth / 10.0

		return min + (1/shieldMultiplier)
	}

	/**
	 * Gets information about the target, updates the immediate navigation goal
	 *
	 * If target has moved out of range, deals with that scenario
	 **/
	private fun checkOnTarget(): Boolean {
		val location = getCenter()
		val targetLocation = target.centerOfMass.toVector()

		if (target.world != starship.world) {
			// If null, they've likely jumped to hyperspace, disengage
			val planet = Space.planetWorldCache[target.world].getOrNull() ?: return false

			// Only if it is very aggressive, follow the target to the planet they entered
			if (aggressivenessLevel.ordinal >= AggressivenessLevel.HIGH.ordinal) {
				state = State.FOCUS_LOCATION

				locationObjective = planet.location.toLocation(planet.spaceWorld!!)
				return true
			}

			// Don't follow to planet if low aggressiveness
			return false
		}

		val distance = distance(location.toVector(), targetLocation)

		return when {
			// Check if they've moved out of range
			(distance > aggressivenessLevel.engagementDistance) -> {

				// Keep pursuing if aggressive, else out of range and should disengage
				if (aggressivenessLevel.ordinal >= AggressivenessLevel.HIGH.ordinal) {
					locationObjective = targetLocation.toLocation(target.world)
					state = State.FOCUS_LOCATION
					return true
				}

				false
			}

			// They are getting far away so focus on moving towards them
			(distance in 500.0..1500.0) -> {
				locationObjective = targetLocation.toLocation(target.world)
				state = State.FOCUS_LOCATION
				true
			}

			// They are in range, in the same world, should continue to engage
			else -> {
				// The combat loop will handle the location gathering
				state = State.COMBAT
				true
			}
		}
	}

	private fun disengage() {
		if (aggressivenessLevel.ordinal >= AggressivenessLevel.HIGH.ordinal) {
			val nextTarget = findNextTarget()

			if (nextTarget == null) fallback()

			else target = nextTarget
			return
		}

		fallback()
	}

	private fun findNextTarget(): ActiveStarship? {
		val nearbyShips = getNearbyShips(0.0, aggressivenessLevel.engagementDistance) { starship, _ ->
			starship.controller !is AIController
		}

		if (nearbyShips.isEmpty()) return null

		return nearbyShips.firstOrNull()
	}

	/** Returns to previous controller if there is no target left **/
	private fun fallback() {
		starship.controller = previousController
		return
	}

	/** Returns the direction to face once its reached its location objective */
	private fun getImmediateLocation(): Vector {
		val closestPoint = getClosestAxisPoint()

		locationObjective = closestPoint.toLocation(starship.world)
		val directionToTarget = target.centerOfMass.toVector().subtract(closestPoint)

		return directionToTarget
	}

	/** Finds a location in the cardinal directions from the target at the engagement distance */
	private fun getClosestAxisPoint(): Vector {
		val shipLocation = getCenter().toVector()
		val targetLocation = target.centerOfMass.toVector()

		val cardinalOffsets = CARDINAL_BLOCK_FACES.map { it.direction.multiply(getStandoffDistance()) }
		val points = cardinalOffsets.map { targetLocation.clone().add(it) }

		return points.minBy { it.distance(shipLocation) }
	}


	/**
	 * Goals of this AI:
	 *
	 * Position itself at a standoff distance along a cardinal direction from the target
	 * This will allow it to engage with limited firing arc weaponry
	 *
	 * If no target is found, it will transition into a passive state
	 */
	override fun tick() = Tasks.async {
		val ok = checkOnTarget()

		if (!ok) {
			disengage()
			return@async
		}

		when (state) {
			State.FOCUS_LOCATION -> navigationLoop()
			State.COMBAT -> combatLoop()
		}
	}

	private fun combatLoop() {
		// Get the closest axis
		val direction = getImmediateLocation()
		val blockFace = vectorToBlockFace(direction)

		starship as ActiveControlledStarship

		Tasks.sync {
			AIControlUtils.faceDirection(this, blockFace)
			AIControlUtils.shiftFlyToLocation(this, locationObjective)
			StarshipCruising.stopCruising(this, starship)
			AIControlUtils.shootInDirection(this, direction, leftClick = false, target = getTargetLocation().toVector())
			AIControlUtils.shootInDirection(this, direction, leftClick = true, target = getTargetLocation().toVector())
		}
	}

	/** Shift flies towards location */
	private fun navigationLoop() {
		val targetLocation = locationObjective
		val location = getCenter()
		val distance = distance(location.toVector(), targetLocation.toVector())

		val direction = targetLocation.toVector().subtract(location.toVector())

		Tasks.sync {
			starship as ActiveControlledStarship
			starship.speedLimit = -1

			if (distance >= 500) {
				StarshipCruising.startCruising(this, starship, direction)
				AIControlUtils.shiftFlyToLocation(this, locationObjective)

				return@sync
			}

			StarshipCruising.stopCruising(this, starship)
			AIControlUtils.shiftFlyToLocation(this, locationObjective)
		}
	}
}