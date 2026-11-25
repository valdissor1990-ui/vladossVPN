package com.vpnproxy.di

import com.vpnproxy.core.services.HttpServiceProbe
import com.vpnproxy.core.services.ServiceProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль приложения.
 * Почти все зависимости используют @Inject constructor; здесь — только биндинги
 * интерфейсов, которым нужна явная реализация.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Боевая проба: ходит только через локальный mixed inbound запущенного ядра.
     * Вынесена в провайдер, чтобы в тестах подменять её интерфейсом [ServiceProbe],
     * а не мутабельным полем синглтона.
     */
    @Provides
    @Singleton
    fun provideServiceProbe(): ServiceProbe = HttpServiceProbe()
}
