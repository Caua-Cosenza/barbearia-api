package com.cauacosenza.barbearia.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.security.JwtCookieAuthenticationFilter;
import com.cauacosenza.barbearia.security.RespostaDeSegurancaNoEnvelope;

/**
 * Quem pode o que.
 *
 * <p>A lista e <b>branca</b>: cada caminho liberado esta escrito, e
 * {@code anyRequest().denyAll()} fecha o resto. A ordem importa — com
 * {@code permitAll()} no fim, uma rota criada depois nasceria aberta ate
 * alguem lembrar de protege-la. Aqui ela nasce negada.
 *
 * <h2>CSRF desabilitado, e por que isso se sustenta</h2>
 *
 * <p>Com o JWT em cookie, a API passa a ser vulneravel a CSRF em principio: o
 * navegador anexa o cookie sozinho, inclusive numa requisicao disparada por
 * outro site. O que sustenta a decisao:
 *
 * <ul>
 *   <li>toda rota de escrita exige {@code Content-Type: application/json}, o
 *       que <b>obriga o preflight</b> CORS — e o preflight e recusado para
 *       qualquer origem fora de {@code CORS_ORIGIN}. Um formulario HTML, que
 *       escaparia do preflight, so consegue enviar
 *       {@code application/x-www-form-urlencoded}, que esta API nao aceita;
 *   <li>em producao os cookies sao {@code SameSite=None} — necessario porque
 *       front e API ficam em subdominios diferentes — mas em desenvolvimento
 *       sao {@code Strict};
 *   <li>e o que o Node faz hoje, e o criterio desta fase e equivalencia.
 * </ul>
 *
 * <p>Vale anotar como divida: {@code SameSite=None} tira a camada que o
 * navegador daria de graca, e a defesa fica inteiramente no CORS. Um token
 * CSRF por sessao seria mais robusto e exigiria mudar o frontend.
 *
 * <h2>Stateless</h2>
 *
 * <p>Sem {@code JSESSIONID}, sem estado no servidor: cada requisicao se
 * autentica sozinha pelo cookie. E o que permite rodar varias instancias sem
 * sessao compartilhada.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * O mesmo custo do {@code PASSWORD_HASH_ROUNDS} do Node.
	 *
	 * <p>Precisa ser identico, e nao apenas "forte o bastante": os hashes ja
	 * gravados em {@code users.password_hash} vieram do Node, e o custo esta
	 * codificado dentro do proprio hash. Um valor diferente aqui nao quebraria
	 * a verificacao das senhas antigas — o bcrypt le o custo do hash — mas
	 * faria senhas novas custarem outra coisa, e as duas aplicacoes
	 * divergiriam durante a convivencia.
	 */
	private static final int CUSTO_BCRYPT = 12;

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(CUSTO_BCRYPT);
	}

	/**
	 * O {@code @Qualifier} nao e decorativo: o
	 * {@code mvcHandlerMappingIntrospector} do Spring MVC tambem implementa
	 * {@link CorsConfigurationSource}, e sem o nome a injecao falha no boot
	 * com "expected single matching bean but found 2".
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
			JwtCookieAuthenticationFilter autenticacaoPorCookie,
			RespostaDeSegurancaNoEnvelope respostaDeErro) throws Exception {

		return http
				.cors(c -> c.configurationSource(cors))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())

				// 401 e 403 no envelope ApiResponse. Sem isto o Spring
				// Security responde corpo vazio e o client.ts do frontend
				// quebra ao ler res.data.success.
				.exceptionHandling(tratamento -> tratamento
						.authenticationEntryPoint(respostaDeErro)
						.accessDeniedHandler(respostaDeErro))

				// Le o cookie access_token antes de a autorizacao decidir.
				// Nunca recusa nada por conta propria -- ver o javadoc do
				// filtro.
				.addFilterBefore(autenticacaoPorCookie, UsernamePasswordAuthenticationFilter.class)

				.authorizeHttpRequests(auth -> auth
						// O preflight nao carrega credencial nenhuma; barra-lo
						// faz toda requisicao do navegador falhar antes de
						// comecar.
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

						// ----- Fase 6: autenticacao -----
						// Publicas porque quem vai logar ainda nao esta
						// autenticado, e quem tem token expirado precisa
						// conseguir sair.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()

						// ----- Fase 6: painel -----
						// Inclui /admin/health, cujo 401 e justamente como o
						// frontend descobre que nao ha sessao.
						.requestMatchers("/api/v1/admin/**").hasRole(Role.ADMIN.name())

						// ----- Fase 5: rotas publicas do site -----
						.requestMatchers(HttpMethod.GET, "/api/v1/services").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/professionals").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/available-times").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/appointments/public").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/cancel/*").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/cancel/*").permitAll()

						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
						.permitAll()

						.anyRequest().denyAll())
				.build();
	}

}
