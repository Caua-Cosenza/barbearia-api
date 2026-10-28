package com.cauacosenza.barbearia.controller;

import jakarta.validation.constraints.Pattern;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.CancelPageResponse;
import com.cauacosenza.barbearia.service.CancelService;

/**
 * {@code GET} e {@code POST /api/v1/cancel/{token}} — a tela de cancelamento.
 *
 * <p><b>O caminho e {@code /cancel/{token}}, nao
 * {@code /appointments/cancel/{token}}.</b> O plano previa o segundo; o
 * {@code endpoints.ts} chama {@code get(`/cancel/${token}`)} e
 * {@code post(`/cancel/${token}`, {})}, e no Node as rotas de cancelamento tem
 * prefixo proprio. O contrato e o frontend.
 *
 * <p>O {@code GET} alimenta a tela e o {@code POST} efetiva. O POST recebe
 * corpo vazio ({@code {}}) e nao o le: o token na URL e a unica entrada.
 *
 * <p>O token e validado como UUID aqui, como o
 * {@code z.string().uuid('Token inválido')} do Node — um token malformado
 * nunca chega ao banco. A consequencia e que <b>token invalido responde 422</b>
 * (erro de validacao) e token bem formado mas inexistente responde 404. O
 * frontend trata os dois como "link invalido", porque o axios rejeita qualquer
 * 4xx e a tela cai no mesmo estado.
 */
@RestController
@RequestMapping("/api/v1/cancel")
@Validated
public class CancelController {

	/** O mesmo formato de UUID que o Zod exige no Node. */
	private static final String UUID_REGEX =
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

	private final CancelService cancelamentos;

	public CancelController(CancelService cancelamentos) {
		this.cancelamentos = cancelamentos;
	}

	@GetMapping("/{token}")
	public ApiResponse<CancelPageResponse> consultar(
			@PathVariable @Pattern(regexp = UUID_REGEX, message = "Token inválido") String token) {

		return ApiResponse.ok(this.cancelamentos.consultar(token));
	}

	@PostMapping("/{token}")
	public ApiResponse<Void> cancelar(
			@PathVariable @Pattern(regexp = UUID_REGEX, message = "Token inválido") String token) {

		this.cancelamentos.cancelar(token);
		// data = null com success = true: nao ha o que devolver, e o frontend
		// so olha res.success.
		return ApiResponse.ok(null, "Agendamento cancelado com sucesso");
	}

}
