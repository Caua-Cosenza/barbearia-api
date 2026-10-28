package com.cauacosenza.barbearia.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.request.CreatePublicAppointmentRequest;
import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.PublicAppointmentResponse;
import com.cauacosenza.barbearia.service.PublicAppointmentService;

/**
 * {@code POST /api/v1/appointments/public} — a reserva de visitante.
 *
 * <p><b>O caminho e {@code /appointments/public}, nao {@code /appointments}.</b>
 * O plano desta fase previa o segundo; o {@code endpoints.ts} chama
 * {@code createPublic: (data) => post('/appointments/public', data)}, e o
 * contrato e o frontend. {@code POST /appointments} existe no Node para
 * cliente <i>logado</i>, com um corpo completamente diferente
 * ({@code professional_id} em snake_case, um servico so) — e assunto da
 * Fase 6.
 *
 * <p>Responde <b>201</b>, com a mensagem {@code "Appointment created"} que o
 * Node usa.
 */
@RestController
@RequestMapping("/api/v1/appointments")
public class PublicAppointmentController {

	private final PublicAppointmentService agendamentos;

	public PublicAppointmentController(PublicAppointmentService agendamentos) {
		this.agendamentos = agendamentos;
	}

	@PostMapping("/public")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<PublicAppointmentResponse> criar(
			@Valid @RequestBody CreatePublicAppointmentRequest pedido) {

		return ApiResponse.ok(this.agendamentos.criar(pedido), "Appointment created");
	}

}
