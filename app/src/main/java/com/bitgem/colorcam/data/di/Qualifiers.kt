package com.bitgem.colorcam.data.di

import javax.inject.Qualifier

/** The single background thread that runs the CameraX `ImageAnalysis` pipeline. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalysisExecutor
