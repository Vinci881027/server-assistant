package com.linux.ai.serverassistant.config;

import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.SessionAuthenticationSignatureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    private static final String DEFAULT_DEV_ALLOWED_ORIGIN = "http://localhost:5173";

    private final List<String> allowedOrigins;

    public SecurityConfig(
            @Value("${app.security.cors.allowed-origins:}") String configuredAllowedOrigins) {
        String resolvedAllowedOrigins = configuredAllowedOrigins == null ? "" : configuredAllowedOrigins;
        List<String> parsedAllowedOrigins = Arrays.stream(resolvedAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (parsedAllowedOrigins.isEmpty()) {
            if (!resolvedAllowedOrigins.isBlank()) {
                throw new IllegalStateException(
                        "Invalid CORS configuration: app.security.cors.allowed-origins contains no valid origins."
                );
            }
            this.allowedOrigins = List.of(DEFAULT_DEV_ALLOWED_ORIGIN);
            return;
        }

        this.allowedOrigins = parsedAllowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AdminAuthorizationService adminAuthorizationService,
                                           SessionAuthenticationSignatureService sessionAuthenticationSignatureService) throws Exception {
        // Security Improvement: Enable CSRF protection (Cookie-based, suitable for SPA)
        CookieCsrfTokenRepository tokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
        delegate.setCsrfRequestAttributeName("_csrf");

        // Custom CSRF Handler to ensure Token is loaded correctly
        CsrfTokenRequestHandler requestHandler = delegate::handle;
        List<AntPathRequestMatcher> csrfIgnoredMatchers = List.of(
                new AntPathRequestMatcher("/api/login"),
                new AntPathRequestMatcher("/api/status")
        );

        http
            // Enable CSRF protection (exclude login and status check endpoints)
            .csrf(csrf -> csrf
                .csrfTokenRepository(tokenRepository)
                .csrfTokenRequestHandler(requestHandler)
                .ignoringRequestMatchers(csrfIgnoredMatchers.toArray(AntPathRequestMatcher[]::new))
            )
            // Add CSRF Cookie filter
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            // Recover authentication from signed session markers if SecurityContext is unexpectedly empty.
            .addFilterBefore(
                    new SessionUserAuthenticationFilter(adminAuthorizationService, sessionAuthenticationSignatureService),
                    AnonymousAuthenticationFilter.class
            )
            // Enable CORS (using configured whitelist)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // This service uses custom session-based login API only.
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            // Add security response headers
            .headers(headers -> headers
                // Content Security Policy
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'self';")
                )
                // XSS Protection: enable mode=block for legacy browser compatibility
                .xssProtection(xss -> xss
                    .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK)
                )
                // Frame Options
                .frameOptions(frame -> frame.sameOrigin())
                // HSTS (HTTP Strict Transport Security)
                // Spring Security's default requestMatcher is SecureRequestMatcher,
                // so this header is ONLY sent when request.isSecure() == true (i.e., HTTPS).
                // It is safe to configure without an extra condition.
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                    .preload(true)
                )
                // Referrer Policy
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            )
            .authorizeHttpRequests(auth -> auth
                // Allow public access to login interface and error pages
                .requestMatchers(new AntPathRequestMatcher("/api/login")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/status")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/ping")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/error")).permitAll()
                // Frontend static resources (Vue build output)
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg", "/vite.svg").permitAll()
                // Restrict Admin API to users with ADMIN role
                .requestMatchers(new AntPathRequestMatcher("/api/admin/**")).hasRole("ADMIN")
                // All other API requests require authentication
                .requestMatchers(new AntPathRequestMatcher("/api/**")).authenticated()
                // Fail closed for any unclassified endpoint
                .anyRequest().denyAll()
            );

        return http.build();
    }

    /**
     * CORS Configuration: Restrict to whitelist domains
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Allowed origins (whitelist): configured by app.security.cors.allowed-origins
        configuration.setAllowedOrigins(allowedOrigins);

        // Allowed HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // Allowed request headers (explicit whitelist instead of wildcard)
        configuration.setAllowedHeaders(Arrays.asList(
                "Content-Type", "Authorization", "X-XSRF-TOKEN", "X-Requested-With",
                "Accept", "Origin", "Cache-Control"));

        // Allow credentials (Cookies)
        configuration.setAllowCredentials(true);

        // Max age for preflight requests (seconds)
        configuration.setMaxAge(3600L);

        // Exposed response headers
        configuration.setExposedHeaders(Arrays.asList("X-CSRF-TOKEN"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * CSRF Cookie Filter
     * Ensures that the CSRF Token is loaded into the Cookie for every request.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                throws jakarta.servlet.ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            // Trigger token generation and set it in the Cookie
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Fallback filter:
     * If session has user identity but SecurityContext is empty/anonymous,
     * reconstruct Authentication to prevent false 401/403 on /api/**.
     */
    static final class SessionUserAuthenticationFilter extends OncePerRequestFilter {

        private final AdminAuthorizationService adminAuthorizationService;
        private final SessionAuthenticationSignatureService sessionAuthenticationSignatureService;

        SessionUserAuthenticationFilter(AdminAuthorizationService adminAuthorizationService,
                                        SessionAuthenticationSignatureService sessionAuthenticationSignatureService) {
            this.adminAuthorizationService = adminAuthorizationService;
            this.sessionAuthenticationSignatureService = sessionAuthenticationSignatureService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                throws jakarta.servlet.ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean hasRealAuth = auth != null
                    && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken);

            if (!hasRealAuth) {
                HttpSession session = request.getSession(false);
                Object rawSessionUser = (session != null)
                        ? session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE)
                        : null;
                Object rawSessionUserSignature = (session != null)
                        ? session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE)
                        : null;

                String sessionUser = (rawSessionUser instanceof String user) ? user : null;
                String sessionUserSignature = (rawSessionUserSignature instanceof String signature) ? signature : null;
                boolean hasSessionMarkers = rawSessionUser != null || rawSessionUserSignature != null;

                boolean validSignedSession = session != null
                        && sessionAuthenticationSignatureService.isValid(
                                sessionUser,
                                session.getId(),
                                sessionUserSignature
                        );

                if (validSignedSession) {
                    boolean isAdmin = adminAuthorizationService.isAdmin(sessionUser);
                    String[] roles = isAdmin ? new String[]{"USER", "ADMIN"} : new String[]{"USER"};

                    UserDetails userDetails = User.withUsername(sessionUser)
                            .password("")
                            .roles(roles)
                            .build();

                    Authentication rebuiltAuth =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                    SecurityContextHolder.getContext().setAuthentication(rebuiltAuth);
                } else if (session != null && hasSessionMarkers) {
                    // Remove tampered/stale markers to prevent repeated privilege spoof attempts.
                    session.removeAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE);
                    session.removeAttribute(SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE);
                }
            }

            filterChain.doFilter(request, response);
        }
    }
}
