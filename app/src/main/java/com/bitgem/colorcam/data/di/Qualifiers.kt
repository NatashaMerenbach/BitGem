package com.bitgem.colorcam.data.di

import javax.inject.Qualifier

/** The single background thread that runs the CameraX `ImageAnalysis` pipeline. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalysisExecutor

/** Coroutine view of [AnalysisExecutor] — same single thread, so scratch buffers stay safe. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalysisDispatcher
