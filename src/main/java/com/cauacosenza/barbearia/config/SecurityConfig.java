package com.cauacosenza.barbearia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuracao minima de seguranca da Fase 1.
 *
 * <p>Sem isso, a auto-configuracao do Spring Security protege tudo com HTTP
 * Basic e uma senha gerada a cada boot — inclusive {@code /actuator/health},
 * que precisa responder sem credencial para o healthcheck do container e do
 * orquestrador funcionarem.
 *
 * <p>O escopo aqui termina nesse ponto: autenticacao JWT, regras por papel,
 * CORS e rate limit entram nas fases seguintes. Tudo que nao for
 * {@code /actuator/health} ou {@code /actuator/info} continua negado, para que
 * nenhuma rota nasca aberta por descuido.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				// API stateless: sem sessao e sem CSRF baseado em sessao.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
						.anyRequest().denyAll())
				.build();
	}

}
