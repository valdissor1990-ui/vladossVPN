package com.vpnproxy.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt-модуль приложения.
 * Все зависимости используют @Inject constructor и не требуют явных провайдеров.
 * Модуль оставлен как точка расширения (например, для будущих кастомных биндингов).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
