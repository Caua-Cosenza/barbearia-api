package com.cauacosenza.barbearia.dto.response;

/**
 * Corpo de {@code GET /api/v1/admin/health}.
 *
 * <p>Nao e um healthcheck de infraestrutura — para isso existe
 * {@code /actuator/health}. E o <b>teste de sessao</b> do painel: o
 * {@code AuthContext} chama esta rota no boot e usa o resultado para decidir
 * entre mostrar o dashboard e mandar para o login, porque o cookie e
 * {@code HttpOnly} e o JavaScript nao consegue inspeciona-lo.
 *
 * <p>Por isso <b>401 aqui e resposta normal</b>, nao falha: significa "nao ha
 * sessao". O valor {@code "admin ok"} e o literal que o Node devolve.
 */
public record AdminHealthResponse(String status) {

	public static AdminHealthResponse ok() {
		return new AdminHealthResponse("admin ok");
	}

}
