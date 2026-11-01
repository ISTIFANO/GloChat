package com.marketchat.commerce.catalog.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serves catalog media (product photos, merchant logos and covers) under /api/catalog/media/**,
 * public and cacheable, routed by the gateway like the rest of the catalog.
 * Later: move to object storage / CDN without changing the URLs the API returns.
 */
@Configuration
class CatalogMediaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/catalog/media/**")
                .addResourceLocations("classpath:/media/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
