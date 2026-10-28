package com.cauacosenza.barbearia.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica a requisicao a partir do cookie {@code access_token}.
 *
 * <p>Nao ha cabecalho {@code Authorization} neste sistema: o token viaja em
 * cookie {@code HttpOnly}, que o JavaScript do painel nao consegue ler nem
 * escrever. O navegador o anexa sozinho, e e o que permite ao frontend nao
 * guardar credencial nenhuma em {@code localStorage}.
 *
 * <p><b>O filtro nunca recusa a requisicao.</b> Sem cookie, com cookie
 * invalido ou expirado, ele simplesmente nao popula o {@code SecurityContext}
 * e segue adiante. Quem decide se aquilo era necessario e a cadeia de
 * autorizacao: as rotas publicas da Fase 5 continuam passando, e as de
 * {@code /admin} caem no {@code AuthenticationEntryPoint}. Fazer o filtro
 * responder 401 por conta propria quebraria toda rota publica acessada sem
 * sessao — ou seja, o site inteiro.
 *
 * <p><b>Stateless de verdade:</b> a autoridade sai do claim {@code role} do
 * proprio token, sem consulta ao banco. O preco e que um usuario rebaixado
 * continua ADMIN ate o access token expirar — no maximo 15 minutos, e o
 * refresh seguinte rele o papel do banco. O ganho e nao ter um SELECT em toda
 * requisicao do painel, que recarrega sozinho a cada poucos segundos.
 */
@Component
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

	/** O prefixo que o {@code hasRole("ADMIN")} do Spring Security espera. */
	private static final String PREFIXO_AUTORIDADE = "ROLE_";

	private final JwtService jwt;

	public JwtCookieAuthenticationFilter(JwtService jwt) {
		this.jwt = jwt;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
			FilterChain cadeia) throws ServletException, IOException {

		// Um contexto ja preenchido nao e sobrescrito: outro filtro pode ter
		// autenticado antes, e o ultimo a falar venceria sem querer.
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			autenticarSePossivel(requisicao);
		}
		cadeia.doFilter(requisicao, resposta);
	}

	private void autenticarSePossivel(HttpServletRequest requisicao) {
		String token = lerCookie(requisicao, CookieFactory.COOKIE_ACCESS);
		if (token == null) {
			return;
		}

		JwtService.UsuarioAutenticado usuario = this.jwt.validarAccessToken(token);
		if (usuario == null) {
			// Assinatura errada, formato invalido ou expirado: tratados da
			// mesma forma, de proposito. Ver JwtService.validarAccessToken.
			return;
		}

		var autenticacao = new UsernamePasswordAuthenticationToken(
				usuario,
				// Credencial nula: ela ja foi verificada ao assinar o token, e
				// guarda-la no contexto so criaria mais um lugar de onde pode
				// vazar.
				null,
				List.of(new SimpleGrantedAuthority(PREFIXO_AUTORIDADE + usuario.role().name())));
		autenticacao.setDetails(new WebAuthenticationDetailsSource().buildDetails(requisicao));

		SecurityContextHolder.getContext().setAuthentication(autenticacao);
	}

	private static String lerCookie(HttpServletRequest requisicao, String nome) {
		Cookie[] cookies = requisicao.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (nome.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
