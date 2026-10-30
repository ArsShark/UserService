package com.innowise.userservice.config;

import com.innowise.userservice.security.InternalKeyAuthenticationFilter;
import com.innowise.userservice.security.ProblemDetailSecurityHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security setup of the User Service: a stateless resource server that is closed by
 * default.
 *
 * <p>The service is an API for other programs, so there are no sessions, no CSRF protection (it
 * protects cookie sessions, and there are none), no login form, no HTTP Basic and no logout
 * page. Every request that no rule opens is denied, so an unknown path is answered by Security
 * and never reaches Spring MVC: an anonymous caller gets 401, an authenticated one 403.
 *
 * <p>Callers bring an access token of the Authentication Service in the header
 * {@code Authorization: Bearer ...}. It is verified here, with the public key of that service
 * (see {@link JwtConfig}), and the claim {@code role} becomes the authority. Both answers of
 * the chain, 401 and 403, are written as {@code ProblemDetail} by
 * {@link ProblemDetailSecurityHandler}.
 *
 * <p>The rules below are the coarse ones: they decide by role and by HTTP method which kind of
 * caller may call an endpoint at all. An endpoint that a {@code USER} may call at all is open to
 * both roles here, and the controller then asks {@code AccessPolicy} whether the resource is
 * his own (method security, {@link EnableMethodSecurity}; the expressions are in
 * {@code AccessRules}). Everything else is for an admin only: the list of all users and of
 * all cards, creating, activating, deactivating and deleting a user, activating a card.
 *
 * <p>{@code /internal/**} is for other services only. It requires the role
 * {@value InternalKeyAuthenticationFilter#ROLE}, which only the
 * {@link InternalKeyAuthenticationFilter} grants, and only for a request with the right internal
 * key. Only the two endpoints of {@code InternalUserController} are opened, each with its method;
 * another method or path under {@code /internal} stays closed. A missing key stops the startup.
 *
 * <p>The ERROR dispatch is permitted: when the servlet container forwards a failed request to the
 * error page, that internal forward must not be denied again, otherwise the client would see
 * 401 or 403 instead of the real status. A direct request to {@code /error} is a normal REQUEST
 * dispatch and stays closed.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(InternalApiProperties.class)
public class SecurityConfig {

  private static final String ADMIN = "ADMIN";
  private static final String USER = "USER";
  private static final String INTERNAL = InternalKeyAuthenticationFilter.ROLE;

  /**
   * Builds the one security filter chain of the service.
   *
   * @param http the builder provided by Spring Security
   * @param internal the settings of the internal endpoints; a missing key fails here
   * @param jwtDecoder verifies the access tokens
   * @param jwtConverter turns a verified token into an authentication with its role
   * @param resolver the exception resolver of Spring MVC; the name is needed because Spring Boot
   *     has more than one bean of this type
   * @return the chain that handles every request
   * @throws Exception if Spring Security cannot build the chain
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
      InternalApiProperties internal,
      JwtDecoder jwtDecoder,
      JwtAuthenticationConverter jwtConverter,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
      throws Exception {
    ProblemDetailSecurityHandler errorHandler = new ProblemDetailSecurityHandler(resolver);
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(handling -> handling
            .authenticationEntryPoint(errorHandler)
            .accessDeniedHandler(errorHandler))
        .oauth2ResourceServer(resourceServer -> resourceServer
            .authenticationEntryPoint(errorHandler)
            .accessDeniedHandler(errorHandler)
            .jwt(jwt -> jwt
                .decoder(jwtDecoder)
                .jwtAuthenticationConverter(jwtConverter)))
        .addFilterBefore(new InternalKeyAuthenticationFilter(internal.apiKey(), errorHandler),
            BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(requests -> requests
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            // Internal endpoints: the Gateway, with the internal key.
            .requestMatchers(HttpMethod.POST, "/internal/users").hasRole(INTERNAL)
            .requestMatchers(HttpMethod.DELETE, "/internal/users/*").hasRole(INTERNAL)
            // Users: the list and every change of the whole user are for an admin only.
            .requestMatchers(HttpMethod.POST, "/api/users").hasRole(ADMIN)
            .requestMatchers(HttpMethod.GET, "/api/users").hasRole(ADMIN)
            .requestMatchers(HttpMethod.PATCH,
                "/api/users/*/activate", "/api/users/*/deactivate").hasRole(ADMIN)
            .requestMatchers(HttpMethod.DELETE, "/api/users/*").hasRole(ADMIN)
            // Own profile: a user reads and replaces his own.
            .requestMatchers(HttpMethod.GET, "/api/users/*").hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.PUT, "/api/users/*").hasAnyRole(ADMIN, USER)
            // Own cards.
            .requestMatchers(HttpMethod.POST, "/api/users/*/cards").hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.GET, "/api/users/*/cards").hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.GET, "/api/cards/*").hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.PUT, "/api/cards/*").hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.PATCH, "/api/cards/*/deactivate")
            .hasAnyRole(ADMIN, USER)
            .requestMatchers(HttpMethod.DELETE, "/api/cards/*").hasAnyRole(ADMIN, USER)
            // The list of all cards and the activation of a card are for an admin only.
            .requestMatchers(HttpMethod.GET, "/api/cards").hasRole(ADMIN)
            .requestMatchers(HttpMethod.PATCH, "/api/cards/*/activate").hasRole(ADMIN)
            .anyRequest().denyAll())
        .build();
  }
}
