package com.cauacosenza.barbearia.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.request.AvailableTimesRequest;
import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.AvailableTimesResponse;
import com.cauacosenza.barbearia.service.AvailableTimesService;

/**
 * {@code POST /api/v1/available-times} — os horarios livres de um dia.
 *
 * <p>POST e nao GET, embora seja consulta. E o que o Node expoe e o que o
 * {@code endpoints.ts} chama; mudar para GET quebraria o frontend, que e o
 * criterio desta fase.
 *
 * <p>A rota mais chamada do fluxo: o modal a dispara a cada troca de data, com
 * 300ms de debounce. Dai o limite proprio de 30 por minuto — foi ajustado
 * depois que clientes trocando de data rapido tomavam 429.
 */
@RestController
@RequestMapping("/api/v1/available-times")
public class AvailableTimesController {

	private final AvailableTimesService horarios;

	public AvailableTimesController(AvailableTimesService horarios) {
		this.horarios = horarios;
	}

	@PostMapping
	public ApiResponse<AvailableTimesResponse> consultar(@Valid @RequestBody AvailableTimesRequest pedido) {
		return ApiResponse.ok(this.horarios.calcular(pedido));
	}

}
