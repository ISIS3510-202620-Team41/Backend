package com.group41.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Sirve las fotos subidas en /uploads/** desde la carpeta local.
 * En produccion esto lo reemplaza un CDN y esta clase desaparece.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public WebConfig(@Value("${app.storage.upload-dir}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path path = Paths.get(uploadDir).toAbsolutePath().normalize();

        registry.addResourceHandler("/uploads/activity-*.jpg")
                .addResourceLocations(path.toUri().toString())
                .setCacheControl(
                        CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic()
                );

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(path.toUri().toString());
    }
}