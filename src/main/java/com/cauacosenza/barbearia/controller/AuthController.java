package com.cauacosenza.barbearia.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.request.LoginRequest;
import com.cauacosenza.barbearia.dto.request.RegisterRequest;
import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.LoginResponse;
import com.cauacosenza.barbearia.security.CookieFactory;
import com.cauacosenza.barbearia.service.AuthService;

/**
 * {@code /api/v1/auth/*} — entrar, renovar e sair.
 *
 * <p><b>Nenhum token aparece no corpo.</b> Os dois saem em cabecalhos
 * {@code Set-Cookie} com {@code HttpOnly}, e o JavaScript do painel nunca os
 * ve. E por isso que o {@code AuthContext} nao guarda nada e precisa chamar
 * {@code GET /admin/health} para descobrir se ha sessao.
 *
 * <p>As rotas sao publicas na {@code SecurityConfig} — tem que ser: quem vai
 * logar ainda nao esta autenticado. O {@code logout} tambem, porque um usuario
 * com access token expirado precisa conseguir limpar os cookies.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService autenticacao;

	private final CookieFactory cookies;

	public AuthController(AuthService autenticacao, CookieFactory cookies) {
		this.autenticacao = autenticacao;
		this.cookies = cookies;
	}

	/**
	 * Cria uma conta. Responde 201.
	 *
	 * <p>Nenhuma tela chama esta rota; existe para manter a equivalencia com o
	 * {@code routes/auth.ts} do Node. Ver {@code RegisterRequest} e o README.
	 */
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<AuthService.ContaCriada> registrar(@Valid @RequestBody RegisterRequest pedido) {
		return ApiResponse.ok(this.autenticacao.registrar(pedido), "Account created");
	}

	/** Autentica e grava os dois cookies. */
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest pedido) {
		AuthService.Sessao sessao = this.autenticacao.login(pedido);

		return comCookiesDaSessao(sessao)
				.body(ApiResponse.ok(sessao.corpo(), "Login successful"));
	}

	/**
	 * Troca o refresh token por um par novo.
	 *
	 * <p>O cookie chega por {@code @CookieValue} e nao pelo corpo: ele so e
	 * enviado nesta rota, por causa do {@code path} restrito definido em
	 * {@link CookieFactory}. Ausente, o {@code AuthService} responde 401 —
	 * o que e normal para quem nunca logou.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<ApiResponse<Void>> refresh(
			@CookieValue(name = CookieFactory.COOKIE_REFRESH, required = false) String refreshToken) {

		AuthService.Sessao sessao = this.autenticacao.renovar(refreshToken);

		return comCookiesDaSessao(sessao)
				.body(ApiResponse.ok(null, "Token refreshed"));
	}

	/**
	 * Encerra a sessao.
	 *
	 * <p>Limpa os cookies <b>sempre</b>, mesmo sem refresh token valido: o
	 * objetivo de quem clica em "sair" e ficar sem sessao, e responder erro o
	 * deixaria preso nela. O service apaga a linha de {@code refresh_tokens}
	 * quando ha o que apagar.
	 */
	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(
			@CookieValue(name = CookieFactory.COOKIE_REFRESH, required = false) String refreshToken) {

		this.autenticacao.logout(refreshToken);

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, this.cookies.limparAccessToken().toString())
				.header(HttpHeaders.SET_COOKIE, this.cookies.limparRefreshToken().toString())
				.body(ApiResponse.ok(null, "Logged out"));
	}

	/**
	 * Dois {@code Set-Cookie} na mesma resposta.
	 *
	 * <p>Precisam ser dois cabecalhos separados, e nao um com virgula: a
	 * especificacao de cookies nao admite varios cookies num unico
	 * {@code Set-Cookie}, e os navegadores guardariam apenas um deles.
	 */
	private ResponseEntity.BodyBuilder comCookiesDaSessao(AuthService.Sessao sessao) {
		ResponseCookie access = this.cookies.accessToken(sessao.accessToken());
		ResponseCookie refresh = this.cookies.refreshToken(sessao.refreshToken());

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, access.toString())
				.header(HttpHeaders.SET_COOKIE, refresh.toString());
	}

}
