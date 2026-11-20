package com.typewritermc.roadnetwork.pathfinding.pathetic

import com.typewritermc.core.extension.annotations.Factory
import com.typewritermc.core.extension.annotations.Parameter
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.roadnetwork.roadNetworkMaxDistance
import de.bsommerfeld.pathetic.api.factory.PathfinderFactory
import de.bsommerfeld.pathetic.api.pathing.NeighborStrategies
import de.bsommerfeld.pathetic.api.pathing.Pathfinder
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicStrategies
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicWeights
import de.bsommerfeld.pathetic.api.pathing.processing.CostProcessor
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory
import kotlin.math.ceil

@Singleton
fun providePathFactory(): PathfinderFactory {
    return AStarPathfinderFactory()
}

@Factory
fun providePathConfiguration(): PathfinderConfiguration.PathfinderConfigurationBuilder {
    return PathfinderConfiguration.builder()
        .provider(SnapshotPointProvider())
        .heuristicWeights(HeuristicWeights.create(1.0, 0.4, 4.0, 1.2))
        .heuristicStrategy(HeuristicStrategies.LINEAR)
        .neighborStrategy(NeighborStrategies.DIAGONAL_3D)
        .maxLength(ceil(roadNetworkMaxDistance).toInt() + 2)
        .pathfindingHooks(emptyList())
        .fallback(true)
        .async(false)
}

@Factory
fun providePathfinder(
    @Parameter validationProcessors: List<ValidationProcessor>,
    @Parameter costProcessors: List<CostProcessor>,
    factory: PathfinderFactory,
    configuration: PathfinderConfiguration.PathfinderConfigurationBuilder
): Pathfinder {
    val pathfinderConfiguration = configuration
        .validationProcessors(validationProcessors)
        .costProcessor(costProcessors)
        .build()

    return factory.createPathfinder(pathfinderConfiguration)
}
