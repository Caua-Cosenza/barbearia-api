package com.cauacosenza.barbearia.dto.response;

/**
 * Corpo de {@code POST /api/v1/auth/login}.
 *
 * <p>O envelope traz {@code { "user": { id, name, email } }}. Os dois tokens
 * <b>nao</b> aparecem aqui: viajam em cookies {@code HttpOnly}, que o
 * JavaScript do painel nao le. Devolve-los no corpo anularia a protecao —
 * bastaria um XSS para copiar o token de dentro da resposta.
 *
 * <p>O {@code name} sai decifrado de {@code users.name_encrypted}. O tipo do
 * frontend declara apenas {@code { id, email }}, mas o Node envia os tres e o
 * excedente e ignorado pelo TypeScript; manter os tres preserva a
 * equivalencia.
 *
 * <p>Nunca sai {@code passwordHash}, nem {@code role}, nem qualquer token.
 */
public record LoginResponse(Usuario user) {

	public record Usuario(String id, String name, String email) {

	}

}
