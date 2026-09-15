package com.cpgame.clubgoddess.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiWebConfig implements WebMvcConfigurer {
    private final SignatureService signatureService;
    @Value("${api.signature.required:true}") private boolean signatureRequired;
    @Value("${api.signature.clock-tolerance-ms:300000}") private long tolerance;
    @Value("${demo.publish-directory:}") private String publishDirectory;

    public ApiWebConfig(SignatureService signatureService) { this.signatureService = signatureService; }

    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOriginPatterns("*").allowedMethods("GET", "POST", "OPTIONS").allowedHeaders("*");
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!publishDirectory.isBlank()) {
            String location = Path.of(publishDirectory).toAbsolutePath().normalize().toUri().toString();
            if (!location.endsWith("/")) location += "/";
            registry.addResourceHandler("/**").addResourceLocations(location);
        }
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                if (!signatureRequired || !req.getRequestURI().startsWith("/cp/") || "OPTIONS".equals(req.getMethod())) return true;
                String expire = req.getParameter("expire");
                String actual = req.getParameter("signapt");
                boolean validTime = false;
                try { validTime = expire != null && Math.abs(System.currentTimeMillis() - Long.parseLong(expire)) <= tolerance; }
                catch (NumberFormatException ignored) {}
                String expected = expire == null ? "" : signatureService.sign(req.getParameterMap(), expire);
                if (validTime && expected.equalsIgnoreCase(actual == null ? "" : actual)) return true;
                res.setStatus(200); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.setCharacterEncoding("UTF-8");
                res.getWriter().write("{\"code\":40101,\"msg\":\"invalid request signature\",\"time\":\"" + (System.currentTimeMillis()/1000) + "\",\"data\":null}");
                return false;
            }
        });
    }
}
