package com.bitgem.colorcam.data.di

import com.bitgem.colorcam.data.camera.ElapsedTimeSource
import com.bitgem.colorcam.data.repository.ColorRepositoryImpl
import com.bitgem.colorcam.domain.analysis.AnalysisConfig
import com.bitgem.colorcam.domain.analysis.ColorQuantizer
import com.bitgem.colorcam.domain.analysis.ColorSmoother
import com.bitgem.colorcam.domain.analysis.KMeansColorQuantizer
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.repository.ColorRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * Wires the domain's abstractions to their implementations.
 *
 * The algorithm classes are provided here rather than annotated with `@Inject` so that the
 * domain module stays free of DI concerns and so that tests can construct them with any
 * configuration they like.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule {

    @Provides
    @Singleton
    fun provideAnalysisConfig(): AnalysisConfig = AnalysisConfig()

    @Provides
    @Singleton
    fun provideYuv420Converter(): Yuv420Converter = Yuv420Converter()

    @Provides
    @Singleton
    fun provideColorQuantizer(config: AnalysisConfig): ColorQuantizer = KMeansColorQuantizer(config)

    @Provides
    @Singleton
    fun provideColorSmoother(config: AnalysisConfig): ColorSmoother =
        ColorSmoother(alpha = config.temporalAlpha, matchDistance = config.temporalMatchDistance)

    @Provides
    @Singleton
    fun provideElapsedTimeSource(): ElapsedTimeSource = ElapsedTimeSource.SYSTEM

    /**
     * A dedicated single-thread executor for the analysis pipeline.
     *
     * Single-threaded on purpose: the pipeline owns mutable scratch buffers, and one thread
     * is more than enough for 10 analyses per second. CameraX is told to keep only the latest
     * frame, so a slow analysis drops frames instead of queueing them.
     */
    @Provides
    @Singleton
    @AnalysisExecutor
    fun provideAnalysisExecutor(): Executor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "color-analysis") }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindColorRepository(impl: ColorRepositoryImpl): ColorRepository
}

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    /**
     * Hands the CameraX analyzer to the UI.
     *
     * The UI never sees `ColorRepositoryImpl`: it asks for an `ImageAnalysis.Analyzer` and
     * Hilt resolves it to the repository that also owns the result flow. This keeps the
     * data-layer type out of the presentation layer while still letting the composable attach
     * the analyzer to its `ImageAnalysis` use case (where the CameraX binding belongs).
     */
    @Provides
    @Singleton
    fun provideAnalyzer(repository: ColorRepositoryImpl): androidx.camera.core.ImageAnalysis.Analyzer =
        repository
}
