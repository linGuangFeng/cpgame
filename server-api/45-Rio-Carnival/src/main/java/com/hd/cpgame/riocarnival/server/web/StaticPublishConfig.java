package com.hd.cpgame.riocarnival.server.web;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticPublishConfig implements WebMvcConfigurer {
    private final String publish;
    public StaticPublishConfig(@Value("${rio.publish-directory:../../../publish/45-rio-carnival}") String publish){this.publish=publish;}
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry){
        String location=Paths.get(publish).toAbsolutePath().normalize().toUri().toString();if(!location.endsWith("/"))location+="/";
        registry.addResourceHandler("/**").addResourceLocations(location).setCachePeriod(0);
    }
    @Override public void addViewControllers(ViewControllerRegistry registry){registry.addViewController("/").setViewName("forward:/index.html");}
}
