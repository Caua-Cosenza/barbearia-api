package com.cauacosenza.barbearia.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS com origem explicita, nunca {@code "*"}.
 *
 * <p>As origens vem de {@code CORS_ORIGIN} e o {@code EnvironmentValidator} ja
 * recusou o boot se a variavel estiver ausente ou nao for uma lista de URLs
 * http/https. Nao ha default em producao: uma API que nasce aceitando qualquer
 * origem por descuido de configuracao e pior do que uma que nao sobe.
 *
 * <p><b>{@code allowCredentials} e {@code true} e isso tem consequencia.</b> O
 * frontend ja usa {@code withCredentials: true} no axios, e a Fase 6 vai pôr o
 * JWT em cookie {@code HttpOnly}. Com credenciais habilitadas o navegador
 * <i>proibe</i> {@code Access-Control-Allow-Origin: *} — a combinacao e
 * recusada pela propria especificacao. Portanto o curinga aqui nao seria so
 * uma ma pratica: quebraria o login antes de existir.
 *
 * <p>Metodos e cabecalhos espelham o {@code fastifyCors} do Node. {@code PUT} e
 * {@code DELETE} ainda nao tem rota publica; entram agora porque sao as rotas
 * de admin da Fase 6 e deixa-los de fora renderia um erro de CORS
 * desconcertante — a requisicao falha no preflight, antes de qualquer codigo
 * de autenticacao rodar.
 */
@Configuration
public class CorsConfig {

	/** Em segundos: quanto o navegador pode cachear o preflight. */
	private static final long CACHE_DO_PREFLIGHT = 3600;

	private final List<String> origensPermitidas;

	public CorsConfig(@Value("${app.cors.allowed-origins}") String origens) {
		this.origensPermitidas = Arrays.stream(origens.split(","))
				.map(String::trim)
				.filter(origem -> !origem.isEmpty())
				.toList();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cors = new CorsConfiguration();
		// setAllowedOrigins e nao setAllowedOriginPatterns: o primeiro so
		// aceita origem literal. O segundo admite curinga e, combinado com
		// allowCredentials, devolveria de volta qualquer Origin que chegasse.
		cors.setAllowedOrigins(this.origensPermitidas);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Content-Type", "Authorization"));
		cors.setAllowCredentials(true);
		cors.setMaxAge(CACHE_DO_PREFLIGHT);

		UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
		fonte.registerCorsConfiguration("/api/**", cors);
		return fonte;
	}

}
